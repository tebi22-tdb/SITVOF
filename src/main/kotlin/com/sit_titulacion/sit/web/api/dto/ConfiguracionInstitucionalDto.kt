package com.sit_titulacion.sit.web.api.dto

data class ConfigGlobalDto(
    val jefeDivisionNombre: String,
    val jefeDivisionTitulo: String,
)

data class ConfigGlobalResponseDto(
    val jefeDivisionNombre: String,
    val jefeDivisionTitulo: String,
    val tieneImagenAnual: Boolean,
)

data class ConfigDepartamentoDto(
    val departamentoNombreCompleto: String,
    val jefeNombre: String,
    val jefeCargo: String,
    val jefeIniciales: String,
)

data class ConfigDepartamentoResponseDto(
    val slug: String,
    val nombre: String = "",
    val departamentoNombreCompleto: String,
    val jefeNombre: String,
    val jefeCargo: String,
    val jefeIniciales: String,
)
