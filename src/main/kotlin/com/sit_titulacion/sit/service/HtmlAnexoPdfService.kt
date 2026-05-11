package com.sit_titulacion.sit.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * PDF a partir de plantillas XHTML en classpath (sin LibreOffice).
 * Usado para anexos 9.1 y 9.3; el 9.2 sigue con DOCX + conversión externa si aplica.
 */
@Service
class HtmlAnexoPdfService {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generarDesdeClasspath(templateClasspath: String, valores: Map<String, String>): ByteArray? {
        return try {
            val raw =
                ClassPathResource(templateClasspath).inputStream.use {
                    String(it.readAllBytes(), StandardCharsets.UTF_8)
                }
            val html = aplicarPlaceholders(raw, valores)
            val baseUri = baseUriDirectorioPlantilla(templateClasspath)
            ByteArrayOutputStream().use { os ->
                val builder = PdfRendererBuilder()
                builder.useFastMode()
                builder.withHtmlContent(html, baseUri)
                builder.toStream(os)
                builder.run()
                os.toByteArray()
            }
        } catch (ex: Exception) {
            log.error("HtmlAnexoPdfService {}: {}", templateClasspath, ex.message, ex)
            null
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
            val safe = escapeHtml(v.ifBlank { "—" })
            out = out.replace("{{$k}}", safe, ignoreCase = false)
        }
        return Regex("""\{\{([A-Z0-9_]+)}}""").replace(out) { "—" }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
