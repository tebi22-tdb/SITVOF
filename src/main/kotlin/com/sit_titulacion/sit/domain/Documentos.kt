package com.sit_titulacion.sit.domain

import java.time.Instant

data class AnexoXxxi(
    val fecha_registro: Instant? = null,
    val estado: String = "pendiente",
)

data class ConstanciaNoInconveniencia(
    val fecha_expedicion: Instant? = null,
    val estado: String = "pendiente",
)

data class Documentos(
    val anexo_xxxi: AnexoXxxi = AnexoXxxi(),
    val constancia_no_inconveniencia: ConstanciaNoInconveniencia = ConstanciaNoInconveniencia(),
)
