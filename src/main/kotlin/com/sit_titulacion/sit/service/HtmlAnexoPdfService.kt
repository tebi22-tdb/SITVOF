package com.sit_titulacion.sit.service

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service
class HtmlAnexoPdfService {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generarDesdeClasspath(templateClasspath: String, valores: Map<String, String>): ByteArray? {
        return try {
            val raw =
                ClassPathResource(templateClasspath).inputStream.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8)
                }
            val html = embedirImagenesLocales(aplicarPlaceholders(raw, valores), templateClasspath)
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
     * Sustituye src="nombre.png" por un data URI base64 cargado desde el classpath
     * junto a la plantilla. Evita problemas de resolución de rutas en fat JARs.
     * Omite valores que ya son data URIs (empiezan con "data:").
     */
    private fun embedirImagenesLocales(html: String, templateClasspath: String): String {
        val directorio = templateClasspath.substringBeforeLast("/")
        val imagenes = Regex("""src="([^"]+\.(png|jpg|jpeg|gif|svg))"""")
        return imagenes.replace(html) { match ->
            val nombreArchivo = match.groupValues[1]
            if (nombreArchivo.startsWith("data:")) return@replace match.value
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

    private fun registrarFuentes(builder: PdfRendererBuilder) {
        val windir = System.getenv("WINDIR") ?: "C:\\Windows"
        val arial = File(windir, "Fonts\\arial.ttf")
        val arialBold = File(windir, "Fonts\\arialbd.ttf")
        if (arial.isFile) {
            builder.useFont(arial, "Arial", 400, BaseRendererBuilder.FontStyle.NORMAL, true)
        }
        if (arialBold.isFile) {
            builder.useFont(arialBold, "Arial", 700, BaseRendererBuilder.FontStyle.NORMAL, true)
        }
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
