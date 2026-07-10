package com.sit_titulacion.sit.web.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ServiciosEscolaresBandejaItemDto(
    val id: String,
    @JsonProperty("numero_control") val numero_control: String,
    @JsonProperty("nombre_completo") val nombre_completo: String,
    val carrera: String,
    @JsonProperty("fecha_solicitud_anexo_9_2") val fecha_solicitud_anexo_9_2: String,
)

data class ServiciosEscolaresDetalleDto(
    val id: String,
    @JsonProperty("numero_control") val numero_control: String,
    @JsonProperty("nombre_completo") val nombre_completo: String,
    @JsonProperty("fecha_constancia_no_inconveniencia") val fecha_constancia_no_inconveniencia: String?,
    @JsonProperty("fecha_creacion_anexo_9_1") val fecha_creacion_anexo_9_1: String?,
    val modalidad: String,
    @JsonProperty("nombre_proyecto") val nombre_proyecto: String,
    @JsonProperty("fecha_solicitud_anexo_9_2") val fecha_solicitud_anexo_9_2: String,
    @JsonProperty("fecha_aceptacion_servicios_escolares_anexo_9_2")
    val fecha_aceptacion_servicios_escolares_anexo_9_2: String?,
    @JsonProperty("fecha_creacion_anexo_9_2")
    val fecha_creacion_anexo_9_2: String?,
)
