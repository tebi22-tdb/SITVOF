package com.sit_titulacion.sit.domain

import org.springframework.data.mongodb.core.mapping.Field

data class DatosPersonales(
    @Field("nombre") val nombre: String,
    @Field("apellido_paterno") val apellido_paterno: String,
    @Field("apellido_materno") val apellido_materno: String,
    val carrera: String,
    val nivel: String,
    val direccion: String? = null,
    val telefono: String? = null,
    val correo_electronico: String? = null,
)
