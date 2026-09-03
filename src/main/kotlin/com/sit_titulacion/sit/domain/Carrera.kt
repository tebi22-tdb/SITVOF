package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Proyección con id estable de `Catalogo(tipo="carrera")`, sincronizada
 * automáticamente por `CarreraDepartamentoSyncRunner`. `Catalogo` sigue siendo
 * donde se administra (alta/edición/baja) — esta colección es de solo lectura
 * desde el resto del sistema, pensada para resolver/comparar carreras sin
 * repetir normalización de texto en cada lugar que la necesita.
 */
@Document(collection = "carreras")
data class Carrera(
    @Id val id: ObjectId = ObjectId(),
    val nombre: String,
    @Indexed(unique = true) val slug: String,
    val activo: Boolean = true,
    val orden: Int = 0,
    val fechaCreacion: Instant = Instant.now(),
    val fechaActualizacion: Instant = Instant.now(),
)
