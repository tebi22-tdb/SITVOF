package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Tesis / monografía (flujo 7 pasos, no residencia):
 * - Desarrollo: 12 meses (tesis) o 18 (monografía) desde confirmación DEP paso 3.
 *   Correos a 3, 6 y 9 meses (tesis) o 6, 12 y 15 (monografía), más 3 días antes del límite.
 * - Trámite: 6 meses desde liberación en departamento.
 *   Correos a 2 y 4 meses, aviso de inicio de fase, 3 días antes y cierre (concluido/vencido).
 */
@Service
class NoResidenciaPlazoNotificacionService(
    private val egresadoRepository: EgresadoRepository,
    private val catalogoRepository: CatalogoRepository,
    private val emailService: EmailService,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(NoResidenciaPlazoNotificacionService::class.java)

    private val zonaPlazo: ZoneId by lazy {
        val key = env.getProperty("sit.plazo.no-res.zona-horaria", "America/Mexico_City")!!.trim()
        try {
            ZoneId.of(key)
        } catch (_: Exception) {
            ZoneId.of("America/Mexico_City")
        }
    }

    private val habilitado: Boolean
        get() = env.getProperty("sit.plazo.no-res.notificaciones.enabled", "true")!!
            .trim().equals("true", ignoreCase = true)

    private val mesesTramite: Long = 6L

    fun procesarTodosLosExpedientesNoResidenciaActivos() {
        if (!habilitado) return
        var procesados = 0
        for (e in egresadoRepository.findAll()) {
            if (esResidenciaProfesional(e)) continue
            if (e.procesoActivoOrNull() == null) continue
            if (!esFlujo7(e.procesoActivoOrNull()!!)) continue
            try {
                procesarPorId(e.id)
                procesados++
            } catch (ex: Exception) {
                log.warn("Notificación plazo no residencia: error egresado id={}: {}", e.id, ex.message)
            }
        }
        log.info("Notificaciones plazo no residencia: revisados {} expedientes", procesados)
    }

    fun procesarPorId(egresadoId: ObjectId?) {
        if (!habilitado || egresadoId == null) return
        val e = egresadoRepository.findById(egresadoId).orElse(null) ?: return
        procesarEgresado(e)
    }

    fun procesarEgresado(egresado: Egresado) {
        if (!habilitado || esResidenciaProfesional(egresado)) return
        val p0 = egresado.procesoActivoOrNull() ?: return
        if (!esFlujo7(p0)) return

        val correo = egresado.datos_personales.correo_electronico?.trim().orEmpty()
        if (correo.isBlank()) {
            log.debug("Sin correo egresado id={}; se omiten notificaciones de plazo no residencia", egresado.id)
            return
        }

        val nombre = nombreCompleto(egresado)
        val paso = pasoActualNoResFlujo7(p0)

        if (procesoCerrado(p0)) {
            enviarFinalSiCorresponde(egresado, p0, correo, nombre, paso)
            return
        }

        if (p0.fechaEnviadoDepartamentoAcademico != null) {
            return
        }

        val liberacion = p0.fechaSolicitudRegistroLiberacionDeptoAcademico
        if (liberacion != null) {
            procesarFaseTramite(egresado, p0, correo, nombre, liberacion, paso)
            return
        }

        val inicioDev = p0.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii ?: return
        procesarFaseDesarrollo(egresado, p0, correo, nombre, inicioDev, p0.datos_proyecto.modalidad)
    }

    private fun procesarFaseDesarrollo(
        egresado: Egresado,
        p: ProcesoTitulacion,
        correo: String,
        nombre: String,
        inicio: Instant,
        modalidad: String,
    ) {
        val hoy = LocalDate.now(zonaPlazo)
        val inicioLocal = inicio.atZone(zonaPlazo).toLocalDate()
        val mesesDev = mesesDesarrollo(modalidad)
        val limite = inicioLocal.plusMonths(mesesDev)
        val diasRestantes = ChronoUnit.DAYS.between(hoy, limite).toInt()
        val limiteTexto = formatearFecha(limite)
        val etiquetaModalidad = etiquetaModalidad(modalidad)
        val avisosMeses = avisosMesesDesarrollo(mesesDev)

        val marcas = listOf(
            Triple(avisosMeses[0], p.notifPlazoNrDev3m) { proc: ProcesoTitulacion -> proc.copy(notifPlazoNrDev3m = Instant.now()) },
            Triple(avisosMeses[1], p.notifPlazoNrDev6m) { proc: ProcesoTitulacion -> proc.copy(notifPlazoNrDev6m = Instant.now()) },
            Triple(avisosMeses[2], p.notifPlazoNrDev9m) { proc: ProcesoTitulacion -> proc.copy(notifPlazoNrDev9m = Instant.now()) },
        )

        var proceso = p
        for ((mesesTranscurridos, marca, patch) in marcas) {
            if (marca != null) continue
            if (hoy.isBefore(inicioLocal.plusMonths(mesesTranscurridos))) continue
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val pasoActual = pasoActualNoResFlujo7(proceso)
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_dev_${mesesTranscurridos}_meses",
                fase = "desarrollo",
                mesesPlazoTotal = mesesDev,
                mesesTranscurridos = mesesTranscurridos,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActual.numero,
                pasoTitulo = pasoActual.titulo,
            )
            if (enviado) {
                guardarMarca(egresado, proceso, patch)
                proceso = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
            }
            if (procesoCerrado(proceso) || proceso.fechaSolicitudRegistroLiberacionDeptoAcademico != null) return
        }

        val pAct = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
        if (procesoCerrado(pAct) || pAct.fechaSolicitudRegistroLiberacionDeptoAcademico != null) return

        val tresDiasAntes = limite.minusDays(3)
        if (pAct.notifPlazoNrDev3d == null && !hoy.isBefore(tresDiasAntes)) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val pasoActual = pasoActualNoResFlujo7(pAct)
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_dev_3_dias",
                fase = "desarrollo",
                mesesPlazoTotal = mesesDev,
                mesesTranscurridos = null,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActual.numero,
                pasoTitulo = pasoActual.titulo,
            )
            if (enviado) guardarMarca(egresado, pAct) { it.copy(notifPlazoNrDev3d = Instant.now()) }
        }
    }

    private fun procesarFaseTramite(
        egresado: Egresado,
        p: ProcesoTitulacion,
        correo: String,
        nombre: String,
        inicio: Instant,
        paso: PasoNoResVista,
    ) {
        val hoy = LocalDate.now(zonaPlazo)
        val inicioLocal = inicio.atZone(zonaPlazo).toLocalDate()
        val limite = inicioLocal.plusMonths(mesesTramite)
        val limiteTexto = formatearFecha(limite)
        val etiquetaModalidad = etiquetaModalidad(p.datos_proyecto.modalidad)

        if (p.notifPlazoNrInicioTram == null) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_inicio_tramite",
                fase = "tramite",
                mesesPlazoTotal = mesesTramite,
                mesesTranscurridos = null,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = paso.numero,
                pasoTitulo = paso.titulo,
            )
            if (enviado) guardarMarca(egresado, p) { it.copy(notifPlazoNrInicioTram = Instant.now()) }
        }

        var proceso = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
        if (procesoCerrado(proceso)) {
            enviarFinalSiCorresponde(egresado, proceso, correo, nombre, pasoActualNoResFlujo7(proceso))
            return
        }

        if (proceso.notifPlazoNrTram2m == null && !hoy.isBefore(inicioLocal.plusMonths(2))) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val pasoActual = pasoActualNoResFlujo7(proceso)
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_tram_2_meses",
                fase = "tramite",
                mesesPlazoTotal = mesesTramite,
                mesesTranscurridos = 2,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActual.numero,
                pasoTitulo = pasoActual.titulo,
            )
            if (enviado) {
                guardarMarca(egresado, proceso) { it.copy(notifPlazoNrTram2m = Instant.now()) }
                proceso = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
            }
        }

        if (procesoCerrado(proceso)) {
            enviarFinalSiCorresponde(egresado, proceso, correo, nombre, pasoActualNoResFlujo7(proceso))
            return
        }

        if (proceso.notifPlazoNrTram4m == null && !hoy.isBefore(inicioLocal.plusMonths(4))) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val pasoActual = pasoActualNoResFlujo7(proceso)
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_tram_4_meses",
                fase = "tramite",
                mesesPlazoTotal = mesesTramite,
                mesesTranscurridos = 4,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActual.numero,
                pasoTitulo = pasoActual.titulo,
            )
            if (enviado) {
                guardarMarca(egresado, proceso) { it.copy(notifPlazoNrTram4m = Instant.now()) }
                proceso = egresadoRepository.findById(egresado.id!!).orElse(egresado).procesoActivoOrNull() ?: return
            }
        }

        if (procesoCerrado(proceso)) {
            enviarFinalSiCorresponde(egresado, proceso, correo, nombre, pasoActualNoResFlujo7(proceso))
            return
        }

        val tresDiasAntes = limite.minusDays(3)
        if (proceso.notifPlazoNrTram3d == null && !hoy.isBefore(tresDiasAntes)) {
            val dias = ChronoUnit.DAYS.between(hoy, limite).toInt()
            val pasoActual = pasoActualNoResFlujo7(proceso)
            val enviado = emailService.enviarAvisoPlazoNoResidencia(
                correoDestino = correo,
                nombreEgresado = nombre,
                tipoAviso = "aviso_tram_3_dias",
                fase = "tramite",
                mesesPlazoTotal = mesesTramite,
                mesesTranscurridos = null,
                etiquetaModalidad = etiquetaModalidad,
                diasRestantes = dias,
                fechaLimiteTexto = limiteTexto,
                pasoNumero = pasoActual.numero,
                pasoTitulo = pasoActual.titulo,
            )
            if (enviado) guardarMarca(egresado, proceso) { it.copy(notifPlazoNrTram3d = Instant.now()) }
        }
    }

    private fun enviarFinalSiCorresponde(
        egresado: Egresado,
        p: ProcesoTitulacion,
        correo: String,
        nombre: String,
        paso: PasoNoResVista,
    ) {
        if (p.notifPlazoNrFinal != null) return
        val vencido = p.estado_general.trim().equals("vencido", ignoreCase = true)
        val tipo = if (vencido) "final_vencido" else "final_concluido"
        val enviado = emailService.enviarAvisoPlazoNoResidencia(
            correoDestino = correo,
            nombreEgresado = nombre,
            tipoAviso = tipo,
            fase = if (p.fechaSolicitudRegistroLiberacionDeptoAcademico != null) "tramite" else "desarrollo",
            mesesPlazoTotal = if (p.fechaSolicitudRegistroLiberacionDeptoAcademico != null) mesesTramite
            else mesesDesarrollo(p.datos_proyecto.modalidad),
            mesesTranscurridos = null,
            etiquetaModalidad = etiquetaModalidad(p.datos_proyecto.modalidad),
            diasRestantes = 0,
            fechaLimiteTexto = "",
            pasoNumero = paso.numero,
            pasoTitulo = paso.titulo,
        )
        if (enviado) guardarMarca(egresado, p) { it.copy(notifPlazoNrFinal = Instant.now()) }
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

    private fun esFlujo7(p: ProcesoTitulacion): Boolean =
        p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null ||
            p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null

    private fun esResidenciaProfesional(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
    }

    private fun mesesDesarrollo(modalidad: String): Long {
        val m = modalidad.trim().lowercase()
        return if (m.contains("monograf")) 18L else 12L
    }

    private fun avisosMesesDesarrollo(mesesDev: Long): List<Long> =
        if (mesesDev == 18L) listOf(6L, 12L, 15L) else listOf(3L, 6L, 9L)

    private fun etiquetaModalidad(modalidad: String): String {
        val m = modalidad.trim().lowercase()
        return when {
            m.contains("monograf") -> "Monografía"
            m.contains("tesina") -> "Tesina"
            else -> "Tesis"
        }
    }

    private fun nombreCompleto(egresado: Egresado): String =
        listOf(
            egresado.datos_personales.nombre,
            egresado.datos_personales.apellido_paterno,
            egresado.datos_personales.apellido_materno,
        ).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Egresado" }

    data class PasoNoResVista(val numero: Int, val titulo: String)

    fun pasoActualNoResFlujo7(p: ProcesoTitulacion): PasoNoResVista {
        val pasos: List<Pair<String, Boolean>> = listOf(
            "Entrega en la DEP (solicitud y anteproyecto)" to (
                p.fechaConfirmacionEntregaEgresadoDepto != null ||
                    p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
                ),
            "Envío de anteproyecto al departamento académico" to (
                p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
                ),
            "Confirmación de registro de tesis (paso 3)" to (
                p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null
                ),
            "Desarrollo del proyecto" to (p.fechaRecepcionTrabajoDivisionEstudiosProf != null),
            "Liberación de producto en departamento" to (
                p.fechaSolicitudRegistroLiberacionDeptoAcademico != null
                ),
            "Recepción de liberación en la DEP" to (p.fechaRecepcionRegistroLiberacionDeptoAcademico != null),
            "Envío a Apoyo a Titulación (CAT)" to (p.fechaEnviadoDepartamentoAcademico != null),
        )
        val pendiente = pasos.indexOfFirst { !it.second }
        val idx = if (pendiente < 0) pasos.lastIndex else pendiente
        return PasoNoResVista(numero = idx + 1, titulo = pasos[idx].first)
    }

    private fun formatearFecha(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}
