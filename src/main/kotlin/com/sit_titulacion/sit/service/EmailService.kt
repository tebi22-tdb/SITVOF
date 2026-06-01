package com.sit_titulacion.sit.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
class EmailService(
    @Autowired(required = false) private val mailSender: JavaMailSender?,
    @Value("\${spring.mail.username:}") private val fromEmail: String,
) {
    private val log = LoggerFactory.getLogger(EmailService::class.java)

    /**
     * Envía las credenciales al correo del destinatario.
     *
     * @return `true` si el mensaje se entregó por SMTP; `false` si se omitió (sin configuración, datos incompletos).
     * @throws Exception si SMTP está configurado pero falla el envío.
     */
    fun enviarCredenciales(
        correoDestino: String,
        usuario: String,
        password: String,
    ): Boolean {
        if (password.isBlank()) {
            log.warn("No se envía correo con credenciales: contraseña vacía (usuario={})", usuario)
            return false
        }
        if (correoDestino.isBlank()) {
            log.warn("No se puede enviar correo: correo destino vacío")
            return false
        }
        if (mailSender == null || fromEmail.isBlank()) {
            log.warn("Spring Mail no configurado. Credenciales (usuario={}): envío omitido.", usuario)
            return false
        }
        val mensaje = SimpleMailMessage().apply {
            setFrom(fromEmail)
            setTo(correoDestino.trim())
            subject = "SITVO - Tus credenciales de acceso"
            text = """
                Hola,

                Se ha creado tu cuenta en el Sistema Integral de Titulación (SITVO).

                Usuario: $usuario
                Contraseña: $password

                Guarda estas credenciales para iniciar sesión.

                Saludos.
            """.trimIndent()
        }
        try {
            mailSender.send(mensaje)
            log.info("Correo enviado a {} con credenciales para usuario {}", correoDestino, usuario)
            return true
        } catch (e: Exception) {
            log.error("Error al enviar correo a {}: {}", correoDestino, e.message)
            throw e
        }
    }

    fun enviarRecuperacionPassword(
        correoDestino: String,
        usuario: String,
        nuevaPassword: String,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Recuperación de contraseña: correo no enviado (configuración incompleta o destino vacío)")
            return false
        }
        val mensaje = SimpleMailMessage().apply {
            setFrom(fromEmail)
            setTo(correoDestino.trim())
            subject = "SITVO - Nueva contraseña"
            text = """
                Hola,

                Se ha generado una nueva contraseña para tu cuenta en el Sistema Integral de Titulación (SITVO).

                Usuario: $usuario
                Nueva contraseña: $nuevaPassword

                Por seguridad, te recomendamos contactar a la coordinación para establecer una contraseña personal.

                Si no solicitaste este cambio, ignora este correo — tu cuenta sigue siendo segura.

                Saludos.
            """.trimIndent()
        }
        return try {
            mailSender.send(mensaje)
            log.info("Correo de recuperación enviado a {} (usuario {})", correoDestino, usuario)
            true
        } catch (e: Exception) {
            log.error("Error al enviar correo de recuperación a {}: {}", correoDestino, e.message)
            false
        }
    }

    /**
     * Envía el Anexo 9.3 (citatorio) como adjunto PDF a los sinodales asignados.
     *
     * @return número de correos enviados correctamente.
     */
    fun enviarAnexo93Sinodales(
        destinatarios: List<String>,
        nombreEgresado: String,
        numeroControl: String,
        fechaActo: String,
        pdfBytes: ByteArray,
        fileName: String,
    ): Int {
        if (mailSender == null || fromEmail.isBlank()) {
            log.warn("Spring Mail no configurado. No se enviaron correos del Anexo 9.3 a sinodales.")
            return 0
        }
        val logoResource = try {
            ClassPathResource("templates/html/logo-itvo.png").takeIf { it.exists() }
        } catch (_: Exception) { null }

        var enviados = 0
        for (correo in destinatarios.filter { it.isNotBlank() }) {
            try {
                val mime = mailSender.createMimeMessage()
                // multipart/related permite imágenes CID inline + adjuntos
                val helper = MimeMessageHelper(mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8")
                helper.setFrom(fromEmail)
                helper.setTo(correo.trim())
                helper.setSubject("SITVO – Citatorio Acto Protocolario: $nombreEgresado")

                val logoTag = if (logoResource != null)
                    """<img src="cid:logo-itvo" alt="ITVO" width="64" height="64" style="display:block;border-radius:8px;border:2px solid rgba(255,255,255,0.3);">"""
                else ""

                helper.setText("""
                    <!DOCTYPE html>
                    <html lang="es">
                    <head><meta charset="UTF-8"></head>
                    <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
                      <table width="100%" cellpadding="0" cellspacing="0" style="background:#f4f6f9;padding:32px 0;">
                        <tr><td align="center">
                          <table width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:10px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);max-width:600px;">

                            <!-- Encabezado -->
                            <tr>
                              <td style="background:#1f4c8f;padding:24px 32px;">
                                <table cellpadding="0" cellspacing="0">
                                  <tr>
                                    <td style="vertical-align:middle;padding-right:18px;">$logoTag</td>
                                    <td style="vertical-align:middle;">
                                      <p style="margin:0;font-size:12px;color:#a8c4e8;letter-spacing:0.6px;text-transform:uppercase;font-weight:500;">Instituto Tecnológico del Valle de Oaxaca</p>
                                      <p style="margin:5px 0 0;font-size:18px;font-weight:700;color:#ffffff;line-height:1.2;">División de Estudios Profesionales</p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>

                            <!-- Cuerpo -->
                            <tr>
                              <td style="padding:36px 36px 32px;">

                                <p style="margin:0 0 22px;font-size:15px;color:#1f2937;line-height:1.6;">
                                  A quien corresponda:
                                </p>

                                <p style="margin:0 0 28px;font-size:15px;font-weight:600;color:#1f4c8f;line-height:1.7;border-left:4px solid #1f4c8f;padding-left:14px;">
                                  La División de Estudios Profesionales le comunica la calendarización y horario del acto protocolario.
                                </p>

                                <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                                  Adjunto a este correo encontrará el <strong>Anexo 9.3 (Citatorio)</strong> con los detalles completos del acto protocolario.
                                </p>

                              </td>
                            </tr>

                            <!-- Pie -->
                            <tr>
                              <td style="background:#f0f6fd;padding:18px 36px;border-top:1px solid #c8d8ec;">
                                <p style="margin:0;font-size:12px;color:#6b7280;line-height:1.6;">
                                  Este mensaje fue generado automáticamente por el <strong>Sistema Integral de Titulación ITVO (SITVO)</strong>.<br>
                                  Por favor no responda a este correo.
                                </p>
                              </td>
                            </tr>

                          </table>
                        </td></tr>
                      </table>
                    </body>
                    </html>
                """.trimIndent(), true)

                // Logo inline (CID) — va antes del adjunto PDF para que los clientes lo asocien al HTML
                if (logoResource != null) {
                    helper.addInline("logo-itvo", logoResource)
                }
                helper.addAttachment(fileName, ByteArrayResource(pdfBytes))
                mailSender.send(mime)
                log.info("Anexo 9.3 enviado a sinodal {} (egresado {})", correo, numeroControl)
                enviados++
            } catch (ex: Exception) {
                log.error("Error al enviar Anexo 9.3 a sinodal {}: {}", correo, ex.message)
            }
        }
        return enviados
    }

    /**
     * Avisa al egresado que la DEP agendó fecha y horario del acto protocolario.
     *
     * @return `true` si el mensaje se entregó por SMTP; `false` si se omitió o falló sin lanzar excepción.
     */
    fun enviarAvisoActoProtocolarioAgendado(
        correoDestino: String,
        nombreEgresado: String,
        numeroControl: String,
        fechaHoraActoTexto: String,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn(
                "Aviso acto protocolario agendado no enviado (SMTP o correo vacío), control={}",
                numeroControl,
            )
            return false
        }
        val mensaje = SimpleMailMessage().apply {
            setFrom(fromEmail)
            setTo(correoDestino.trim())
            subject = "SITVO – Acto protocolario agendado"
            text = """
                Hola $nombreEgresado,

                La División de Estudios Profesionales agendó tu acto protocolario.

                Fecha y horario: $fechaHoraActoTexto

                Revisa tu seguimiento en https://sitvo.net para continuar con tu trámite.

                Saludos,
                SITVO.
            """.trimIndent()
        }
        return try {
            mailSender.send(mensaje)
            log.info("Aviso acto protocolario agendado enviado a {} control={}", correoDestino, numeroControl)
            true
        } catch (e: Exception) {
            log.error("Error al enviar aviso acto protocolario agendado a {}: {}", correoDestino, e.message)
            false
        }
    }

    /**
     * Avisos de plazo (solo residencia): 2 meses, 4 meses, 3 días antes del límite, o cierre del proceso.
     */
    fun enviarAvisoPlazoResidencia(
        correoDestino: String,
        nombreEgresado: String,
        numeroControl: String,
        tipoAviso: String,
        diasRestantes: Int,
        fechaLimiteTexto: String,
        pasoNumero: Int,
        pasoTitulo: String,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Aviso plazo residencia no enviado (SMTP o correo vacío), control={}", numeroControl)
            return false
        }
        val (subject, cuerpo) = plantillaAvisoPlazoResidencia(
            nombreEgresado = nombreEgresado,
            tipoAviso = tipoAviso,
            diasRestantes = diasRestantes,
            fechaLimiteTexto = fechaLimiteTexto,
            pasoNumero = pasoNumero,
            pasoTitulo = pasoTitulo,
        )
        val mensaje = SimpleMailMessage().apply {
            setFrom(fromEmail)
            setTo(correoDestino.trim())
            this.subject = subject
            text = cuerpo
        }
        return try {
            mailSender.send(mensaje)
            log.info("Aviso plazo residencia ({}) enviado a {} control={}", tipoAviso, correoDestino, numeroControl)
            true
        } catch (e: Exception) {
            log.error("Error aviso plazo residencia a {}: {}", correoDestino, e.message)
            false
        }
    }

    private fun plantillaAvisoPlazoResidencia(
        nombreEgresado: String,
        tipoAviso: String,
        diasRestantes: Int,
        fechaLimiteTexto: String,
        pasoNumero: Int,
        pasoTitulo: String,
    ): Pair<String, String> {
        val firma = "Saludos,\nDivisión de Estudios Profesionales"
        val pasoLinea = when (tipoAviso) {
            "final_concluido" -> "Estado: tu proceso quedó concluido en el sistema."
            "final_vencido" -> "Estado: tu proceso se encuentra marcado como vencido."
            else -> "Paso actual: $pasoNumero — $pasoTitulo."
        }
        val tiempoLinea = when {
            diasRestantes < 0 ->
                "El plazo de 6 meses (desde la fecha de registro del Anexo XXXI) venció hace ${-diasRestantes} día(s). La fecha límite era $fechaLimiteTexto."
            diasRestantes == 0 ->
                "Hoy ($fechaLimiteTexto) es el último día de tu plazo de 6 meses contado desde el Anexo XXXI."
            else ->
                "Te faltan $diasRestantes día(s) para la fecha límite del proceso ($fechaLimiteTexto), contados desde el Anexo XXXI."
        }
        return when (tipoAviso) {
            "aviso_2_meses" -> "SITVO – Aviso de plazo (2 meses)" to """
                Hola $nombreEgresado,

                Han transcurrido 2 meses desde la fecha de registro de tu Anexo XXXI.

                $tiempoLinea
                $pasoLinea

                Continúa tu trámite en el SITVO y ante la División de Estudios Profesionales según corresponda.

                $firma
            """.trimIndent()

            "aviso_4_meses" -> "SITVO – Aviso de plazo (4 meses)" to """
                Hola $nombreEgresado,

                Han transcurrido 4 meses desde la fecha de registro de tu Anexo XXXI.

                $tiempoLinea
                $pasoLinea

                Te recomendamos avanzar con los pasos pendientes antes del vencimiento del plazo total de 6 meses.

                $firma
            """.trimIndent()

            "aviso_3_dias" -> "SITVO – Último aviso: plazo por vencer (3 días)" to """
                Hola $nombreEgresado,

                Tu plazo de 6 meses para concluir el proceso de Residencia Profesional (desde el Anexo XXXI) está por terminar.

                $tiempoLinea
                $pasoLinea

                Si aún tienes trámites pendientes, atiéndelos de inmediato.

                $firma
            """.trimIndent()

            "final_concluido" -> "SITVO – Tu proceso de titulación concluyó" to """
                Hola $nombreEgresado,

                Tu proceso de titulación por Residencia Profesional quedó concluido en el sistema.

                $pasoLinea

                Ya no recibirás avisos automáticos de plazo por este trámite.

                $firma
            """.trimIndent()

            else -> "SITVO – Plazo de titulación vencido" to """
                Hola $nombreEgresado,

                Tu proceso de titulación por Residencia Profesional se marcó como vencido: se superó el plazo de 6 meses desde la fecha de registro del Anexo XXXI.

                La fecha límite era $fechaLimiteTexto.
                $pasoLinea

                Comunícate con la División de Estudios Profesionales para conocer las opciones disponibles.

                $firma
            """.trimIndent()
        }
    }

    /**
     * Avisos de plazo tesis/monografía (flujo 7): desarrollo (12/18 meses desde paso 3) y trámite (6 meses tras liberación).
     */
    fun enviarAvisoPlazoNoResidencia(
        correoDestino: String,
        nombreEgresado: String,
        tipoAviso: String,
        fase: String,
        mesesPlazoTotal: Long,
        mesesTranscurridos: Long?,
        etiquetaModalidad: String,
        diasRestantes: Int,
        fechaLimiteTexto: String,
        pasoNumero: Int,
        pasoTitulo: String,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Aviso plazo no residencia no enviado (SMTP o correo vacío), tipo={}", tipoAviso)
            return false
        }
        val (subject, cuerpo) = plantillaAvisoPlazoNoResidencia(
            nombreEgresado = nombreEgresado,
            tipoAviso = tipoAviso,
            fase = fase,
            mesesPlazoTotal = mesesPlazoTotal,
            mesesTranscurridos = mesesTranscurridos,
            etiquetaModalidad = etiquetaModalidad,
            diasRestantes = diasRestantes,
            fechaLimiteTexto = fechaLimiteTexto,
            pasoNumero = pasoNumero,
            pasoTitulo = pasoTitulo,
        )
        val mensaje = SimpleMailMessage().apply {
            setFrom(fromEmail)
            setTo(correoDestino.trim())
            this.subject = subject
            text = cuerpo
        }
        return try {
            mailSender.send(mensaje)
            log.info("Aviso plazo no residencia ({}) enviado a {}", tipoAviso, correoDestino)
            true
        } catch (e: Exception) {
            log.error("Error aviso plazo no residencia a {}: {}", correoDestino, e.message)
            false
        }
    }

    private fun plantillaAvisoPlazoNoResidencia(
        nombreEgresado: String,
        tipoAviso: String,
        fase: String,
        mesesPlazoTotal: Long,
        mesesTranscurridos: Long?,
        etiquetaModalidad: String,
        diasRestantes: Int,
        fechaLimiteTexto: String,
        pasoNumero: Int,
        pasoTitulo: String,
    ): Pair<String, String> {
        val firma = "Saludos,\nDivisión de Estudios Profesionales"
        val pasoLinea = when (tipoAviso) {
            "final_concluido" -> "Estado: tu proceso quedó concluido en el sistema."
            "final_vencido" -> "Estado: tu proceso se encuentra marcado como vencido."
            else -> "Paso actual: $pasoNumero — $pasoTitulo."
        }
        val origenDesarrollo =
            "desde la confirmación de la DEP del registro de tu $etiquetaModalidad (paso 3 del proceso)"
        val origenTramite = "desde la liberación de producto en tu departamento académico"
        val origen = if (fase == "tramite") origenTramite else origenDesarrollo
        val tiempoLinea = when {
            tipoAviso.startsWith("final_") -> ""
            diasRestantes < 0 ->
                "El plazo de $mesesPlazoTotal meses ($origen) venció hace ${-diasRestantes} día(s). La fecha límite era $fechaLimiteTexto."
            diasRestantes == 0 ->
                "Hoy ($fechaLimiteTexto) es el último día de tu plazo de $mesesPlazoTotal meses ($origen)."
            else ->
                "Te faltan $diasRestantes día(s) para la fecha límite ($fechaLimiteTexto), dentro del plazo de $mesesPlazoTotal meses ($origen)."
        }
        val bloqueTiempo = if (tiempoLinea.isNotBlank()) "$tiempoLinea\n" else ""

        return when (tipoAviso) {
            "aviso_inicio_tramite" -> "SITVO – Inicio del plazo de trámite (6 meses)" to """
                Hola $nombreEgresado,

                Tu $etiquetaModalidad fue liberada en el departamento académico. Comenzó el plazo de 6 meses calendario para concluir el trámite de titulación posterior a la liberación.

                $bloqueTiempo$pasoLinea

                Continúa tu trámite en el SITVO y con la División de Estudios Profesionales según corresponda.

                $firma
            """.trimIndent()

            "aviso_dev_3_meses", "aviso_dev_6_meses", "aviso_dev_9_meses",
            "aviso_dev_12_meses", "aviso_dev_15_meses" -> {
                val m = mesesTranscurridos ?: 0
                "SITVO – Aviso de plazo de desarrollo ($m meses)" to """
                    Hola $nombreEgresado,

                    Han transcurrido $m meses $origenDesarrollo.

                    $bloqueTiempo$pasoLinea

                    Continúa el desarrollo de tu $etiquetaModalidad y los trámites pendientes antes del vencimiento del plazo total de $mesesPlazoTotal meses.

                    $firma
                """.trimIndent()
            }

            "aviso_dev_3_dias" -> "SITVO – Último aviso: plazo de desarrollo por vencer (3 días)" to """
                Hola $nombreEgresado,

                Tu plazo de $mesesPlazoTotal meses para el desarrollo de tu $etiquetaModalidad ($origenDesarrollo) está por terminar.

                $bloqueTiempo$pasoLinea

                Si aún debes liberar producto o completar trámites, atiéndelos de inmediato.

                $firma
            """.trimIndent()

            "aviso_tram_2_meses" -> "SITVO – Aviso de plazo de trámite (2 meses)" to """
                Hola $nombreEgresado,

                Han transcurrido 2 meses $origenTramite.

                $bloqueTiempo$pasoLinea

                Continúa con los pasos pendientes antes del vencimiento del plazo total de 6 meses.

                $firma
            """.trimIndent()

            "aviso_tram_4_meses" -> "SITVO – Aviso de plazo de trámite (4 meses)" to """
                Hola $nombreEgresado,

                Han transcurrido 4 meses $origenTramite.

                $bloqueTiempo$pasoLinea

                Te recomendamos avanzar con los pasos pendientes antes del vencimiento del plazo de 6 meses.

                $firma
            """.trimIndent()

            "aviso_tram_3_dias" -> "SITVO – Último aviso: plazo de trámite por vencer (3 días)" to """
                Hola $nombreEgresado,

                Tu plazo de 6 meses para concluir el trámite tras la liberación de tu $etiquetaModalidad está por terminar.

                $bloqueTiempo$pasoLinea

                Si aún tienes trámites pendientes, atiéndelos de inmediato.

                $firma
            """.trimIndent()

            "final_concluido" -> "SITVO – Tu proceso de titulación concluyó" to """
                Hola $nombreEgresado,

                Tu proceso de titulación por $etiquetaModalidad quedó concluido en el sistema.

                $pasoLinea

                Ya no recibirás avisos automáticos de plazo por este trámite.

                $firma
            """.trimIndent()

            else -> "SITVO – Plazo de titulación vencido" to """
                Hola $nombreEgresado,

                Tu proceso de titulación por $etiquetaModalidad se marcó como vencido: se superó el plazo vigente del trámite.

                $pasoLinea

                Comunícate con la División de Estudios Profesionales para conocer las opciones disponibles.

                $firma
            """.trimIndent()
        }
    }
}
