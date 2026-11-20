package com.sit_titulacion.sit.service

import org.springframework.core.io.ClassPathResource
import java.util.Base64

/**
 * Sustituye el texto del cuerpo de un anexo (atributo data-texto) sin tocar
 * encabezado, pie, código QR ni la estructura de la hoja.
 */
internal object TextosPlantilla {
    private val token = Regex("""\{\{([A-Z0-9_]+)}}""")
    private val dataPh = Regex(
        """<span\b[^>]*\bdata-ph="([A-Z0-9_]+)"[^>]*>[\s\S]*?</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val etiqueta = Regex("""</?([a-zA-Z0-9]+)(\s[^>]*)?>""")
    private val permitidas = setOf("span", "b", "strong", "u", "em", "i", "br")
    private val fuenteEstrechaCss: String by lazy {
        val normal = ClassPathResource("fonts/RobotoCondensed-Regular.ttf").inputStream.use { it.readAllBytes() }
        val cursiva = ClassPathResource("fonts/RobotoCondensed-Italic.ttf").inputStream.use { it.readAllBytes() }
        val uriNormal = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(normal)
        val uriCursiva = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(cursiva)
        """@font-face { font-family: Estrecha; src: url("$uriNormal"); font-weight: 400; font-style: normal; }
           @font-face { font-family: Estrecha; src: url("$uriCursiva"); font-weight: 400; font-style: italic; }"""
    }

    fun aplicar(html: String, textos: Map<String, String>): String {
        if (textos.isEmpty()) return html
        val salida = StringBuilder()
        var i = 0
        while (i < html.length) {
            val marca = html.indexOf("data-texto=\"", i)
            if (marca < 0) {
                salida.append(html, i, html.length)
                break
            }
            val inicio = html.lastIndexOf('<', marca)
            if (inicio < i) {
                salida.append(html, i, marca + 1)
                i = marca + 1
                continue
            }
            val nombre = nombreEtiqueta(html, inicio)
            val finApertura = html.indexOf('>', marca)
            if (nombre.isEmpty() || finApertura < 0) {
                salida.append(html, i, marca + 1)
                i = marca + 1
                continue
            }
            val claveInicio = marca + "data-texto=\"".length
            val claveFin = html.indexOf('"', claveInicio)
            val clave = if (claveFin > claveInicio) html.substring(claveInicio, claveFin) else ""
            val cierre = indiceCierre(html, finApertura + 1, nombre)
            val finCierre = (cierre + nombre.length + 3).coerceAtMost(html.length)
            salida.append(html, i, finApertura + 1)
            val nuevo = textos[clave]
            if (nuevo != null) salida.append(xmlSeguro(nuevo)) else salida.append(html, finApertura + 1, cierre)
            salida.append(html, cierre, finCierre)
            i = finCierre
        }
        return salida.toString()
    }

    fun extraer(html: String): Map<String, String> {
        val salida = linkedMapOf<String, String>()
        var i = 0
        while (i < html.length) {
            val marca = html.indexOf("data-texto=\"", i)
            if (marca < 0) break
            val inicio = html.lastIndexOf('<', marca)
            val nombre = if (inicio >= 0) nombreEtiqueta(html, inicio) else ""
            val finApertura = html.indexOf('>', marca)
            val claveInicio = marca + "data-texto=\"".length
            val claveFin = html.indexOf('"', claveInicio)
            if (nombre.isEmpty() || finApertura < 0 || claveFin < 0) {
                i = marca + 1
                continue
            }
            val clave = html.substring(claveInicio, claveFin)
            val cierre = indiceCierre(html, finApertura + 1, nombre)
            salida[clave] = html.substring(finApertura + 1, cierre).trim()
            i = cierre + nombre.length + 3
        }
        return salida
    }

    fun tokens(html: String): Set<String> = token.findAll(html).map { it.groupValues[1] }.toSet()

    /** Deja solo texto y las marcas que la hoja ya usa. Los datos {{CAMPO}} no se pueden borrar. */
    fun sanitizar(html: String): String {
        var s = xmlSeguro(dataPh.replace(html) { "{{${it.groupValues[1]}}}" })
        s = Regex("""<(script|style)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "<br/>")
        s = etiqueta.replace(s) { marca ->
            val nombre = marca.groupValues[1].lowercase()
            if (nombre !in permitidas) return@replace ""
            if (nombre == "br") return@replace "<br/>"
            if (marca.value.startsWith("</")) return@replace "</$nombre>"
            if (nombre != "span") return@replace "<$nombre>"
            val attrs = marca.groupValues[2]
            val clase = Regex("""class="([a-zA-Z0-9 _-]+)"""").find(attrs)?.groupValues?.get(1)?.trim()
            val estilo = Regex("""style="([^"]+)"""").find(attrs)?.groupValues?.get(1)?.trim()
            val estiloOk = estilo?.let { estiloLimpio(it) }
            buildString {
                append("<span")
                if (!clase.isNullOrBlank()) append(" class=\"").append(clase).append('"')
                if (estiloOk != null) append(" style=\"").append(estiloOk).append('"')
                append(">")
            }
        }
        return s.trim()
    }

    fun prepararEditor(html: String): String {
        val conEdicion = Regex("""<([a-zA-Z0-9]+)([^>]*\bdata-texto="[^"]+"[^>]*)>""")
            .replace(html) { "<${it.groupValues[1]}${it.groupValues[2]} contenteditable=\"true\">" }
        val conTokens = token.replace(conEdicion) { m ->
            val nombre = m.groupValues[1]
            """<span class="ph-fijo" contenteditable="false" data-ph="$nombre">{{$nombre}}</span>"""
        }
        val estilo = """
            <style type="text/css">
              $fuenteEstrechaCss
              [data-texto][contenteditable="true"] { outline: 1px dashed transparent; }
              [data-texto][contenteditable="true"]:hover { outline: 1px dashed #1f4c8f; cursor: text; }
              [data-texto][contenteditable="true"]:focus { outline: 2px solid #1f4c8f; background: rgba(255,251,230,.7); }
              .ph-fijo { background: #eef2f7; border-radius: 2px; }
              .hdr-fixed, .ftr-fixed, .qr-auth-wrap, .qr-anchor, .firma-qr-right { pointer-events: none; }
            </style>
        """.trimIndent()
        return conTokens.replace("</head>", "$estilo</head>")
    }

    /** Solo deja tipo, tamaño, cursiva, subrayado, color, alineación y los estilos que la hoja ya trae. */
    private fun estiloLimpio(crudo: String): String? {
        val partes = mutableListOf<String>()
        for (trozo in crudo.split(';')) {
            val texto = trozo.trim()
            if (texto.isEmpty()) continue
            val corte = texto.indexOf(':')
            if (corte <= 0) continue
            val prop = texto.substring(0, corte).trim().lowercase()
            val valor = texto.substring(corte + 1).trim()
            val limpio = when (prop) {
                "font-family" -> familiaPermitida(valor)?.let { "font-family: $it" }
                "font-size" -> valor.lowercase().takeIf { it in setOf("10pt", "12pt", "14pt") }?.let { "font-size: $it" }
                "font-style" -> if (valor.equals("italic", true)) "font-style: italic" else null
                "color" -> colorPermitido(valor)?.let { "color: $it" }
                "text-align" -> valor.lowercase().takeIf { it in setOf("left", "center", "right", "justify") }?.let { "text-align: $it" }
                "display" -> if (valor.equals("block", true)) "display: block" else null
                "text-decoration", "text-decoration-line" ->
                    if (valor.lowercase().startsWith("underline") && !valor.contains("url", true)) "text-decoration: underline" else null
                "text-underline-offset" ->
                    if (valor.replace(" ", "").equals("2px", true)) "text-underline-offset: 2px" else null
                "text-transform" ->
                    if (valor.equals("uppercase", true)) "text-transform: uppercase" else null
                else -> null
            }
            if (limpio != null) partes += limpio
        }
        return partes.joinToString("; ").ifBlank { null }
    }

    private fun familiaPermitida(valor: String): String? {
        val limpia = valor.trim().trim('"').trim('\'').lowercase()
        return when (limpia) {
            "arial" -> "Arial"
            "times new roman" -> "'Times New Roman'"
            "estrecha" -> "Estrecha"
            else -> null
        }
    }

    private fun colorPermitido(valor: String): String? {
        val v = valor.trim().lowercase().replace(" ", "")
        return when (v) {
            "#000", "#000000", "rgb(0,0,0)" -> "#000000"
            "#1f4c8f", "rgb(31,76,143)" -> "#1f4c8f"
            "#b91c1c", "rgb(185,28,28)" -> "#b91c1c"
            "#fff", "#ffffff", "rgb(255,255,255)" -> "#ffffff"
            else -> null
        }
    }

    /** El editor del navegador escribe &nbsp;, y el PDF solo acepta el número. */
    private fun xmlSeguro(html: String): String =
        html.replace("&nbsp;", "&#160;")
            .replace("&ensp;", "&#8194;")
            .replace("&emsp;", "&#8195;")

    private fun nombreEtiqueta(html: String, inicio: Int): String {
        var i = inicio + 1
        val nombre = StringBuilder()
        while (i < html.length && html[i].isLetter()) {
            nombre.append(html[i])
            i++
        }
        return nombre.toString().lowercase()
    }

    private fun indiceCierre(html: String, desde: Int, tag: String): Int {
        val abre = Regex("<$tag(\\s|>)", RegexOption.IGNORE_CASE)
        val cierra = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        var profundidad = 1
        var i = desde
        while (i < html.length && profundidad > 0) {
            val siguienteAbre = abre.find(html, i)
            val siguienteCierra = cierra.find(html, i) ?: return html.length
            if (siguienteAbre != null && siguienteAbre.range.first < siguienteCierra.range.first) {
                profundidad++
                i = siguienteAbre.range.last + 1
            } else {
                profundidad--
                if (profundidad == 0) return siguienteCierra.range.first
                i = siguienteCierra.range.last + 1
            }
        }
        return html.length
    }
}
