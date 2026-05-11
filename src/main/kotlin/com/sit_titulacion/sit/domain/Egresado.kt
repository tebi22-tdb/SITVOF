package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

@Document(collection = "registro")
data class Egresado(
    @Id val id: ObjectId? = null,
    val numero_control: String,
    val datos_personales: DatosPersonales,
    @Field("fecha_creacion") val fechaCreacion: Instant = Instant.now(),
    val fecha_actualizacion: Instant = Instant.now(),
    val procesos: List<ProcesoTitulacion> = emptyList(),
) {
    /** Proceso activo: el último en la lista. Lanza excepción si no hay ninguno. */
    fun procesoActivo(): ProcesoTitulacion = procesos.last()

    /** Proceso activo o null si la lista está vacía. */
    fun procesoActivoOrNull(): ProcesoTitulacion? = procesos.lastOrNull()

    /** Reemplaza el proceso activo (último) con uno actualizado. */
    fun actualizarProcesoActivo(proceso: ProcesoTitulacion): Egresado =
        copy(
            procesos = procesos.dropLast(1) + proceso,
            fecha_actualizacion = Instant.now(),
        )
}
