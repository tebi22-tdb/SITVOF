package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.NormalizacionTexto

/**
 * Empareja el nombre guardado en el tribunal con el correo del catálogo de docentes.
 * Tolera mayúsculas, acentos, espacios de más y un título delante (Mtro., Ing., Dr.).
 * Si el nombre está repetido en el catálogo, se usa el primero. La misma dirección no se repite.
 */
internal object CorreosSinodales {
    private val prefijo = Regex(
        "^(ing\\.?|ingeniero|ingeniera|mtro\\.?|mtra\\.?|maestro|maestra|dr\\.?|dra\\.?|doctor|doctora|lic\\.?|licenciado|licenciada|profr\\.?|profra\\.?|profesor|profesora)\\s+",
    )

    fun resolver(nombres: List<String>, docentes: List<Pair<String, String>>): List<Pair<String, String>> {
        val porClave = LinkedHashMap<String, String>()
        val entradas = ArrayList<Entrada>()
        for ((nombre, correo) in docentes) {
            val mail = correo.trim()
            val clave = runCatching { NormalizacionTexto.normalizarClave(nombre) }.getOrDefault("")
            if (mail.isBlank() || clave.isBlank() || porClave.containsKey(clave)) continue
            porClave[clave] = mail
            entradas += Entrada(mail, clave, sinTitulo(clave))
        }
        val vistos = mutableSetOf<String>()
        val salida = ArrayList<Pair<String, String>>()
        for (nombre in nombres) {
            val visible = nombre.trim()
            if (visible.isBlank()) continue
            val correo = buscar(visible, porClave, entradas) ?: continue
            if (!vistos.add(correo.lowercase())) continue
            salida += visible to correo
        }
        return salida
    }

    private fun buscar(nombre: String, porClave: Map<String, String>, entradas: List<Entrada>): String? {
        val clave = runCatching { NormalizacionTexto.normalizarClave(nombre) }.getOrDefault("")
        if (clave.isBlank()) return null
        porClave[clave]?.let { return it }
        val sin = sinTitulo(clave)
        val porTitulo = entradas.filter { it.sinTitulo == sin || it.clave == sin }
        if (porTitulo.size == 1) return porTitulo.first().correo
        val porPalabras = coincidenciaPorPalabras(sin, entradas)
        if (porPalabras != null) return porPalabras
        val palabras = sin.split(" ").filter { it.isNotBlank() }
        if (palabras.size < 2) return null
        val contiene = entradas.filter { entrada ->
            val otro = entrada.sinTitulo
            otro.isNotBlank() && (otro.contains(sin) || sin.contains(otro))
        }
        return if (contiene.size == 1) contiene.first().correo else null
    }

    /** LOPEZ JUAN PEREZ coincide con Juan Perez Lopez si el resultado es unico. */
    private fun coincidenciaPorPalabras(sin: String, entradas: List<Entrada>): String? {
        val palabras = sin.split(" ").filter { it.length >= 3 }
        if (palabras.size < 2) return null
        val hits = entradas.filter { entrada ->
            val delCatalogo = entrada.sinTitulo.split(" ").filter { it.length >= 3 }.toSet()
            palabras.all { it in delCatalogo }
        }
        return if (hits.size == 1) hits.first().correo else null
    }

    private fun sinTitulo(clave: String): String {
        var actual = clave.trim()
        repeat(3) {
            val siguiente = prefijo.replace(actual, "").trim()
            if (siguiente == actual) return actual
            actual = siguiente
        }
        return actual
    }

    private data class Entrada(val correo: String, val clave: String, val sinTitulo: String)
}
