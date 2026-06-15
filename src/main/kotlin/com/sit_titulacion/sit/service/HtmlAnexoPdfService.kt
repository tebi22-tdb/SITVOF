package com.sit_titulacion.sit.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
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
            // No escapar valores que ya son data URIs — no contienen caracteres HTML especiales
            val safe = if (v.startsWith("data:")) v else escapeHtml(v.ifBlank { "—" })
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
