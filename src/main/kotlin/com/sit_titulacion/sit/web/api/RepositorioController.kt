package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.web.api.dto.TituladoPublicoDto
import org.bson.types.ObjectId
import org.springframework.core.io.InputStreamResource
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId

@RestController
@RequestMapping("/api/repositorio")
class RepositorioController(
    private val egresadoRepository: EgresadoRepository,
    private val gridFsTemplate: GridFsTemplate,
) {

    @GetMapping
    fun listar(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<List<TituladoPublicoDto>> {
        val titulados = egresadoRepository.findConDocumentacionConfirmada()
            .filter { e ->
                val modalidad = e.procesos.lastOrNull { it.fechaConfirmacionDocumentacionEscaneadaRecibida != null }
                    ?.datos_proyecto?.modalidad ?: e.procesoActivoOrNull()?.datos_proyecto?.modalidad ?: ""
                !modalidad.contains("ceneval", ignoreCase = true)
            }
            .sortedByDescending { it.procesos.lastOrNull { p -> p.fechaConfirmacionDocumentacionEscaneadaRecibida != null }?.fechaConfirmacionDocumentacionEscaneadaRecibida ?: it.fechaCreacion }
            .map { e ->
                val p = e.datos_personales
                val pr = e.procesos.lastOrNull { it.fechaConfirmacionDocumentacionEscaneadaRecibida != null }
                    ?: e.procesoActivoOrNull()
                val proy = pr?.datos_proyecto
                val nombre = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                val anio = (pr?.fechaTitulacion ?: pr?.fechaCreacionAnexo93 ?: e.fechaCreacion)
                    .atZone(ZoneId.systemDefault()).year
                val gridfsId = pr?.gridfsIdDocFinal ?: pr?.documento_adjunto?.gridfs_id
                TituladoPublicoDto(
                    egresadoId = e.id?.toHexString() ?: "",
                    nombre = nombre.ifBlank { "—" },
                    carrera = p.carrera,
                    nivel = p.nivel,
                    modalidad = proy?.modalidad ?: "",
                    nombreProyecto = proy?.nombre_proyecto ?: "",
                    asesorInterno = proy?.asesor_interno,
                    asesorExterno = proy?.asesor_externo,
                    director = proy?.director,
                    asesor1 = proy?.asesor_1,
                    asesor2 = proy?.asesor_2,
                    anio = anio,
                    tieneDocumento = gridfsId != null,
                )
            }
        return ResponseEntity.ok(titulados)
    }

    @GetMapping("/{egresadoId}/documento")
    fun descargarDocumento(
        @PathVariable egresadoId: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) {
            return ResponseEntity.badRequest().build<Any>()
        }
        val eg = egresadoRepository.findById(oid).orElse(null)
            ?: return ResponseEntity.notFound().build<Any>()
        val pr = eg.procesos.lastOrNull { it.fechaConfirmacionDocumentacionEscaneadaRecibida != null }
            ?: eg.procesoActivoOrNull()
            ?: return ResponseEntity.notFound().build<Any>()

        val gridfsId = pr.gridfsIdDocFinal ?: pr.documento_adjunto.gridfs_id
            ?: return ResponseEntity.notFound().build<Any>()

        val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(gridfsId)))
            ?: return ResponseEntity.notFound().build<Any>()
        val resource = gridFsTemplate.getResource(file)

        val adj = pr.documento_adjunto
        val contentType = adj.content_type.ifBlank { "application/pdf" }
        val fileName = adj.nombre_original.ifBlank { "documento.pdf" }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$fileName\"")
            .contentType(MediaType.parseMediaType(contentType))
            .body(InputStreamResource(resource.inputStream))
    }
}
