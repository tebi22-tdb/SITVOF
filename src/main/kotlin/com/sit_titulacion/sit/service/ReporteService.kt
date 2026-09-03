package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Departamento
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.domain.NumeroControl
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.RevisionRepository
import com.sit_titulacion.sit.web.api.dto.ReporteConteoDto
import com.sit_titulacion.sit.web.api.dto.ReporteEgresadoListadoDto
import com.sit_titulacion.sit.web.api.dto.ReporteEmbudoEtapaDto
import com.sit_titulacion.sit.web.api.dto.ReporteFiltrosAplicadosDto
import com.sit_titulacion.sit.web.api.dto.ReporteResumenCoordinadorDto
import com.sit_titulacion.sit.web.api.dto.ReporteTasaAprobacionDto
import com.sit_titulacion.sit.web.api.dto.ReporteTasaTitulacionDto
import com.sit_titulacion.sit.web.api.dto.ReporteTendenciaMensualDto
import com.sit_titulacion.sit.web.api.dto.ReporteTiempoEtapaDto
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class ReporteFiltros(
    val fechaDesde: Instant? = null,
    val fechaHasta: Instant? = null,
    val modalidad: String? = null,
    val carrera: String? = null,
    val generacion: Int? = null,
)

/**
 * Reportes agregados de titulación — un solo módulo, exclusivo de
 * coordinador (ver `ReporteController`). `resumenCoordinador` ve todo (Fase
 * 1); `listadoEgresados` es el directorio consultable (nombre, número de
 * control, carrera, generación de ingreso, estado) que reemplazó los
 * resúmenes agregados "por departamento"/"servicios escolares" — el caso de
 * uso real es "dame la lista de titulados de tal generación", no otro
 * agregado más.
 *
 * Sigue el mismo estilo del resto del proyecto: `findAll()` + filtrado en
 * memoria en vez de un aggregation pipeline de Mongo (ningún repositorio del
 * proyecto usa pipelines hoy; el volumen de un plantel no lo justifica —
 * si algún día crece, este es el lugar aislado donde migrar, sin tocar el
 * resto del código).
 *
 * Reutiliza [EgresadoService.clasificarPlazo] para el bucket en_tiempo/
 * rezagado/vencido/concluido — es la MISMA clasificación que ya usa el
 * dashboard (Fase 0.2), no una copia: si algún día cambia la regla de
 * plazos, el reporte no se puede desincronizar del dashboard por definición.
 */
