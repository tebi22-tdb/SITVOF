package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId

data class DocumentoAdjunto(
    val gridfs_id: ObjectId? = null,
    val nombre_original: String = "",
    val content_type: String = "",
    val tamanio_bytes: Long = 0L,
    val fecha_subida: java.time.Instant? = null,
)
