package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

@Document(collection = "documentacion_escaneada")
data class DocumentacionEscaneada(
    @Id val id: ObjectId? = null,
    @Field("egresado_id") val egresadoId: ObjectId,
    @Field("numero_control") val numeroControl: String,
    @Field("nombre_completo") val nombreCompleto: String,
    @Field("carrera") val carrera: String? = null,
    @Field("fecha_registro") val fechaRegistro: Instant = Instant.now(),
    val archivos: List<ArchivoEscaneadoMeta> = emptyList(),
)

data class ArchivoEscaneadoMeta(
    @Field("gridfs_id") val gridfsId: ObjectId,
    @Field("nombre_original") val nombreOriginal: String,
    @Field("content_type") val contentType: String,
    @Field("tamanio_bytes") val tamanioBytes: Long,
)
