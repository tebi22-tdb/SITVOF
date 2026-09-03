package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
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
    /** M = masculino, F = femenino (redacción de documentos oficiales). */
    val genero: String? = null,
    /**
     * Referencia resuelta a [Carrera]/[Departamento] (id estable) — `carrera` sigue
     * siendo el texto libre que ya usa todo el sistema (PDFs, formularios, filtros
     * existentes); estos dos campos son ADITIVOS, para que reportes/agregaciones
     * futuras puedan agrupar/filtrar sin repetir la normalización de texto.
     * Los resuelve y mantiene al día `CarreraDepartamentoSyncRunner`.
     */
    val carreraId: ObjectId? = null,
    val departamentoId: ObjectId? = null,
)
