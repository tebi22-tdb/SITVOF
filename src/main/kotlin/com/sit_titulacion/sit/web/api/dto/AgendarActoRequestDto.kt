package com.sit_titulacion.sit.web.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

/** Fecha y hora local del acto (ej. 2026-03-20T15:30 desde input datetime-local). */
data class AgendarActoRequestDto(
    @JsonProperty("fecha_hora") val fecha_hora: String,
)
