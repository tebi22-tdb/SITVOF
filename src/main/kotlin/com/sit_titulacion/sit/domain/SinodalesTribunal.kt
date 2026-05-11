package com.sit_titulacion.sit.domain

/**
 * Tribunal para acto de recepción profesional (subdocumento en egresado, campo `sinodales_tribunal`).
 * Nombres de propiedad en snake_case coinciden con las claves en MongoDB.
 */
data class SinodalesTribunal(
    val presidente: String = "",
    val secretario: String = "",
    val vocal: String = "",
    val vocal_suplente: String = "",
)
