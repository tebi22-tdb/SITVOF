package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.service.ReporteExcelService
import com.sit_titulacion.sit.service.ReporteFiltros
import com.sit_titulacion.sit.service.ReporteService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reportes")
class ReporteController(
    private val reporteService: ReporteService,
    private val reporteExcelService: ReporteExcelService,
) {

    /** Reporte consolidado — sin scope de departamento (ve todo). Solo administrador/apoyo_titulacion. */
    @GetMapping("/coordinador/resumen")
    fun resumenCoordinador(
        @RequestParam(required = false) fecha_desde: String?,
        @RequestParam(required = false) fecha_hasta: String?,
        @RequestParam(required = false) modalidad: String?,
        @RequestParam(required = false) carrera: String?,
        @RequestParam(required = false) generacion: Int?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<Any> {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        }
        if (!principal.getRol().esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo coordinación puede ver reportes"))
        }
        return ResponseEntity.ok(reporteService.resumenCoordinador(filtrosDesde(fecha_desde, fecha_hasta, modalidad, carrera, generacion)))
    }

    /**
     * El directorio consultable — mismos filtros de fecha/modalidad/carrera
     * que [resumenCoordinador], más 3 propios de este listado. Ver
     * [ReporteService.listadoEgresados].
     */
    @GetMapping("/coordinador/listado-egresados")
    fun listadoEgresados(
        @RequestParam(required = false) fecha_desde: String?,
        @RequestParam(required = false) fecha_hasta: String?,
        @RequestParam(required = false) modalidad: String?,
        @RequestParam(required = false) carrera: String?,
        @RequestParam(required = false) generacion: Int?,
        @RequestParam(required = false) generacion_ingreso: Int?,
        @RequestParam(required = false) departamento_slug: String?,
        @RequestParam(required = false) estado_general: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<Any> {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        }
        if (!principal.getRol().esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo coordinación puede ver reportes"))
        }
        val filtros = filtrosDesde(fecha_desde, fecha_hasta, modalidad, carrera, generacion)
        return ResponseEntity.ok(
            reporteService.listadoEgresados(
                filtros,
                generacionIngreso = generacion_ingreso,
                departamentoSlug = departamento_slug?.trim()?.ifBlank { null },
                estadoGeneral = estado_general?.trim()?.ifBlank { null },
            ),
        )
    }

    /** Libro de Excel con el resumen general (KPIs) — mismos filtros que [resumenCoordinador]. */
    @GetMapping("/coordinador/exportar.xlsx")
    fun exportarExcel(
        @RequestParam(required = false) fecha_desde: String?,
        @RequestParam(required = false) fecha_hasta: String?,
        @RequestParam(required = false) modalidad: String?,
        @RequestParam(required = false) carrera: String?,
        @RequestParam(required = false) generacion: Int?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<Any> {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        }
        if (!principal.getRol().esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo coordinación puede exportar reportes"))
        }
        val filtros = filtrosDesde(fecha_desde, fecha_hasta, modalidad, carrera, generacion)
        val bytes = reporteExcelService.generar(reporteService.resumenCoordinador(filtros))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte-titulacion.xlsx\"")
            .body(bytes)
    }

    /** Libro de Excel del listado — documento propio, no una hoja del resumen general (ver [ReporteExcelService]). */
    @GetMapping("/coordinador/listado-egresados/exportar.xlsx")
    fun exportarListadoExcel(
        @RequestParam(required = false) fecha_desde: String?,
        @RequestParam(required = false) fecha_hasta: String?,
        @RequestParam(required = false) modalidad: String?,
        @RequestParam(required = false) carrera: String?,
        @RequestParam(required = false) generacion: Int?,
        @RequestParam(required = false) generacion_ingreso: Int?,
        @RequestParam(required = false) departamento_slug: String?,
        @RequestParam(required = false) estado_general: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<Any> {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        }
        if (!principal.getRol().esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo coordinación puede exportar reportes"))
        }
        val filtros = filtrosDesde(fecha_desde, fecha_hasta, modalidad, carrera, generacion)
        val items = reporteService.listadoEgresados(
            filtros,
            generacionIngreso = generacion_ingreso,
            departamentoSlug = departamento_slug?.trim()?.ifBlank { null },
            estadoGeneral = estado_general?.trim()?.ifBlank { null },
        )
        val bytes = reporteExcelService.generarListado(items)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"listado-egresados.xlsx\"")
            .body(bytes)
    }

    private fun filtrosDesde(
        fecha_desde: String?,
        fecha_hasta: String?,
        modalidad: String?,
        carrera: String?,
        generacion: Int?,
    ) = ReporteFiltros(
        fechaDesde = ReporteService.parseFecha(fecha_desde),
        fechaHasta = ReporteService.parseFecha(fecha_hasta, finDeDia = true),
        modalidad = modalidad?.trim()?.ifBlank { null },
        carrera = carrera?.trim()?.ifBlank { null },
        generacion = generacion,
    )
}
