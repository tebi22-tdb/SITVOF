package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

@Document(collection = "revisiones")
@CompoundIndex(name = "egresado_fecha", def = "{ 'egresado_id': 1, 'fecha': -1 }")
data class RevisionDocumentoAdjunto(
    @Field("nombre_original") val nombreOriginal: String,
    @Field("content_type") val contentType: String,
    @Field("tamanio_bytes") val tamanioBytes: Long,
    @Field("contenido") val contenido: ByteArray,
)

data class Revision(
    @Id val id: ObjectId? = null,
    @Field("egresado_id") val egresadoId: ObjectId,
    @Field("numero_revision") val numeroRevision: Int,
    val fecha: Instant = Instant.now(),
    @Field("revisado_por") val revisadoPor: String = "academico",
    val resultado: String, // "observaciones" | "aprobado"
    val observaciones: String? = null,
    @Field("detalle_observaciones") val detalleObservaciones: List<String>? = null,
    @Field("fecha_envio_siguiente") val fechaEnvioSiguiente: Instant? = null,
    @Field("enviado_al_egresado") val enviadoAlEgresado: Boolean = false,
    @Field("fecha_envio_egresado") val fechaEnvioEgresado: Instant? = null,
    @Field("documento_adjunto") val documentoAdjunto: RevisionDocumentoAdjunto? = null,
)
