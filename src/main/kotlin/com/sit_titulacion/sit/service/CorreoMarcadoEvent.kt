package com.sit_titulacion.sit.service

/**
 * El SMTP acepto o rechazo de forma definitiva un correo ligado a un egresado.
 * Un corte o una demora no publica este evento: ese correo sigue en la cola.
 */
data class CorreoMarcadoEvent(
    val egresadoId: String,
    val correo: String,
    val marca: String,
    val aceptado: Boolean,
    val referencia: String?,
)
