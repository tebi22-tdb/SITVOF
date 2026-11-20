package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.AdjuntoCorreoGuardado
import com.sit_titulacion.sit.domain.CorreoPendienteGuardado
import com.sit_titulacion.sit.repository.CorreoPendienteRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore

enum class RolSinodalCorreo {
    PRESIDENTE,
    SECRETARIO,
    VOCAL,
    VOCAL_SUPLENTE,
}

data class SinodalCorreoDestino(
    val correo: String,
    val rol: RolSinodalCorreo,
)

/** Snapshot del estado del envío de correos, para mostrar un aviso en el panel si el SMTP está fallando. */
data class EstadoSmtp(
    val ok: Boolean,
    val ultimoError: String?,
    val momento: java.time.Instant?,
)

/** Módulo de la pantalla donde debe verse el aviso mientras el correo sigue en cola. */
object ModuloCorreo {
    const val ALTA_EGRESADOS = "alta-egresados"
    const val ALTA_USUARIOS = "alta-usuarios"
    const val SEGUIMIENTO = "seguimiento"
    const val DEPARTAMENTO = "departamento"
}

data class AvisoCorreoModulo(
    val modulo: String,
    val segmento: String?,
)

/** Cuántos citatorios aceptó el SMTP y cuántos quedaron en reintento. */
data class ResultadoCorreosSinodales(
    val aceptados: Int = 0,
    val enProceso: Int = 0,
    val correosAceptados: List<String> = emptyList(),
    val correosRechazados: List<String> = emptyList(),
)

/** El citatorio de este egresado ya está en la cola. No se encola otra copia. */
internal class CorreoEnCursoException : RuntimeException()

internal const val MARCA_CITATORIO_SINODAL = "anexo93"

