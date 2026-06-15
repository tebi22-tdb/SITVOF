package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "residencias_coordinacion")
data class ResidenciaCoordinacion(
    @Id val id: ObjectId? = null,
    val numero_control: String,
    val nombre_alumno: String,
    val carrera: String,
    val coordinacion: String,
    val tipo_proyecto: String,
    val nombre_proyecto: String,
    val asesor_interno: String,
    val asesor_externo: String? = null,
    val fecha_carta_aceptacion: String,
    val fecha_inicio: String,
    val fecha_fin: String,
    val estado: String = "activa",
    val anexo_29: DocumentoAdjunto? = null,
    val anexo_30: DocumentoAdjunto? = null,
    val fecha_creacion: Instant = Instant.now(),
    val fecha_actualizacion: Instant = Instant.now(),
)
