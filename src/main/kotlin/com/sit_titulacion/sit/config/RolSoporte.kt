package com.sit_titulacion.sit.config

import java.text.Normalizer

/**
 * Roles en BD pueden venir compuestos (p. ej. "Coordinador - Administrador").
 * La comparación solo con [String.replace] de espacios rompe la bandeja de departamento.
 * También unifica guiones “tipográficos” (Word, Unicode) que no coinciden con `-` ASCII.
 */
object RolSoporte {
    private val separadoresCompuestos = Regex("""\s*-\s*|\s*,\s*|\s*/\s*""")
    private val trozosPalabras = Regex("""[\s/,\-–—−_.\u2010\u2011\u2012\u2013\u2014\u2212.]+""")

    private fun unificarTextoRol(rol: String): String {
        val n = Normalizer.normalize(rol.trim().lowercase(), Normalizer.Form.NFKC)
        val sb = StringBuilder(n.length)
        for (ch in n) {
            when (ch) {
                '–', '—', '−', '‐', '‑', '⁃', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Partes normalizadas: "Coordinador - Administrador" → `coordinador`, `administrador`. */
    fun tokensRol(rol: String?): Set<String> {
        if (rol.isNullOrBlank()) return emptySet()
        val base = unificarTextoRol(rol)
        val porGuionComa =
            separadoresCompuestos.split(base)
                .map { it.trim().replace(' ', '_') }
                .filter { it.isNotEmpty() }
        val porBloques =
            trozosPalabras.split(base)
                .map { it.trim().replace(' ', '_') }
                .filter { it.isNotEmpty() }
        val partes = (porGuionComa + porBloques).toSet()
        return partes.ifEmpty { setOf(base.replace(' ', '_')) }
    }

    fun tieneAlgunRol(rol: String?, vararg claves: String): Boolean {
        val permitidos =
            claves.map { it.trim().lowercase().replace(' ', '_') }
                .filter { it.isNotEmpty() }
                .toSet()
        if (permitidos.isEmpty()) return false
        return tokensRol(rol).any { it in permitidos }
    }

    /** Acceso a detalle sin filtro de carrera (antes solo `coordinador`). */
    fun esCoordinadorOAdmin(rol: String?): Boolean = tieneAlgunRol(rol, "coordinador", "administrador")
}
