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
}
