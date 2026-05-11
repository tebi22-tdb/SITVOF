package com.sit_titulacion.sit.web.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class TituladoPublicoDto(
    val nombre: String,
    val carrera: String,
    val nivel: String,
    val modalidad: String,
    @JsonProperty("nombre_proyecto") val nombreProyecto: String,
    @JsonProperty("asesor_interno") val asesorInterno: String?,
    @JsonProperty("asesor_externo") val asesorExterno: String?,
    val director: String?,
    @JsonProperty("asesor_1") val asesor1: String?,
    @JsonProperty("asesor_2") val asesor2: String?,
    val anio: Int?,
)
