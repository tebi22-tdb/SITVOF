package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

/**
 * Usuario para autenticación.
 * - Egresados: username = número de control, egresadoId enlazado.
 * - Staff: username = correo o identificador de login, nombre y correoElectronico opcionales.
 */
@Document(collection = "usuarios")
data class Usuario(
    @Id val id: ObjectId? = null,
    @Indexed(unique = true) val username: String,
    val passwordHash: String,
    val rol: String = "egresado",
    val egresadoId: ObjectId? = null,
    val nombre: String? = null,
    val curp: String? = null,
    @Field("correo_electronico") val correoElectronico: String? = null,
    @Field("segmento_academico") val segmentoAcademico: String? = null,
    @Field("carreras_asignadas") val carrerasAsignadas: List<String> = emptyList(),
    val activo: Boolean = true,
    val fechaCreacion: Instant = Instant.now(),
    val fechaActualizacion: Instant = Instant.now(),
)
