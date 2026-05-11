package com.sit_titulacion.sit.domain

data class DatosProyecto(
    val nombre_proyecto: String,
    val modalidad: String,
    val curso_titulacion: String = "no",
    val asesor_interno: String? = null,
    val asesor_externo: String? = null,
    val director: String? = null,
    val asesor_1: String? = null,
    val asesor_2: String? = null,
)
