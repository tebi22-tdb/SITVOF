package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "docentes")
data class Docente(
    @Id val id: ObjectId? = null,
    val nombreCompleto: String,
    val correo: String,
    val cedula: String,
    /** M = masculino, F = femenino (redacción de documentos oficiales). */
    val genero: String? = null,
    val activo: Boolean = true,
    val fechaCreacion: Instant = Instant.now(),
    val fechaActualizacion: Instant = Instant.now(),
)
