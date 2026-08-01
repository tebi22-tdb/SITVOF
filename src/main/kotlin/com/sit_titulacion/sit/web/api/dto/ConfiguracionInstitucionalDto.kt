package com.sit_titulacion.sit.web.api.dto

data class ConfigGlobalDto(
    val jefeDivisionNombre: String,
    val jefeDivisionTitulo: String,
    val jefeDivisionIniciales: String,
    val jefeDivisionCorreo: String,
)

data class ConfigGlobalResponseDto(
    val jefeDivisionNombre: String,
    val jefeDivisionTitulo: String,
    val jefeDivisionIniciales: String,
    val jefeDivisionCorreo: String,
)

data class ConfigDepartamentoDto(
    val departamentoNombreCompleto: String,
    val jefeNombre: String,
    val jefeCargo: String,
    val jefeIniciales: String,
    val correo: String,
)

data class ConfigDepartamentoResponseDto(
    val slug: String,
    val nombre: String = "",
    val departamentoNombreCompleto: String,
    val jefeNombre: String,
    val jefeCargo: String,
    val jefeIniciales: String,
    val correo: String,
)

data class ConfigServiciosEscolaresDto(
    val jefeNombre: String,
    val jefeCargo: String,
)

data class ConfigServiciosEscolaresResponseDto(
    val jefeNombre: String,
    val jefeCargo: String,
)

/** Un "casillero" fijo de imagen para los anexos 32/33 (logos de encabezado, imagen anual). */
data class ImagenAnexoSlotDto(
    val clave: String,
    val etiqueta: String,
    val tieneImagenPersonalizada: Boolean,
)

/** Una insignia del pie de los anexos 32/33, en una lista ordenable de izquierda a derecha. */
data class InsigniaAnexoDto(
    val id: String,
    val dataUri: String,
)

data class ReordenarInsigniasDto(
    val idsEnOrden: List<String>,
)
