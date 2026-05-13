package com.sit_titulacion.sit.domain

import java.time.Instant

data class HistorialEstado(
    val estado: String,
    val fecha: Instant,
    val observacion: String? = null,
)
