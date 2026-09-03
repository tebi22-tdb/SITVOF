package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Proyección con id estable de `Catalogo(tipo="departamento")`, sincronizada
 * automáticamente por `CarreraDepartamentoSyncRunner`. `Catalogo` sigue siendo
 * donde se administra — esta colección es de solo lectura desde el resto del
 * sistema. `carreraIds` reemplaza la lista de nombres de carrera en texto
 * libre (`Catalogo.carreras: List<String>`) por referencias reales a [Carrera].
 */
@Document(collection = "departamentos")
data class Departamento(
    @Id val id: ObjectId = ObjectId(),
    val nombre: String,
    /** Coincide con `segmento_academico` en `Usuario` y con `Catalogo.slug`. */
    @Indexed(unique = true) val slug: String,
    val carreraIds: List<ObjectId> = emptyList(),
    val activo: Boolean = true,
    val orden: Int = 0,
    val fechaCreacion: Instant = Instant.now(),
    val fechaActualizacion: Instant = Instant.now(),
)
