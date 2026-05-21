package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Residencia profesional: plazo de 6 meses desde [Documentos.anexo_xxxi.fecha_registro].
 * Correos: a los 2 y 4 meses, 3 días antes del límite, y al cerrar (concluido o vencido).
 */
@Service
class ResidenciaPlazoNotificacionService(
    private val egresadoRepository: EgresadoRepository,
    private val catalogoRepository: CatalogoRepository,
    private val emailService: EmailService,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(ResidenciaPlazoNotificacionService::class.java)

    private val zonaPlazo: ZoneId by lazy {
        val key = env.getProperty("sit.residencia.plazo.zona-horaria", "America/Mexico_City")!!.trim()
        try {
            ZoneId.of(key)
        } catch (_: Exception) {
            ZoneId.of("America/Mexico_City")
        }
    }

    private val habilitado: Boolean
        get() = env.getProperty("sit.residencia.notificaciones.enabled", "true")!!.trim().equals("true", ignoreCase = true)

    private val mesesPlazoResidencia: Long = 6L

    fun procesarTodosLosExpedientesResidenciaActivos() {
        if (!habilitado) return
        val todos = egresadoRepository.findAll()
        var procesados = 0
        for (e in todos) {
            if (!esResidenciaProfesional(e)) continue
            if (e.procesoActivoOrNull() == null) continue
            try {
                procesarPorId(e.id)
                procesados++
            } catch (ex: Exception) {
                log.warn("Notificación plazo residencia: error egresado id={}: {}", e.id, ex.message)
            }
        }
        log.info("Notificaciones plazo residencia: revisados {} expedientes", procesados)
    }

    fun procesarPorId(egresadoId: ObjectId?) {
        if (!habilitado || egresadoId == null) return
        val e = egresadoRepository.findById(egresadoId).orElse(null) ?: return
        procesarEgresado(e)
    }

    fun procesarEgresado(egresado: Egresado) {
        if (!habilitado || !esResidenciaProfesional(egresado)) return
        val p0 = egresado.procesoActivoOrNull() ?: return
        val inicio = fechaInicioPlazo(p0) ?: return
        val correo = egresado.datos_personales.correo_electronico?.trim().orEmpty()
        if (correo.isBlank()) {
            log.debug("Sin correo egresado id={}; se omiten notificaciones de plazo", egresado.id)
            return
        }

        val hoy = LocalDate.now(zonaPlazo)
        val inicioLocal = inicio.atZone(zonaPlazo).toLocalDate()
        val limite = inicioLocal.plusMonths(mesesPlazoResidencia)
        val diasRestantes = ChronoUnit.DAYS.between(hoy, limite).toInt()

        val nombre = listOf(
            egresado.datos_personales.nombre,
            egresado.datos_personales.apellido_paterno,
            egresado.datos_personales.apellido_materno,
        ).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Egresado" }

        if (procesoCerrado(p0)) {
            enviarFinalSiCorresponde(egresado, p0, correo, nombre, numeroControl = egresado.numero_control, limite, diasRestantes)
            return
        }

        val paso = pasoActualResidencia(p0)
        val limiteTexto = formatearFecha(limite)

        if (p0.notifPlazoRes2m == null && !hoy.isBefore(inicioLocal.plusMonths(2))) {
            val enviado = emailService.enviarAvisoPlazoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                numeroControl = egresado.numero_control,
                tipoAviso = "aviso_2_meses",
                diasRestantes = diasRestantes,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = paso.numero,
                pasoTitulo = paso.titulo,
            )
            if (enviado) guardarMarca(egresado, p0) { it.copy(notifPlazoRes2m = Instant.now()) }
        }

        val p1 = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
        if (procesoCerrado(p1)) return

        if (p1.notifPlazoRes4m == null && !hoy.isBefore(inicioLocal.plusMonths(4))) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val enviado = emailService.enviarAvisoPlazoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                numeroControl = egresado.numero_control,
                tipoAviso = "aviso_4_meses",
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActualResidencia(p1).numero,
                pasoTitulo = pasoActualResidencia(p1).titulo,
            )
            if (enviado) guardarMarca(egresado, p1) { it.copy(notifPlazoRes4m = Instant.now()) }
        }

        val p2 = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
        if (procesoCerrado(p2)) return

        val tresDiasAntes = limite.minusDays(3)
        if (p2.notifPlazoRes3d == null && !hoy.isBefore(tresDiasAntes)) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val enviado = emailService.enviarAvisoPlazoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                numeroControl = egresado.numero_control,
                tipoAviso = "aviso_3_dias",
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActualResidencia(p2).numero,
                pasoTitulo = pasoActualResidencia(p2).titulo,
            )
            if (enviado) guardarMarca(egresado, p2) { it.copy(notifPlazoRes3d = Instant.now()) }
        }
    }

    private fun enviarFinalSiCorresponde(
        egresado: Egresado,
        p: ProcesoTitulacion,
        correo: String,
        nombre: String,
        numeroControl: String,
        limite: LocalDate,
        diasRestantes: Int,
    ) {
        if (p.notifPlazoResFinal != null) return
        val paso = pasoActualResidencia(p)
        val vencido = p.estado_general.trim().equals("vencido", ignoreCase = true)
        val tipo = if (vencido) "final_vencido" else "final_concluido"
        val enviado = emailService.enviarAvisoPlazoResidencia(
            correoDestino = correo,
            nombreEgresado = nombre,
            numeroControl = numeroControl,
            tipoAviso = tipo,
            diasRestantes = diasRestantes,
            fechaLimiteTexto = formatearFecha(limite),
            pasoNumero = paso.numero,
            pasoTitulo = paso.titulo,
        )
        if (enviado) guardarMarca(egresado, p) { it.copy(notifPlazoResFinal = Instant.now()) }
    }

    private fun guardarMarca(egresado: Egresado, p: ProcesoTitulacion, patch: (ProcesoTitulacion) -> ProcesoTitulacion) {
        val actualizado = egresado.actualizarProcesoActivo(patch(p).copy(fecha_actualizacion = Instant.now()))
        egresadoRepository.save(actualizado)
    }

    private fun procesoCerrado(p: ProcesoTitulacion): Boolean {
        val estado = p.estado_general.trim().lowercase()
        if (estado == "titulado" || estado == "vencido") return true
        return p.fechaConfirmacionDocumentacionEscaneadaRecibida != null
    }

    private fun fechaInicioPlazo(p: ProcesoTitulacion): Instant? =
        p.documentos.anexo_xxxi.fecha_registro ?: p.fechaCreacion

    private fun esResidenciaProfesional(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
    }

    data class PasoResidenciaVista(val numero: Int, val titulo: String)

    fun pasoActualResidencia(p: ProcesoTitulacion): PasoResidenciaVista {
        val pasos: List<Pair<String, Boolean>> = listOf(
            "Registro de tu solicitud" to true,
            "Envío de solicitud al departamento académico" to (p.fechaEnviadoDepartamentoAcademico != null),
            "Recepción de anexos XXXII y XXXIII" to (p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null),
            "Recoger y firmar anexo 9.1" to (p.fechaCreacionAnexo91 != null),
            "Confirmación de anexo 9.1 firmado" to (p.fechaConfirmacionEntregaAnexo91 != null),
            "Solicitud del anexo 9.2" to (p.fechaSolicitudAnexo92 != null),
            "Constancia 9.2 recibida" to (p.fechaConfirmacionRecibidoAnexo92 != null),
            "Solicitud de sinodales" to (p.fechaSolicitudSinodales != null),
            "Oficio de sinodales recibido" to (p.fechaConfirmacionSinodalesRecibidos != null),
            "Acto protocolario agendado" to (p.fechaAgendaActo93 != null),
            "Anexo 9.3 generado" to (p.fechaCreacionAnexo93 != null),
            "Documentación escaneada" to (p.fechaEnvioDocumentacionEscaneadaEgresado != null),
            "Proceso concluido" to (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null),
        )
        val pendiente = pasos.indexOfFirst { !it.second }
        val idx = if (pendiente < 0) pasos.lastIndex else pendiente
        return PasoResidenciaVista(numero = idx + 1, titulo = pasos[idx].first)
    }

    private fun formatearFecha(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}
