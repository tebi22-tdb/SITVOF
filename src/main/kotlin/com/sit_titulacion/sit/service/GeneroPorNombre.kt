package com.sit_titulacion.sit.service

import java.util.Locale

enum class GeneroDocumento { MASCULINO, FEMENINO }

/**
 * Género gramatical para documentos: primero el código guardado (M/F), si no hay, inferencia por nombre.
 */
object GeneroPorNombre {

    private val PREFIJOS_TITULO = setOf(
        "DR", "DRA", "DR.", "DRA.", "MTRO", "MTRA", "MTRO.", "MTRA.", "LIC", "LIC.",
        "ING", "ING.", "PROF", "PROF.", "C.P.", "CP", "ARQ", "ARQ.", "M.C.", "MC",
    )

    private val FEMENINOS = setOf(
        "maria", "guadalupe", "ana", "laura", "mariana", "patricia", "rosa", "elena", "carmen",
        "gabriela", "bety", "beatriz", "mercedes", "diana", "sandra", "veronica", "claudia",
        "silvia", "teresa", "luisa", "leticia", "alejandra", "fernanda", "consuelo", "gloria",
        "margarita", "yolanda", "norma", "luz", "adriana", "karla", "paola", "andrea", "monica",
        "rebeca", "isabel", "rocio", "martha", "marta", "liliana", "viridiana", "esperanza",
        "guillermina", "irma", "socorro", "graciela", "lourdes", "miriam", "nancy", "olga",
        "pilar", "raquel", "susana", "victoria", "xochitl", "itzel", "citlali",
    )

    private val MASCULINOS = setOf(
        "jose", "juan", "manuel", "carlos", "pedro", "luis", "miguel", "francisco", "antonio",
        "jesus", "david", "jorge", "fernando", "ricardo", "fabian", "merlin", "alberto", "raul",
        "roberto", "sergio", "eduardo", "arturo", "oscar", "hugo", "victor", "pablo", "mario",
        "enrique", "gerardo", "guillermo", "hector", "ivan", "jaime", "julio", "leonardo",
        "martin", "nicolas", "omar", "ramon", "salvador", "tomas", "andres", "felipe", "diego",
        "esteban", "adrian", "emilio", "ernesto", "gustavo", "ignacio", "israel", "javier",
        "marco", "mauricio", "rodolfo", "ruben", "samuel", "sebastian", "ulises",
    )

    private val MASCULINOS_TERMINAN_A = setOf("joshua", "garcia", "luca", "nikola", "mustafa")

    fun normalizarCodigo(codigo: String?): String? = when (codigo?.trim()?.uppercase(Locale.ROOT)) {
        "F", "FEMENINO" -> "F"
        "M", "MASCULINO" -> "M"
        else -> null
    }

    fun resolver(codigo: String?, nombreCompleto: String): GeneroDocumento =
        when (normalizarCodigo(codigo)) {
            "F" -> GeneroDocumento.FEMENINO
            "M" -> GeneroDocumento.MASCULINO
            else -> inferir(nombreCompleto)
        }

    fun inferir(nombreCompleto: String): GeneroDocumento {
        val primero = primerNombre(nombreCompleto).lowercase(Locale.ROOT)
        if (primero.isBlank()) return GeneroDocumento.MASCULINO
        if (primero in FEMENINOS) return GeneroDocumento.FEMENINO
        if (primero in MASCULINOS) return GeneroDocumento.MASCULINO
        if (primero.endsWith("a") && primero !in MASCULINOS_TERMINAN_A) return GeneroDocumento.FEMENINO
        return GeneroDocumento.MASCULINO
    }

    fun etiquetaCiudadano(genero: GeneroDocumento): String = when (genero) {
        GeneroDocumento.FEMENINO -> "C.C."
        GeneroDocumento.MASCULINO -> "C."
    }

    fun tratamientoEgresadoOficio(genero: GeneroDocumento): String = when (genero) {
        GeneroDocumento.FEMENINO -> "de la C.C."
        GeneroDocumento.MASCULINO -> "del C."
    }

    fun etiquetaPresidente(genero: GeneroDocumento): String = when (genero) {
        GeneroDocumento.FEMENINO -> "PRESIDENTA"
        GeneroDocumento.MASCULINO -> "PRESIDENTE"
    }

    fun etiquetaSecretario(genero: GeneroDocumento): String = when (genero) {
        GeneroDocumento.FEMENINO -> "SECRETARIA"
        GeneroDocumento.MASCULINO -> "SECRETARIO"
    }

    fun cargoConGenero(cargo: String, genero: GeneroDocumento): String {
        var c = cargo.trim()
        if (genero == GeneroDocumento.FEMENINO) {
            c = c.replace(Regex("(?i)\\bJEFE\\b")) { "JEFA" }
        } else {
            c = c.replace(Regex("(?i)\\bJEFA\\b")) { "JEFE" }
        }
        return c
    }

    fun cargoConGenero(cargo: String, nombreTitular: String): String =
        cargoConGenero(cargo, resolver(null, nombreTitular))

    fun cargoTitularDepartamento(nombreDepto: String, genero: GeneroDocumento): String {
        var n = nombreDepto.trim().uppercase(Locale.forLanguageTag("es-MX"))
        n = n.replace("DEPARTAMENTO DE ", "DEPTO. DE ")
        n = n.replace("DEPARTAMENTO DEL ", "DEPTO. DEL ")
        val titulo = if (genero == GeneroDocumento.FEMENINO) "JEFA" else "JEFE"
        return "$titulo DEL $n"
    }

    fun cargoTitularDepartamento(nombreDepto: String, nombreTitular: String): String =
        cargoTitularDepartamento(nombreDepto, resolver(null, nombreTitular))

    private fun primerNombre(nombreCompleto: String): String {
        val tokens = nombreCompleto.trim()
            .split(Regex("\\s+"))
            .map { it.trim('.', ',', ';', ':') }
            .filter { it.isNotBlank() }
        for (t in tokens) {
            val up = t.uppercase(Locale.ROOT)
            if (up in PREFIJOS_TITULO || up.replace(".", "") in PREFIJOS_TITULO.map { it.replace(".", "") }) {
                continue
            }
            return t
        }
        return tokens.firstOrNull().orEmpty()
    }
}
