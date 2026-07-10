package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "configuracion_institucional")
data class ConfiguracionInstitucional(
    @Id val id: ObjectId? = null,
    /** "global" | "departamento" */
    val tipo: String,
    // ── Campos tipo global ──────────────────────────────────────────────────
    val jefeDivisionNombre: String? = null,
    /** "JEFE" | "JEFA" */
    val jefeDivisionTitulo: String? = null,
    /** Iniciales para pie de página del Jefe(a) de División, ej. "MFR/xyz" */
    val jefeDivisionIniciales: String? = null,
    /** Imagen anual TECNM en base64 data URI (ej. data:image/png;base64,…).
     *  Cuando es null se usa la imagen por defecto del classpath. */
    val imagenAnualDataUri: String? = null,
    // ── Campos tipo departamento ────────────────────────────────────────────
    /** Coincide con Catalogo.slug de tipo "departamento" */
    val departamentoSlug: String? = null,
    val departamentoNombreCompleto: String? = null,
    val jefeNombre: String? = null,
    val jefeCargo: String? = null,
    /** Iniciales para pie de página, ej. "CEMB/cvr" */
    val jefeIniciales: String? = null,
    val fechaActualizacion: Instant = Instant.now(),
)
