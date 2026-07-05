package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.web.api.dto.ServiciosEscolaresBandejaItemDto
import com.sit_titulacion.sit.web.api.dto.ServiciosEscolaresDetalleDto
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class ServiciosEscolaresService(
    private val egresadoRepository: EgresadoRepository,
) {
    private val formatter = DateTimeFormatter.ISO_INSTANT
    private val formatterLegible = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(ZoneId.of("America/Mexico_City"))

    fun listarBandeja(estado: String, numeroControl: String?): List<ServiciosEscolaresBandejaItemDto> {
        val pendientes = estado != "atendidos"
        val filtroControl = numeroControl?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            egresadoRepository.findCandidatosBandejaServiciosEscolares()
                .asSequence()
                .filter { e -> enBandeja(e, pendientes) }
                .filter { e ->
                    if (filtroControl == null) true
                    else e.numero_control.contains(filtroControl, ignoreCase = true)
                }
                .sortedByDescending { it.procesoActivoOrNull()?.fechaSolicitudAnexo92 }
                .map { toBandejaItem(it) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun obtenerDetalle(id: String): ServiciosEscolaresDetalleDto? {
        val oid = runCatching { ObjectId(id) }.getOrNull() ?: return null
        val e = egresadoRepository.findByObjectIdConTimeout(oid) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        if (p.fechaSolicitudAnexo92 == null) return null
        return toDetalle(e)
    }

    fun confirmarConstancia92(id: String): Boolean {
        val oid = runCatching { ObjectId(id) }.getOrNull() ?: return false
        val e = egresadoRepository.findByObjectIdConTimeout(oid) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudAnexo92 == null) return false
        if (p.fechaAceptacionServiciosEscolaresAnexo92 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(fechaAceptacionServiciosEscolaresAnexo92 = ahora, fecha_actualizacion = ahora),
            ),
        )
        return true
    }

    fun revertirAceptacionConstancia92(id: String): String? {
        val oid = runCatching { ObjectId(id) }.getOrNull() ?: return "Expediente no encontrado."
        val e = egresadoRepository.findByObjectIdConTimeout(oid) ?: return "Expediente no encontrado."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaSolicitudAnexo92 == null) return "No hay solicitud de anexo 9.2."
        if (p.fechaAceptacionServiciosEscolaresAnexo92 == null) {
            return "La solicitud no está marcada como atendida."
        }
        if (p.fechaConfirmacionRecibidoAnexo92 != null) {
            return "No se puede revertir: la DEP ya confirmó la recepción del anexo 9.2."
        }
        if (p.fechaSolicitudSinodales != null) {
            return "No se puede revertir: el trámite ya avanzó en el proceso."
        }
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(fechaAceptacionServiciosEscolaresAnexo92 = null, fecha_actualizacion = ahora),
            ),
        )
        return null
    }

    private fun enBandeja(e: Egresado, pendientes: Boolean): Boolean {
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudAnexo92 == null) return false
        val atendido = p.fechaAceptacionServiciosEscolaresAnexo92 != null
        return pendientes == !atendido
    }

    private fun nombreCompleto(e: Egresado): String =
        listOf(
            e.datos_personales.nombre,
            e.datos_personales.apellido_paterno,
            e.datos_personales.apellido_materno,
        ).filter { !it.isNullOrBlank() }.joinToString(" ")

    private fun toBandejaItem(e: Egresado): ServiciosEscolaresBandejaItemDto {
        val p = e.procesoActivoOrNull()!!
        val fechaSolicitud = p.fechaSolicitudAnexo92!!
        return ServiciosEscolaresBandejaItemDto(
            id = e.id!!.toHexString(),
            numero_control = e.numero_control,
            nombre_completo = nombreCompleto(e),
            carrera = e.datos_personales.carrera,
            fecha_solicitud_anexo_9_2 = formatter.format(fechaSolicitud),
        )
    }

    private fun toDetalle(e: Egresado): ServiciosEscolaresDetalleDto {
        val p = e.procesoActivoOrNull()!!
        val doc = p.documentos.constancia_no_inconveniencia
        val fechaConstancia = doc.fecha_expedicion?.let { formatterLegible.format(it) }
        val fecha91 = p.fechaCreacionAnexo91?.let { formatterLegible.format(it) }
        val fechaSolicitud = p.fechaSolicitudAnexo92!!
        return ServiciosEscolaresDetalleDto(
            id = e.id!!.toHexString(),
            numero_control = e.numero_control,
            nombre_completo = nombreCompleto(e),
            fecha_constancia_no_inconveniencia = fechaConstancia,
            fecha_creacion_anexo_9_1 = fecha91,
            modalidad = p.datos_proyecto.modalidad,
            nombre_proyecto = p.datos_proyecto.nombre_proyecto,
            fecha_solicitud_anexo_9_2 = formatter.format(fechaSolicitud),
            fecha_aceptacion_servicios_escolares_anexo_9_2 =
                p.fechaAceptacionServiciosEscolaresAnexo92?.let { formatter.format(it) },
        )
    }
}
