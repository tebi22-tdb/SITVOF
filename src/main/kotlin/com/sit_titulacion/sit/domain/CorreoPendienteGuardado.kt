package com.sit_titulacion.sit.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "correos_pendientes")
data class CorreoPendienteGuardado(
    @Id val id: String,
    val correoDestino: String,
    val subject: String,
    val saludo: String,
    val mensajeDestacado: String,
    val cuerpoHtml: String,
    val adjuntos: List<AdjuntoCorreoGuardado> = emptyList(),
    val modulos: List<String> = emptyList(),
    val segmento: String? = null,
    val intentos: Int = 0,
    val proximoIntentoEpochMs: Long = 0L,
    val egresadoId: String? = null,
    val marca: String? = null,
    val referencia: String? = null,
)

data class AdjuntoCorreoGuardado(
    val nombre: String,
    val contenido: ByteArray,
)