@Service
class EmailService(
    @Autowired(required = false) private val mailSender: JavaMailSender?,
    @Value("\${spring.mail.username:}") private val fromEmail: String,
    @Value("\${sit.cert.base-url:https://sitvo.net}") private val baseUrl: String,
    private val configInstitucional: ConfiguracionInstitucionalService,
    @Value("\${sit.mail.reintento.espera-inicial-ms:15000}") private val esperaInicialMs: Long = 15_000,
    @Autowired(required = false) private val correoPendienteRepository: CorreoPendienteRepository? = null,
    @Autowired(required = false) private val eventos: ApplicationEventPublisher? = null,
) {
    private val log = LoggerFactory.getLogger(EmailService::class.java)

    // Solo importa el último resultado: si el envío más reciente falló, hay un problema activo
    // que vale la pena mostrar en el panel. Un éxito posterior lo limpia automáticamente.
    @Volatile private var ultimoError: String? = null
    @Volatile private var momentoUltimoError: java.time.Instant? = null

    /** Correos que el SMTP no aceptó por un corte o una demora. Se reintentan hasta que salgan. */
    private val pendientes = mutableListOf<CorreoPendiente>()
    /** El egresado y los sinodales salen a la vez; no se forman uno detrás de otro. */
    private val envioCupo = Semaphore(5)

    /**
     * Hilos propios para el SMTP. El pool común de Java, en un servidor de un solo núcleo,
     * se queda bloqueado si una tarea espera a otras tareas del mismo pool: el aviso del
     * egresado sale y el de los sinodales no arranca.
     */
    private val correoExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { tarea ->
        Thread(tarea, "sit-correo").apply { isDaemon = true }
    }

    fun ejecutarEnvio(bloque: () -> Unit) {
        correoExecutor.execute {
            try {
                bloque()
            } catch (ex: Exception) {
                log.error("El envío en segundo plano falló: {}", ex.message)
            }
        }
    }

    fun citatorioYaEnCola(egresadoId: String, correo: String): Boolean {
        val id = egresadoId.trim()
        val destino = correo.trim()
        if (id.isBlank() || destino.isBlank()) return false
        return synchronized(pendientes) {
            pendientes.any {
                it.marca == MARCA_CITATORIO_SINODAL &&
                    it.egresadoId == id &&
                    it.correoDestino.equals(destino, ignoreCase = true)
            }
        }
    }

    @PostConstruct
    fun cargarCorreosPendientes() {
        val repo = correoPendienteRepository ?: return
        val guardados = try {
            repo.findAll()
        } catch (ex: Exception) {
            log.warn("No se pudieron cargar correos pendientes: {}", ex.message)
            return
        }
        synchronized(pendientes) {
            for (doc in guardados) {
                if (pendientes.any { it.id == doc.id }) continue
                pendientes.add(
                    CorreoPendiente(
                        id = doc.id,
                        correoDestino = doc.correoDestino,
                        subject = doc.subject,
                        saludo = doc.saludo,
                        mensajeDestacado = doc.mensajeDestacado,
                        cuerpoHtml = doc.cuerpoHtml,
                        adjuntos = doc.adjuntos.map { it.nombre to it.contenido },
                        modulos = doc.modulos,
                        segmento = doc.segmento,
                        intentos = doc.intentos,
                        proximoIntentoEpochMs = minOf(doc.proximoIntentoEpochMs, System.currentTimeMillis()),
                        egresadoId = doc.egresadoId,
                        marca = doc.marca,
                        referencia = doc.referencia,
                    ),
                )
            }
        }
        if (guardados.isNotEmpty()) {
            log.info("Hay {} correo(s) pendiente(s) de un arranque anterior. Se seguirán intentando.", guardados.size)
        }
    }

    private fun registrarExito() {
        if (synchronized(pendientes) { pendientes.isNotEmpty() }) return
        ultimoError = null
        momentoUltimoError = null
    }

    private fun registrarFallo(mensaje: String?) {
        ultimoError = mensaje ?: "Error SMTP desconocido"
        momentoUltimoError = java.time.Instant.now()
    }

    /** Módulos con correos que todavía se están enviando. */
    fun avisosPendientes(): List<AvisoCorreoModulo> {
        val items = synchronized(pendientes) { pendientes.toList() }
        return items.flatMap { pendiente ->
            val segmento = pendiente.segmento?.trim()?.lowercase()?.ifBlank { null }
            pendiente.modulos.filter { it.isNotBlank() }.map { AvisoCorreoModulo(it, segmento) }
        }.distinct()
    }

    /** Para el endpoint de estado que consulta el panel de coordinación. */
    fun estadoActual(): EstadoSmtp {
        val hayPendientes = synchronized(pendientes) { pendientes.isNotEmpty() }
        return EstadoSmtp(
            ok = ultimoError == null && !hayPendientes,
            ultimoError = ultimoError ?: if (hayPendientes) "Hay correos pendientes de reintento" else null,
            momento = momentoUltimoError,
        )
    }

    /**
     * Envía las credenciales al correo del destinatario (plantilla HTML institucional).
     *
     * @return `true` si el mensaje se entregó por SMTP; `false` si se omitió (sin configuración, datos incompletos).
     * @throws Exception si SMTP está configurado pero falla el envío.
     */
    fun enviarCredenciales(
        correoDestino: String,
        usuario: String,
        password: String,
        renovacion: Boolean = false,
        modulo: String? = null,
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
        val usuarioEsc = escaparHtml(usuario)
        val passwordEsc = escaparHtml(password)
        try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = "SITVO - Tus credenciales de acceso",
                saludo = "Hola:",
                mensajeDestacado = if (renovacion) {
                    "Se registró un nuevo proceso de titulación. La contraseña anterior ya no sirve."
                } else {
                    "Se ha creado tu cuenta en el Sistema Integral de Titulación (SITVO)."
                },
                cuerpoHtml = """
                    <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                      <strong>Usuario:</strong> $usuarioEsc<br>
                      <strong>Contraseña:</strong> $passwordEsc
                    </p>
                    <p style="margin:16px 0 0;font-size:14px;color:#374151;line-height:1.7;">
                      ${if (renovacion) "Usa esta contraseña nueva para entrar. La anterior quedó invalidada." else "Guarda estas credenciales para iniciar sesión en"}
                      <a href="https://sitvo.net" style="color:#1f4c8f;">https://sitvo.net</a>.
                    </p>
                """.trimIndent(),
                modulos = listOfNotNull(modulo),
            )
            log.info("Correo enviado a {} con credenciales para usuario {}", correoDestino, usuario)
            return true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error al enviar correo a {}: {}", correoDestino, e.message)
            throw e
        }
    }

    /**
     * Envía enlace de un solo uso para que el usuario elija su nueva contraseña.
     * No incluye la contraseña en el correo.
     */
    fun enviarEnlaceRestablecerPassword(
        correoDestino: String,
        usuario: String,
        enlace: String,
        vigenciaMinutos: Int,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Restablecer contraseña: correo no enviado (configuración incompleta o destino vacío)")
            return false
        }
        val usuarioEsc = escaparHtml(usuario)
        val enlaceEsc = escaparHtml(enlace)
        return try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = "SITVO - Restablecer contraseña",
                saludo = "Hola:",
                mensajeDestacado = "Recibimos una solicitud para restablecer la contraseña de tu cuenta en SITVO.",
                cuerpoHtml = """
                    <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                      <strong>Usuario:</strong> $usuarioEsc
                    </p>
                    <p style="margin:0 0 16px;font-size:14px;color:#374151;line-height:1.7;">
                      Haz clic en el siguiente enlace (válido $vigenciaMinutos minutos) para elegir una nueva contraseña.
                      Tu contraseña actual no cambia hasta que completes este paso.
                    </p>
                    <p style="margin:0 0 16px;text-align:center;">
                      <a href="$enlaceEsc"
                         style="display:inline-block;padding:12px 20px;background:#1f4c8f;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px;font-weight:600;">
                        Restablecer contraseña
                      </a>
                    </p>
                    <p style="margin:0;font-size:12px;color:#6b7280;line-height:1.6;word-break:break-all;">
                      Si el botón no funciona, copia y pega este enlace en el navegador:<br>$enlaceEsc
                    </p>
                    <p style="margin:16px 0 0;font-size:14px;color:#374151;line-height:1.7;">
                      Si no solicitaste este cambio, ignora este correo: tu cuenta sigue con la misma contraseña.
                    </p>
                """.trimIndent(),
            )
            log.info("Correo de restablecimiento enviado a {} (usuario {})", correoDestino, usuario)
            true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error al enviar correo de restablecimiento a {}: {}", correoDestino, e.message)
            false
        }
    }

    /**
     * Envía el Anexo 9.3 (citatorio) PDF y, según el rol, el formato de preguntas Word correspondiente.
     *
     * @return cuántos aceptó el SMTP y cuántos quedaron reintentándose.
     */
    fun enviarAnexo93Sinodales(
        destinatarios: List<SinodalCorreoDestino>,
        nombreEgresado: String,
        numeroControl: String,
        fechaActo: String,
        pdfBytes: ByteArray,
        fileName: String,
        incluirFormatoPreguntas: Boolean = false,
        certId: String = numeroControl,
        segmento: String? = null,
        egresadoId: String? = null,
        referenciaActo: String? = null,
    ): ResultadoCorreosSinodales {
        if (mailSender == null || fromEmail.isBlank()) {
            log.warn("Spring Mail no configurado. No se enviaron correos del Anexo 9.3 a sinodales.")
            return ResultadoCorreosSinodales()
        }

        val aceptados = java.util.concurrent.atomic.AtomicInteger(0)
        val enProceso = java.util.concurrent.atomic.AtomicInteger(0)
        val correosAceptados = java.util.Collections.synchronizedList(mutableListOf<String>())
        val correosRechazados = java.util.Collections.synchronizedList(mutableListOf<String>())
        val modulosAnexo = listOf(ModuloCorreo.SEGUIMIENTO, ModuloCorreo.DEPARTAMENTO)
        val envios = destinatarios.filter { it.correo.isNotBlank() }.map { dest ->
            CompletableFuture.runAsync({
                val correo = dest.correo.trim()
                try {
                    val lineaFormatoPreguntas = if (incluirFormatoPreguntas) {
                        when (dest.rol) {
                            RolSinodalCorreo.PRESIDENTE,
                            RolSinodalCorreo.SECRETARIO,
                            RolSinodalCorreo.VOCAL ->
                                " También se adjunta el <strong>formato de preguntas</strong> correspondiente a su función en el tribunal."
                            RolSinodalCorreo.VOCAL_SUPLENTE -> ""
                        }
                    } else ""

                    val adjuntos = mutableListOf(fileName to pdfBytes)
                    if (incluirFormatoPreguntas) {
                        adjuntoFormatoPreguntas(dest.rol, certId)?.let { adjuntos += it }
                    }

                    enviarMimeInstitucional(
                        correoDestino = correo,
                        subject = "SITVO – Citatorio Acto Protocolario: $nombreEgresado",
                        saludo = "Estimado Sinodal:",
                        mensajeDestacado = "La División de Estudios Profesionales le comunica la calendarización y horario del acto protocolario.",
                        cuerpoHtml = """
                            <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                              Adjunto a este correo encontrará el <strong>Anexo 9.3 (Citatorio)</strong> con los detalles completos del acto protocolario.$lineaFormatoPreguntas
                            </p>
                        """.trimIndent(),
                        adjuntos = adjuntos,
                        modulos = modulosAnexo,
                        segmento = segmento,
                        egresadoId = egresadoId,
                        marca = if (egresadoId.isNullOrBlank()) null else MARCA_CITATORIO_SINODAL,
                        referencia = referenciaActo,
                    )
                    log.info("Anexo 9.3 enviado a sinodal {} ({}, egresado {})", correo, dest.rol, numeroControl)
                    aceptados.incrementAndGet()
                    correosAceptados.add(correo)
                } catch (ex: CorreoEnCursoException) {
                    enProceso.incrementAndGet()
                } catch (ex: Exception) {
                    registrarFallo(ex.message)
                    if (esFalloTransitorio(ex)) {
                        log.warn(
                            "Anexo 9.3 a sinodal {} no salió y se reintentará hasta enviarlo: {}",
                            correo,
                            ex.message,
                        )
                        enProceso.incrementAndGet()
                    } else {
                        correosRechazados.add(correo)
                        log.error("Error al enviar Anexo 9.3 a sinodal {}: {}", correo, ex.message)
                    }
                }
            }, correoExecutor)
        }
        envios.forEach { it.join() }
        return ResultadoCorreosSinodales(
            aceptados.get(),
            enProceso.get(),
            correosAceptados.toList(),
            correosRechazados.toList(),
        )
    }

    private fun adjuntoFormatoPreguntas(rol: RolSinodalCorreo, certId: String): Pair<String, ByteArray>? {
        val classpath = when (rol) {
            RolSinodalCorreo.PRESIDENTE -> "templates/sinodales/FORMATO_PREGUNTAS_PRESIDENTE.docx"
            RolSinodalCorreo.SECRETARIO -> "templates/sinodales/FORMATO_PREGUNTAS_SECRETARIO.docx"
            RolSinodalCorreo.VOCAL -> "templates/sinodales/FORMATO_PREGUNTAS_VOCAL.docx"
            RolSinodalCorreo.VOCAL_SUPLENTE -> return null
        }
        return try {
            val res = ClassPathResource(classpath)
            if (!res.exists()) {
                log.warn("No se encontró plantilla de preguntas para {}: {}", rol, classpath)
                return null
            }
            val qrUrl = "${baseUrl.trimEnd('/')}/#/verificar/$certId"
            val qrPng = generarQrPngBytes(qrUrl, 300)
            val doc = XWPFDocument(res.inputStream)
            val primeraTabla = doc.bodyElements.filterIsInstance<XWPFTable>().firstOrNull()
            val cursor = primeraTabla?.ctTbl?.newCursor()
            val qrPara = if (cursor != null) doc.insertNewParagraph(cursor) else doc.createParagraph()
            qrPara.alignment = ParagraphAlignment.LEFT
            qrPara.createRun().addPicture(
                ByteArrayInputStream(qrPng),
                XWPFDocument.PICTURE_TYPE_PNG, "qr.png",
                Units.toEMU(70.0), Units.toEMU(70.0),
            )
            val out = java.io.ByteArrayOutputStream()
            doc.write(out)
            val nombre = res.filename ?: "FORMATO_PREGUNTAS.docx"
            nombre to out.toByteArray()
        } catch (ex: Exception) {
            log.warn("No se pudo cargar plantilla de preguntas para {}: {}", rol, ex.message)
            null
        }
    }

    private fun generarQrPngBytes(contenido: String, size: Int = 200): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, size, size, hints)
        val bos = java.io.ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", bos)
        return bos.toByteArray()
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
        segmento: String? = null,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn(
                "Aviso acto protocolario agendado no enviado (SMTP o correo vacío), control={}",
                numeroControl,
            )
            return false
        }
        val nombreEsc = escaparHtml(nombreEgresado)
        val fechaEsc = escaparHtml(fechaHoraActoTexto)
        return try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = "SITVO – Acto protocolario agendado",
                saludo = "Hola $nombreEsc:",
                mensajeDestacado = "La División de Estudios Profesionales agendó tu acto protocolario.",
                cuerpoHtml = """
                    <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                      <strong>Fecha y horario:</strong> $fechaEsc
                    </p>
                    <p style="margin:16px 0 0;font-size:14px;color:#374151;line-height:1.7;">
                      Revisa tu seguimiento en
                      <a href="https://sitvo.net" style="color:#1f4c8f;">https://sitvo.net</a>
                      para continuar con tu trámite.
                    </p>
                """.trimIndent(),
                modulos = listOf(ModuloCorreo.SEGUIMIENTO, ModuloCorreo.DEPARTAMENTO),
                segmento = segmento,
            )
            log.info("Aviso acto protocolario agendado enviado a {} control={}", correoDestino, numeroControl)
            true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error al enviar aviso acto protocolario agendado a {}: {}", correoDestino, e.message)
            false
        }
    }

    /**
     * Avisa al sinodal la fecha del acto en el que participa.
     * @return `true` si el mensaje se entregó por SMTP.
     */
    fun enviarAvisoActoProtocolarioSinodal(
        correoDestino: String,
        nombreSinodal: String,
        nombreEgresado: String,
        numeroControl: String,
        fechaHoraActoTexto: String,
        segmento: String? = null,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn(
                "Aviso acto protocolario a sinodal no enviado (SMTP o correo vacío), control={}",
                numeroControl,
            )
            return false
        }
        val sinodalEsc = escaparHtml(nombreSinodal)
        val egresadoEsc = escaparHtml(nombreEgresado)
        val controlEsc = escaparHtml(numeroControl)
        val fechaEsc = escaparHtml(fechaHoraActoTexto)
        return try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = "SITVO – Acto protocolario agendado",
                saludo = "Hola $sinodalEsc:",
                mensajeDestacado = "La División de Estudios Profesionales agendó un acto protocolario en el que participas como sinodal.",
                cuerpoHtml = """
                    <p style="margin:0 0 8px;font-size:14px;color:#374151;line-height:1.7;">
                      <strong>Egresado:</strong> $egresadoEsc<br>
                      <strong>Número de control:</strong> $controlEsc<br>
                      <strong>Fecha y horario:</strong> $fechaEsc
                    </p>
                """.trimIndent(),
                modulos = listOf(ModuloCorreo.SEGUIMIENTO, ModuloCorreo.DEPARTAMENTO),
                segmento = segmento,
            )
            log.info(
                "Aviso acto protocolario enviado a sinodal {} control={}",
                correoDestino,
                numeroControl,
            )
            true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error al enviar aviso de acto a sinodal {}: {}", correoDestino, e.message)
            false
        }
    }

    /**
     * Plantilla HTML compartida (cabecera ITVO + mensaje destacado + pie).
     * Usada en credenciales, recuperación, Anexo 9.3 y aviso de acto al egresado.
     * Si el SMTP tarda o se corta, el correo queda pendiente y [reintentarCorreosPendientes] lo manda después.
     */
    private fun enviarMimeInstitucional(
        correoDestino: String,
        subject: String,
        saludo: String,
        mensajeDestacado: String,
        cuerpoHtml: String,
        adjuntos: List<Pair<String, ByteArray>> = emptyList(),
        modulos: List<String> = emptyList(),
        segmento: String? = null,
        egresadoId: String? = null,
        marca: String? = null,
        referencia: String? = null,
    ) {
        val pendiente = CorreoPendiente(
            correoDestino = correoDestino.trim(),
            subject = subject,
            saludo = saludo,
            mensajeDestacado = mensajeDestacado,
            cuerpoHtml = cuerpoHtml,
            adjuntos = adjuntos,
            modulos = modulos,
            segmento = segmento,
            egresadoId = egresadoId?.trim()?.ifBlank { null },
            marca = marca,
            referencia = referencia,
        )
        // Queda guardado antes del SMTP. Si el proceso se cae a mitad del envío, al arrancar se vuelve a intentar.
        pendiente.proximoIntentoEpochMs = System.currentTimeMillis() + 180_000
        synchronized(pendientes) {
            val repetido = pendiente.marca == MARCA_CITATORIO_SINODAL &&
                pendiente.egresadoId != null &&
                pendientes.any {
                    it.marca == MARCA_CITATORIO_SINODAL &&
                        it.egresadoId == pendiente.egresadoId &&
                        it.correoDestino.equals(pendiente.correoDestino, ignoreCase = true)
                }
            if (repetido) throw CorreoEnCursoException()
            pendientes.add(pendiente)
        }
        guardarPendiente(pendiente)
        try {
            enviarPendiente(pendiente)
            synchronized(pendientes) { pendientes.remove(pendiente) }
            borrarPendiente(pendiente)
            registrarExito()
            publicarMarca(pendiente, true)
        } catch (e: Exception) {
            if (esFalloTransitorio(e)) {
                pendiente.intentos = 1
                pendiente.proximoIntentoEpochMs = System.currentTimeMillis() + esperaReintentoMs(1)
                guardarPendiente(pendiente)
                log.warn(
                    "El correo a {} no salió ({}). Queda pendiente y se reintentará hasta enviarlo.",
                    pendiente.correoDestino,
                    e.message,
                )
            } else {
                publicarMarca(pendiente, false)
                synchronized(pendientes) { pendientes.remove(pendiente) }
                borrarPendiente(pendiente)
            }
            throw e
        }
    }

    /**
     * Cada pocos segundos revisa los correos que no salieron. Un fallo de red, de tiempo o de cupo se vuelve a intentar.
     * Una dirección inexistente o un rechazo de la cuenta que envía no se reintenta.
     */
    @Scheduled(fixedDelayString = "\${sit.mail.reintento.ms:4000}")
    fun reintentarCorreosPendientes() {
        val ahora = System.currentTimeMillis()
        val lote = synchronized(pendientes) {
            pendientes.filter { it.proximoIntentoEpochMs <= ahora }.toList()
        }
        if (lote.isEmpty()) return
        val huboRechazoDefinitivo = java.util.concurrent.atomic.AtomicBoolean(false)
        val envios = lote.map { item ->
            CompletableFuture.runAsync({
                try {
                    enviarPendiente(item)
                    synchronized(pendientes) { pendientes.remove(item) }
                    borrarPendiente(item)
                    publicarMarca(item, true)
                    log.info("Correo reenviado a {} (asunto={})", item.correoDestino, item.subject)
                } catch (e: Exception) {
                    if (!esFalloTransitorio(e)) {
                        publicarMarca(item, false)
                        synchronized(pendientes) { pendientes.remove(item) }
                        borrarPendiente(item)
                        registrarFallo(e.message)
                        huboRechazoDefinitivo.set(true)
                        log.error(
                            "Correo a {} no se puede enviar y no se reintenta: {}",
                            item.correoDestino,
                            e.message,
                        )
                    } else {
                        item.intentos += 1
                        val espera = esperaReintentoMs(item.intentos)
                        item.proximoIntentoEpochMs = System.currentTimeMillis() + espera
                        guardarPendiente(item)
                        registrarFallo(e.message)
                        log.warn(
                            "Reintento {} falló para {}: {}. Siguiente intento en {} s",
                            item.intentos,
                            item.correoDestino,
                            e.message,
                            espera / 1000,
                        )
                    }
                }
            }, correoExecutor)
        }
        envios.forEach { it.join() }
        if (!huboRechazoDefinitivo.get() && synchronized(pendientes) { pendientes.isEmpty() }) registrarExito()
    }

    private fun enviarPendiente(pendiente: CorreoPendiente) {
        val sender = mailSender ?: error("mailSender no configurado")
        val logoResource = try {
            ClassPathResource("templates/html/logo-itvo.png").takeIf { it.exists() }
        } catch (_: Exception) {
            null
        }
        val logoTag = if (logoResource != null) {
            """<img src="cid:logo-itvo" alt="ITVO" width="64" height="64" style="display:block;border-radius:8px;border:2px solid rgba(255,255,255,0.3);">"""
        } else {
            ""
        }
        val firmaNombre = escaparHtml(configInstitucional.obtenerJefeDivisionNombre())
        val firmaCargo = escaparHtml(
            when (configInstitucional.obtenerJefeDivisionTitulo().uppercase()) {
                "JEFA" -> "Jefa de la División de Estudios Profesionales"
                else -> "Jefe de la División de Estudios Profesionales"
            },
        )

        envioCupo.acquire()
        try {
            val mime = sender.createMimeMessage()
            val helper = MimeMessageHelper(mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8")
            helper.setFrom(fromEmail)
            helper.setTo(pendiente.correoDestino)
            helper.setSubject(pendiente.subject)
            helper.setText(
            """
            <!DOCTYPE html>
            <html lang="es">
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%" cellpadding="0" cellspacing="0" style="background:#f4f6f9;padding:32px 0;">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:10px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);max-width:600px;">

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

                    <tr>
                      <td style="padding:36px 36px 32px;">
                        <p style="margin:0 0 22px;font-size:15px;color:#1f2937;line-height:1.6;">
                          ${pendiente.saludo}
                        </p>
                        <p style="margin:0 0 28px;font-size:15px;font-weight:600;color:#1f4c8f;line-height:1.7;border-left:4px solid #1f4c8f;padding-left:14px;">
                          ${pendiente.mensajeDestacado}
                        </p>
                        ${pendiente.cuerpoHtml}
                        <p style="margin:32px 0 0;font-size:14px;color:#1f2937;line-height:1.7;">
                          Atentamente,<br><br>
                          <strong>$firmaNombre</strong><br>
                          $firmaCargo<br>
                          Instituto Tecnológico del Valle de Oaxaca
                        </p>
                      </td>
                    </tr>

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
            """.trimIndent(),
            true,
        )
        if (logoResource != null) {
            helper.addInline("logo-itvo", logoResource)
        }
            for ((nombre, bytes) in pendiente.adjuntos) {
                helper.addAttachment(nombre, ByteArrayResource(bytes))
            }
            sender.send(mime)
        } finally {
            envioCupo.release()
        }
    }

    /** Corte, demora o cupo: se vuelve a intentar. Cuenta rechazada o dirección inexistente: no. */
    internal fun esFalloTransitorio(error: Throwable): Boolean {
        val partes = ArrayList<String>()
        val tipos = ArrayList<String>()
        var actual: Throwable? = error
        var guard = 0
        while (actual != null && guard < 8) {
            partes += actual.message.orEmpty()
            tipos += actual.javaClass.simpleName
            actual = actual.cause
            guard++
        }
        val texto = partes.joinToString(" ").lowercase()
        val tipo = tipos.joinToString(" ")
        if (tipo.contains("AuthenticationFailed") || tipo.contains("MailAuthentication")) return false
        val transitorioExplicito = listOf(
            "quota",
            "try again",
            "temporar",
            "timeout",
            "timed out",
            "connection reset",
            "connection refused",
            "421",
            "450",
            "451",
            "452",
        )
        if (transitorioExplicito.any { texto.contains(it) }) return true
        val permanentes = listOf(
            "535",
            "username and password not accepted",
            "authentication failed",
            "invalid credentials",
            "550",
            "551",
            "553",
            "554",
            "mailbox unavailable",
            "user unknown",
            "recipient address rejected",
            "invalid addresses",
            "invalid address",
        )
        if (permanentes.any { texto.contains(it) }) return false
        return true
    }

    private fun esperaReintentoMs(intento: Int): Long {
        if (esperaInicialMs <= 0L) return 0L
        val pasos = intento.coerceIn(1, 3)
        var espera = esperaInicialMs
        repeat(pasos - 1) { espera = (espera * 2).coerceAtMost(15_000L) }
        return espera.coerceAtMost(15_000L)
    }

    private fun guardarPendiente(pendiente: CorreoPendiente) {
        val repo = correoPendienteRepository ?: return
        try {
            repo.save(
                CorreoPendienteGuardado(
                    id = pendiente.id,
                    correoDestino = pendiente.correoDestino,
                    subject = pendiente.subject,
                    saludo = pendiente.saludo,
                    mensajeDestacado = pendiente.mensajeDestacado,
                    cuerpoHtml = pendiente.cuerpoHtml,
                    adjuntos = pendiente.adjuntos.map { (nombre, bytes) ->
                        AdjuntoCorreoGuardado(nombre = nombre, contenido = bytes)
                    },
                    modulos = pendiente.modulos,
                    segmento = pendiente.segmento,
                    intentos = pendiente.intentos,
                    proximoIntentoEpochMs = pendiente.proximoIntentoEpochMs,
                    egresadoId = pendiente.egresadoId,
                    marca = pendiente.marca,
                    referencia = pendiente.referencia,
                ),
            )
        } catch (ex: Exception) {
            log.warn("No se pudo guardar el correo pendiente a {}: {}", pendiente.correoDestino, ex.message)
        }
    }

    private fun publicarMarca(pendiente: CorreoPendiente, aceptado: Boolean) {
        val id = pendiente.egresadoId ?: return
        val marca = pendiente.marca ?: return
        val publisher = eventos ?: return
        try {
            publisher.publishEvent(
                CorreoMarcadoEvent(
                    egresadoId = id,
                    correo = pendiente.correoDestino,
                    marca = marca,
                    aceptado = aceptado,
                    referencia = pendiente.referencia,
                ),
            )
        } catch (ex: Exception) {
            log.warn("No se pudo registrar el resultado del correo a {}: {}", pendiente.correoDestino, ex.message)
        }
    }

    private fun borrarPendiente(pendiente: CorreoPendiente) {
        val repo = correoPendienteRepository ?: return
        try {
            repo.deleteById(pendiente.id)
        } catch (ex: Exception) {
            log.warn("No se pudo quitar el correo pendiente {}: {}", pendiente.id, ex.message)
        }
    }

    private class CorreoPendiente(
        val id: String = UUID.randomUUID().toString(),
        val correoDestino: String,
        val subject: String,
        val saludo: String,
        val mensajeDestacado: String,
        val cuerpoHtml: String,
        val adjuntos: List<Pair<String, ByteArray>>,
        val modulos: List<String> = emptyList(),
        val segmento: String? = null,
        var intentos: Int = 0,
        var proximoIntentoEpochMs: Long = 0L,
        val egresadoId: String? = null,
        val marca: String? = null,
        val referencia: String? = null,
    )

    private fun escaparHtml(texto: String): String =
        texto
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

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
        segmento: String? = null,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Aviso plazo residencia no enviado (SMTP o correo vacío), control={}", numeroControl)
            return false
        }
        val (subject, mensajeDestacado, cuerpoHtml) = plantillaAvisoPlazoResidenciaHtml(
            nombreEgresado = nombreEgresado,
            tipoAviso = tipoAviso,
            diasRestantes = diasRestantes,
            fechaLimiteTexto = fechaLimiteTexto,
            pasoNumero = pasoNumero,
            pasoTitulo = pasoTitulo,
        )
        return try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = subject,
                saludo = "Hola ${escaparHtml(nombreEgresado)}:",
                mensajeDestacado = mensajeDestacado,
                cuerpoHtml = cuerpoHtml,
                modulos = listOf(ModuloCorreo.SEGUIMIENTO, ModuloCorreo.DEPARTAMENTO),
                segmento = segmento,
            )
            log.info("Aviso plazo residencia ({}) enviado a {} control={}", tipoAviso, correoDestino, numeroControl)
            true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error aviso plazo residencia a {}: {}", correoDestino, e.message)
            false
        }
    }

    /** subject, mensajeDestacado, cuerpoHtml */
    private fun plantillaAvisoPlazoResidenciaHtml(
        nombreEgresado: String,
        tipoAviso: String,
        diasRestantes: Int,
        fechaLimiteTexto: String,
        pasoNumero: Int,
        pasoTitulo: String,
    ): Triple<String, String, String> {
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
        fun p(texto: String) =
            """<p style="margin:0 0 14px;font-size:14px;color:#374151;line-height:1.7;">${escaparHtml(texto)}</p>"""

        return when (tipoAviso) {
            "aviso_2_meses" -> Triple(
                "SITVO – Aviso de plazo (2 meses)",
                "Han transcurrido 2 meses desde la fecha de registro de tu Anexo XXXI.",
                p(tiempoLinea) + p(pasoLinea) +
                    p("Continúa tu trámite en el SITVO y ante la División de Estudios Profesionales según corresponda."),
            )
            "aviso_4_meses" -> Triple(
                "SITVO – Aviso de plazo (4 meses)",
                "Han transcurrido 4 meses desde la fecha de registro de tu Anexo XXXI.",
                p(tiempoLinea) + p(pasoLinea) +
                    p("Te recomendamos avanzar con los pasos pendientes antes del vencimiento del plazo total de 6 meses."),
            )
            "aviso_3_dias" -> Triple(
                "SITVO – Último aviso: plazo por vencer (3 días)",
                "Tu plazo de 6 meses para concluir el proceso de Residencia Profesional (desde el Anexo XXXI) está por terminar.",
                p(tiempoLinea) + p(pasoLinea) + p("Si aún tienes trámites pendientes, atiéndelos de inmediato."),
            )
            "final_concluido" -> Triple(
                "SITVO – Tu proceso de titulación concluyó",
                "Tu proceso de titulación por Residencia Profesional quedó concluido en el sistema.",
                p(pasoLinea) + p("Ya no recibirás avisos automáticos de plazo por este trámite."),
            )
            else -> Triple(
                "SITVO – Plazo de titulación vencido",
                "Tu proceso de titulación por Residencia Profesional se marcó como vencido: se superó el plazo de 6 meses desde la fecha de registro del Anexo XXXI.",
                p("La fecha límite era $fechaLimiteTexto.") + p(pasoLinea) +
                    p("Comunícate con la División de Estudios Profesionales para conocer las opciones disponibles."),
            )
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
        segmento: String? = null,
    ): Boolean {
        if (correoDestino.isBlank() || mailSender == null || fromEmail.isBlank()) {
            log.warn("Aviso plazo no residencia no enviado (SMTP o correo vacío), tipo={}", tipoAviso)
            return false
        }
        val (subject, mensajeDestacado, cuerpoHtml) = plantillaAvisoPlazoNoResidenciaHtml(
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
        return try {
            enviarMimeInstitucional(
                correoDestino = correoDestino,
                subject = subject,
                saludo = "Hola ${escaparHtml(nombreEgresado)}:",
                mensajeDestacado = mensajeDestacado,
                cuerpoHtml = cuerpoHtml,
                modulos = listOf(ModuloCorreo.SEGUIMIENTO, ModuloCorreo.DEPARTAMENTO),
                segmento = segmento,
            )
            log.info("Aviso plazo no residencia ({}) enviado a {}", tipoAviso, correoDestino)
            true
        } catch (e: Exception) {
            registrarFallo(e.message)
            log.error("Error aviso plazo no residencia a {}: {}", correoDestino, e.message)
            false
        }
    }

    /** subject, mensajeDestacado, cuerpoHtml */
    private fun plantillaAvisoPlazoNoResidenciaHtml(
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
    ): Triple<String, String, String> {
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
        fun p(texto: String) =
            """<p style="margin:0 0 14px;font-size:14px;color:#374151;line-height:1.7;">${escaparHtml(texto)}</p>"""
        val bloqueTiempo = if (tiempoLinea.isNotBlank()) p(tiempoLinea) else ""

        return when (tipoAviso) {
            "aviso_inicio_tramite" -> Triple(
                "SITVO – Inicio del plazo de trámite (6 meses)",
                "Tu $etiquetaModalidad fue liberada en el departamento académico. Comenzó el plazo de 6 meses calendario para concluir el trámite de titulación posterior a la liberación.",
                bloqueTiempo + p(pasoLinea) +
                    p("Continúa tu trámite en el SITVO y con la División de Estudios Profesionales según corresponda."),
            )
            "aviso_dev_3_meses", "aviso_dev_6_meses", "aviso_dev_9_meses",
            "aviso_dev_12_meses", "aviso_dev_15_meses" -> {
                val m = mesesTranscurridos ?: 0
                Triple(
                    "SITVO – Aviso de plazo de desarrollo ($m meses)",
                    "Han transcurrido $m meses $origenDesarrollo.",
                    bloqueTiempo + p(pasoLinea) +
                        p("Continúa el desarrollo de tu $etiquetaModalidad y los trámites pendientes antes del vencimiento del plazo total de $mesesPlazoTotal meses."),
                )
            }
            "aviso_dev_3_dias" -> Triple(
                "SITVO – Último aviso: plazo de desarrollo por vencer (3 días)",
                "Tu plazo de $mesesPlazoTotal meses para el desarrollo de tu $etiquetaModalidad ($origenDesarrollo) está por terminar.",
                bloqueTiempo + p(pasoLinea) +
                    p("Si aún debes liberar producto o completar trámites, atiéndelos de inmediato."),
            )
            "aviso_tram_2_meses" -> Triple(
                "SITVO – Aviso de plazo de trámite (2 meses)",
                "Han transcurrido 2 meses $origenTramite.",
                bloqueTiempo + p(pasoLinea) +
                    p("Continúa con los pasos pendientes antes del vencimiento del plazo total de 6 meses."),
            )
            "aviso_tram_4_meses" -> Triple(
                "SITVO – Aviso de plazo de trámite (4 meses)",
                "Han transcurrido 4 meses $origenTramite.",
                bloqueTiempo + p(pasoLinea) +
                    p("Te recomendamos avanzar con los pasos pendientes antes del vencimiento del plazo de 6 meses."),
            )
            "aviso_tram_3_dias" -> Triple(
                "SITVO – Último aviso: plazo de trámite por vencer (3 días)",
                "Tu plazo de 6 meses para concluir el trámite tras la liberación de tu $etiquetaModalidad está por terminar.",
                bloqueTiempo + p(pasoLinea) + p("Si aún tienes trámites pendientes, atiéndelos de inmediato."),
            )
            "final_concluido" -> Triple(
                "SITVO – Tu proceso de titulación concluyó",
                "Tu proceso de titulación por $etiquetaModalidad quedó concluido en el sistema.",
                p(pasoLinea) + p("Ya no recibirás avisos automáticos de plazo por este trámite."),
            )
            else -> Triple(
                "SITVO – Plazo de titulación vencido",
                "Tu proceso de titulación por $etiquetaModalidad se marcó como vencido: se superó el plazo vigente del trámite.",
                p(pasoLinea) +
                    p("Comunícate con la División de Estudios Profesionales para conocer las opciones disponibles."),
            )
        }
    }
}
