package com.sit_titulacion.sit.domain

/**
 * Reglas sobre el número de control de un alumno — hoy solo la generación
 * de ingreso. Fuente única, sin Spring de por medio (fácil de testear), en
 * el mismo espíritu que [NormalizacionTexto].
 */
object NumeroControl {
    private val PREFIJO_ANIO = Regex("^(\\d{2})")

    /**
     * Año de ingreso real del alumno (20XX), a partir de los dos primeros
     * dígitos del número de control — se mantiene sin importar el largo
     * total del número ni las letras que se le agreguen después (`C` =
     * cambio de carrera, `B` = baja temporal). Los alumnos que llegan de
     * otra institución reciben un número de control nuevo asignado por este
     * plantel, así que también siguen esta misma convención.
     *
     * `null` si el número no empieza con 2 dígitos (dato mal capturado o
     * atípico) — el egresado no se descarta de ningún listado, solo no
     * puede filtrarse ni encontrarse por una generación de ingreso.
     */
    fun generacionIngreso(numeroControl: String): Int? =
        PREFIJO_ANIO.find(numeroControl.trim())?.groupValues?.get(1)?.let { 2000 + it.toInt() }
}