@Service
class ReporteService(
    private val egresadoRepository: EgresadoRepository,
    private val revisionRepository: RevisionRepository,
    private val departamentoRepository: DepartamentoRepository,
    private val egresadoService: EgresadoService,
) {
    private val zone = ZoneId.systemDefault()

    /** Etapas del proceso a medir — clave, etiqueta y de dónde sale cada fecha. Ajustable sin tocar el resto. */
    private data class DefinicionEtapa(
        val clave: String,
        val etiqueta: String,
        val inicio: (ProcesoTitulacion) -> Instant?,
        val fin: (ProcesoTitulacion) -> Instant?,
    )

    /**
     * "Liberación del producto" no es un solo campo — depende del sub-flujo:
     * el camino simple de no-residencia (y residencia, vía `liberar()`) usa
     * `fechaRecibidoRegistroLiberacion`; el camino extendido de no-residencia
     * (con solicitud de registro de anteproyecto) usa
     * `fechaLiberacionDocumentoCoordinacionCat` en su lugar — ver
     * `RevisionService.crear`. Cualquiera de los dos que esté lleno cuenta.
     */
    private fun fechaLiberacion(p: ProcesoTitulacion): Instant? =
        p.fechaRecibidoRegistroLiberacion ?: p.fechaLiberacionDocumentoCoordinacionCat

    /**
     * Una fila por bloque/módulo responsable, siguiendo la cadena real del
     * proceso verificada precondición por precondición contra
     * `EgresadoService.kt` (no el orden en que están declarados los campos):
     * registro → envío a depto. académico → (revisiones, si no es residencia
     * — vive en la colección `revisiones`, no en `ProcesoTitulacion`, por eso
     * no tiene su propia etapa medible aquí; su tiempo queda implícito dentro
     * del tramo "envío → liberación") → liberación del producto →
     * confirmación de anexos XXXI/XXXII → Anexo 9.1 → Anexo 9.2 (pasa por
     * Servicios Escolares en medio) → Sinodales → Acto 9.3 → Documentación
     * escaneada (= titulado, ver `confirmarDocumentacionEscaneadaRecibida`).
     */
    private val etapas = listOf(
        DefinicionEtapa(
            "registro_a_envio_departamento", "De registro a envío a depto. académico",
            { it.fechaCreacion }, { it.fechaEnviadoDepartamentoAcademico },
        ),
        DefinicionEtapa(
            "envio_a_liberacion", "De envío a depto. académico a liberación del producto",
            { it.fechaEnviadoDepartamentoAcademico }, { fechaLiberacion(it) },
        ),
        DefinicionEtapa(
            "liberacion_a_confirmacion_anexos", "De liberación del producto a confirmación de anexos XXXI/XXXII",
            { fechaLiberacion(it) }, { it.fechaConfirmacionRecibidosAnexoXxxiXxxii },
        ),
        DefinicionEtapa(
            "anexo_9_1", "Anexo 9.1: de creación a entrega",
            { it.fechaCreacionAnexo91 }, { it.fechaConfirmacionEntregaAnexo91 },
        ),
        DefinicionEtapa(
            "anexo_9_2", "Anexo 9.2: de solicitud a confirmación de recibido",
            { it.fechaSolicitudAnexo92 }, { it.fechaConfirmacionRecibidoAnexo92 },
        ),
        DefinicionEtapa(
            "sinodales", "Sinodales: de solicitud a confirmación de recibidos",
            { it.fechaSolicitudSinodales }, { it.fechaConfirmacionSinodalesRecibidos },
        ),
        DefinicionEtapa(
            "acto_9_3", "Acto 9.3: de agenda a entrega del anexo",
            { it.fechaAgendaActo93 }, { it.fechaConfirmacionEntregaAnexo93 },
        ),
        DefinicionEtapa(
            "documentacion_escaneada", "Documentación escaneada: de solicitud a confirmación",
            { it.fechaSolicitudDocumentacionEscaneada }, { it.fechaConfirmacionDocumentacionEscaneadaRecibida },
        ),
    )

    /** Hitos del embudo de avance — secuencia propia (no reutiliza `etapas`, que mide duración entre pares de fechas). */
    private data class DefinicionHito(
        val clave: String,
        val etiqueta: String,
        val fecha: (ProcesoTitulacion) -> Instant?,
    )

    private val hitosEmbudo = listOf(
        DefinicionHito("registrados", "Registrados") { it.fechaCreacion },
        DefinicionHito("enviado_departamento", "Enviado a depto. académico") { it.fechaEnviadoDepartamentoAcademico },
        DefinicionHito("sinodales_solicitados", "Sinodales solicitados") { it.fechaSolicitudSinodales },
        DefinicionHito("sinodales_asignados", "Sinodales asignados") { it.fechaAsignacionSinodales },
        DefinicionHito("anexo_93_entregado", "Anexo 9.3 entregado") { it.fechaConfirmacionEntregaAnexo93 },
        // Un solo hito final: confirmarDocumentacionEscaneadaRecibida marca
        // fechaConfirmacionDocumentacionEscaneadaRecibida y fechaTitulacion
        // en el mismo instante (ver EgresadoService) — mostrar los dos como
        // hitos separados sería repetir la misma cuenta dos veces.
        DefinicionHito("titulados", "Titulados") { it.fechaTitulacion },
    )

    private val mesesAbrev = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")

    /** Vista global — sin scope de carrera. Solo coordinador (ver `ReporteController`). */
    fun resumenCoordinador(filtros: ReporteFiltros): ReporteResumenCoordinadorDto = construirResumen(filtros)

    /**
     * El directorio consultable: una fila por egresado que cumpla el mismo
     * filtro de fecha/modalidad/carrera de [resumenCoordinador], más 3
     * filtros propios de este listado. `departamentoId`/`estadoGeneral` se
     * comparan tal cual (vienen de un select, no de texto libre del
     * usuario); `generacionIngreso` se deriva del número de control con
     * [NumeroControl.generacionIngreso] — el filtro solo puede encontrar
     * egresados cuyo número de control sí matchea el patrón. `departamentoSlug`
     * usa el mismo `slug` que ya expone el catálogo al frontend
     * (`CatalogoController./departamentos`) — se resuelve aquí al `id` real
     * de [Departamento] (Fase 0.3) para comparar contra
     * `DatosPersonales.departamentoId`, sin exponer un endpoint nuevo solo
     * para este filtro.
     */
    fun listadoEgresados(
        filtros: ReporteFiltros,
        generacionIngreso: Int?,
        departamentoSlug: String?,
        estadoGeneral: String?,
    ): List<ReporteEgresadoListadoDto> {
        val departamentosActivos = departamentoRepository.findByActivoTrue()
        val departamentosPorId = departamentosActivos.associateBy { it.id.toString() }
        val idDepartamentoFiltro = departamentoSlug?.let { slug -> departamentosActivos.find { it.slug == slug }?.id }

        return egresadoRepository.findAll()
            .filter { e -> cumpleFiltros(e, filtros) }
            .filter { e ->
                if (generacionIngreso == null) true
                else NumeroControl.generacionIngreso(e.numero_control) == generacionIngreso
            }
            .filter { e ->
                if (departamentoSlug == null) true
                // si el slug no resolvió a ningún departamento activo, no debe "colarse"
                // el bucket de sin-departamento — el filtro simplemente no encuentra nada.
                else idDepartamentoFiltro != null && e.datos_personales.departamentoId == idDepartamentoFiltro
            }
            .mapNotNull { e ->
                val p = e.procesoActivoOrNull() ?: return@mapNotNull null
                if (estadoGeneral != null && p.estado_general != estadoGeneral) return@mapNotNull null
                filaListado(e, p, departamentosPorId)
            }
    }

    private fun filaListado(
        e: Egresado,
        p: ProcesoTitulacion,
        departamentosPorId: Map<String, Departamento>,
    ): ReporteEgresadoListadoDto {
        val dp = e.datos_personales
        return ReporteEgresadoListadoDto(
            id = e.id.toString(),
            nombre_completo = "${dp.nombre} ${dp.apellido_paterno} ${dp.apellido_materno}".trim(),
            numero_control = e.numero_control,
            carrera = dp.carrera,
            departamento_nombre = dp.departamentoId?.toString()?.let { departamentosPorId[it]?.nombre },
            generacion_ingreso = NumeroControl.generacionIngreso(e.numero_control),
            modalidad = p.datos_proyecto.modalidad,
            estado_general = p.estado_general,
            estado_plazo = egresadoService.clasificarPlazo(e, p).estado,
            fecha_titulacion = p.fechaTitulacion?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
        )
    }

    private fun construirResumen(filtros: ReporteFiltros): ReporteResumenCoordinadorDto {
        val filtrados = egresadoRepository.findAll().filter { e -> cumpleFiltros(e, filtros) }
        val procesos = filtrados.mapNotNull { it.procesoActivoOrNull() }

        return ReporteResumenCoordinadorDto(
            filtros = filtrosAplicadosDto(filtros),
            total_procesos = filtrados.size,
            conteo_por_estado_general = procesos.groupingBy { it.estado_general }.eachCount(),
            conteo_por_estado_plazo = filtrados
                .map { e -> egresadoService.clasificarPlazo(e, e.procesoActivoOrNull()).estado }
                .groupingBy { it }.eachCount(),
            distribucion_modalidad = distribucion(procesos.map { it.datos_proyecto.modalidad }),
            distribucion_carrera = distribucion(filtrados.map { it.datos_personales.carrera }),
            tiempos_promedio_etapa_dias = calcularTiemposEtapa(procesos),
            tasa_aprobacion_primera_revision = calcularTasaAprobacion(filtrados),
            tasa_titulacion = calcularTasaTitulacion(filtrados),
            tendencia_mensual = calcularTendenciaMensual(procesos),
            embudo_etapas = calcularEmbudoEtapas(procesos),
        )
    }

    private fun cumpleFiltros(e: Egresado, f: ReporteFiltros): Boolean {
        val p = e.procesoActivoOrNull() ?: return false
        if (f.fechaDesde != null && p.fechaCreacion.isBefore(f.fechaDesde)) return false
        if (f.fechaHasta != null && p.fechaCreacion.isAfter(f.fechaHasta)) return false
        if (f.modalidad != null &&
            NormalizacionTexto.normalizarClave(p.datos_proyecto.modalidad) != NormalizacionTexto.normalizarClave(f.modalidad)
        ) return false
        if (f.carrera != null &&
            NormalizacionTexto.normalizarClave(e.datos_personales.carrera) != NormalizacionTexto.normalizarClave(f.carrera)
        ) return false
        if (f.generacion != null && p.fechaCreacion.atZone(zone).year != f.generacion) return false
        return true
    }

    private fun distribucion(valores: List<String>): List<ReporteConteoDto> {
        val total = valores.size
        if (total == 0) return emptyList()
        return valores.groupingBy { it.trim().ifBlank { "—" } }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { (etiqueta, cantidad) -> ReporteConteoDto(etiqueta, cantidad, cantidad * 100.0 / total) }
    }

    private fun calcularTiemposEtapa(procesos: List<ProcesoTitulacion>): List<ReporteTiempoEtapaDto> =
        etapas.map { etapa ->
            val duraciones = procesos.mapNotNull { p ->
                val inicio = etapa.inicio(p) ?: return@mapNotNull null
                val fin = etapa.fin(p) ?: return@mapNotNull null
                ChronoUnit.DAYS.between(inicio, fin).takeIf { it >= 0 } // defensivo: fechas invertidas no entran al promedio
            }
            ReporteTiempoEtapaDto(
                etapa = etapa.clave,
                etiqueta = etapa.etiqueta,
                dias_promedio = if (duraciones.isNotEmpty()) duraciones.average() else null,
                muestra = duraciones.size,
            )
        }

    /** Cuántos procesos se registraron cada mes (`fechaCreacion`), orden cronológico ascendente. */
    private fun calcularTendenciaMensual(procesos: List<ProcesoTitulacion>): List<ReporteTendenciaMensualDto> {
        val porMes = procesos.groupingBy { p ->
            val fecha = p.fechaCreacion.atZone(zone)
            "%04d-%02d".format(fecha.year, fecha.monthValue)
        }.eachCount()
        return porMes.entries.sortedBy { it.key }.map { (mes, cantidad) ->
            val anio = mes.substring(0, 4)
            val mesNum = mes.substring(5, 7).toInt()
            ReporteTendenciaMensualDto(mes = mes, etiqueta = "${mesesAbrev[mesNum - 1]} $anio", cantidad = cantidad)
        }
    }

    /**
     * Cuántos procesos ya tienen la fecha de cada hito llena — cada hito se
     * cuenta de forma independiente (no exige que el hito anterior también
     * esté lleno), así datos inconsistentes no distorsionan los siguientes.
     */
    private fun calcularEmbudoEtapas(procesos: List<ProcesoTitulacion>): List<ReporteEmbudoEtapaDto> =
        hitosEmbudo.map { hito ->
            ReporteEmbudoEtapaDto(
                clave = hito.clave,
                etiqueta = hito.etiqueta,
                cantidad = procesos.count { hito.fecha(it) != null },
            )
        }

    private fun calcularTasaAprobacion(egresados: List<Egresado>): ReporteTasaAprobacionDto {
        val ids = egresados.mapNotNull { it.id }.toSet()
        if (ids.isEmpty()) return ReporteTasaAprobacionDto(0, 0, 0.0)
        val primerasRevisiones = revisionRepository.findByEgresadoIdIn(ids).filter { it.numeroRevision == 1 }
        val total = primerasRevisiones.size
        val aprobadas = primerasRevisiones.count { it.resultado == "aprobado" }
        return ReporteTasaAprobacionDto(
            total_primeras_revisiones = total,
            aprobadas_en_primera_revision = aprobadas,
            porcentaje = if (total > 0) aprobadas * 100.0 / total else 0.0,
        )
    }

    /**
     * De los egresados filtrados, cuántos ya llegaron a `estado_general =
     * "titulado"` en su proceso ACTIVO — cada `Egresado` aporta un solo
     * conteo sin importar cuántos intentos previos tenga en `procesos`
     * (vencidos o de otra modalidad), porque solo se mira el último.
     */
    private fun calcularTasaTitulacion(egresados: List<Egresado>): ReporteTasaTitulacionDto {
        val total = egresados.size
        val titulados = egresados.count { it.procesoActivoOrNull()?.estado_general == "titulado" }
        return ReporteTasaTitulacionDto(
            total_procesos = total,
            titulados = titulados,
            porcentaje = if (total > 0) titulados * 100.0 / total else 0.0,
        )
    }

    private fun filtrosAplicadosDto(f: ReporteFiltros) = ReporteFiltrosAplicadosDto(
        fecha_desde = f.fechaDesde?.toString(),
        fecha_hasta = f.fechaHasta?.toString(),
        modalidad = f.modalidad,
        carrera = f.carrera,
        generacion = f.generacion?.toString(),
    )

    companion object {
        /** yyyy-MM-dd (mismo formato que ya acepta el resto del API). Null si falta o es inválida. */
        fun parseFecha(s: String?, finDeDia: Boolean = false): Instant? {
            if (s.isNullOrBlank()) return null
            return try {
                val date = java.time.LocalDate.parse(s)
                if (finDeDia) date.atTime(23, 59, 59, 999_999_999).atZone(ZoneId.systemDefault()).toInstant()
                else date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
