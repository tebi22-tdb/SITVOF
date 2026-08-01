package com.sit_titulacion.sit.service

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service
class HtmlAnexoPdfService {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generarDesdeClasspath(
        templateClasspath: String,
        valores: Map<String, String>,
        imagenesOverride: Map<String, String> = emptyMap(),
        valoresHtml: Map<String, String> = emptyMap(),
    ): ByteArray? {
        return try {
            val raw =
                ClassPathResource(templateClasspath).inputStream.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8)
                }
            val conHtmlCrudo = aplicarPlaceholdersHtml(raw, valoresHtml)
            val html = embedirImagenesLocales(aplicarPlaceholders(conHtmlCrudo, valores), templateClasspath, imagenesOverride)
            val baseUri = baseUriDirectorioPlantilla(templateClasspath)
            ByteArrayOutputStream().use { os ->
                val builder = PdfRendererBuilder()
                if (!templateClasspath.contains("oficio-sinodales")) {
                    builder.useFastMode()
                }
                registrarFuentes(builder)
                builder.withHtmlContent(html, baseUri)
                builder.toStream(os)
                val renderer = builder.buildPdfRenderer()
                renderer.use {
                    it.layout()
                    val viewerPreferences = PDViewerPreferences(COSDictionary())
                    viewerPreferences.setPrintScaling(PDViewerPreferences.PRINT_SCALING.None)
                    it.pdfDocument.documentCatalog.viewerPreferences = viewerPreferences
                    it.createPDF()
                }
                os.toByteArray()
            }
        } catch (ex: Exception) {
            log.error("HtmlAnexoPdfService {}: {}", templateClasspath, ex.message, ex)
            null
        }
    }

    /**
     * Sustituye src="nombre.png" por un data URI base64. Si la clave (nombre de archivo)
     * está en [imagenesOverride], usa esa imagen subida desde el admin; si no, la carga
     * del classpath junto a la plantilla. Evita problemas de resolución de rutas en fat JARs.
     * Omite valores que ya son data URIs (empiezan con "data:").
     */
    private fun embedirImagenesLocales(
        html: String,
        templateClasspath: String,
        imagenesOverride: Map<String, String>,
    ): String {
        val directorio = templateClasspath.substringBeforeLast("/")
        val imagenes = Regex("""src="([^"]+\.(png|jpg|jpeg|gif|svg))"""")
        return imagenes.replace(html) { match ->
            val nombreArchivo = match.groupValues[1]
            if (nombreArchivo.startsWith("data:")) return@replace match.value
            imagenesOverride[nombreArchivo]?.let { return@replace """src="$it"""" }
            val classpath = "$directorio/$nombreArchivo"
            try {
                val bytes = ClassPathResource(classpath).inputStream.use { it.readAllBytes() }
                val mime = when (nombreArchivo.substringAfterLast(".").lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif"         -> "image/gif"
                    "svg"         -> "image/svg+xml"
                    else          -> "image/png"
                }
                val b64 = Base64.getEncoder().encodeToString(bytes)
                """src="data:$mime;base64,$b64""""
            } catch (_: Exception) {
                match.value
            }
        }
    }

    private fun baseUriDirectorioPlantilla(templateClasspath: String): String {
        val url = HtmlAnexoPdfService::class.java.classLoader.getResource(templateClasspath) ?: return ""
        return try {
            url.toURI().resolve(".").toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Sustituye placeholders con HTML ya construido (ej. las insignias del pie), SIN escapar.
     * Se aplica antes que [aplicarPlaceholders] para que el catch-all de esa función no borre
     * el placeholder si por algún motivo no viene en [valoresHtml]. Solo usar con HTML que el
     * propio backend arma a partir de datos controlados (nunca texto libre de usuarios).
     */
    private fun aplicarPlaceholdersHtml(html: String, valoresHtml: Map<String, String>): String {
        var out = html
        valoresHtml.forEach { (k, v) -> out = out.replace("{{$k}}", v, ignoreCase = false) }
        return out
    }

    private fun aplicarPlaceholders(html: String, valores: Map<String, String>): String {
        var out = html
        valores.forEach { (k, v) ->
            val safe = if (v.startsWith("data:")) {
                v
            } else {
                escapeHtml(normalizarTextoUtf8(v.ifBlank { "—" }))
            }
            out = out.replace("{{$k}}", safe, ignoreCase = false)
        }
        return Regex("""\{\{([A-Z0-9_]+)}}""").replace(out) { "—" }
    }

    /**
     * Corrige texto UTF-8 leído como ISO-8859-1 (p. ej. TecnolÃ³gico → Tecnológico).
     */
    internal fun normalizarTextoUtf8(s: String): String {
        if (!s.contains('Ã') && !s.contains('Â')) return s
        return runCatching {
            val fixed = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (fixed.contains('\uFFFD')) s else fixed
        }.getOrDefault(s)
    }

    /**
     * Arial no se puede redistribuir (fuente propietaria de Microsoft), así que
     * incrustamos Liberation Sans —métricamente compatible, SIL Open Font License—
     * registrada bajo el nombre "Arial". Se carga del classpath (funciona igual
     * en Windows, Linux o dentro de un JAR empaquetado, a diferencia de la ruta
     * fija a C:\Windows\Fonts que antes solo funcionaba en Windows).
     */
    private fun registrarFuentes(builder: PdfRendererBuilder) {
        builder.useFont(
            { ClassPathResource("fonts/LiberationSans-Regular.ttf").inputStream },
            "Arial", 400, BaseRendererBuilder.FontStyle.NORMAL, true,
        )
        builder.useFont(
            { ClassPathResource("fonts/LiberationSans-Bold.ttf").inputStream },
            "Arial", 700, BaseRendererBuilder.FontStyle.NORMAL, true,
        )
        // Mismo caso que Arial: Times New Roman tampoco se puede redistribuir,
        // usamos Liberation Serif (misma familia libre) registrada con ese nombre.
        builder.useFont(
            { ClassPathResource("fonts/LiberationSerif-Regular.ttf").inputStream },
            "Times New Roman", 400, BaseRendererBuilder.FontStyle.NORMAL, true,
        )
        builder.useFont(
            { ClassPathResource("fonts/LiberationSerif-Bold.ttf").inputStream },
            "Times New Roman", 700, BaseRendererBuilder.FontStyle.NORMAL, true,
        )
    }

    private fun escapeHtml(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                in '\u0000'..'\u007F' -> sb.append(ch)
                else -> sb.append("&#").append(ch.code).append(';')
            }
        }
        return sb.toString()
    }
}
