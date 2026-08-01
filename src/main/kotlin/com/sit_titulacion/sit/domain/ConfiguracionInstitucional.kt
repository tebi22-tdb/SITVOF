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
    /** Correo de la División de Estudios Profesionales, para pie de página de anexos. */
    val jefeDivisionCorreo: String? = null,
    // ── Campos tipo departamento ────────────────────────────────────────────
    /** Coincide con Catalogo.slug de tipo "departamento" */
    val departamentoSlug: String? = null,
    val departamentoNombreCompleto: String? = null,
    val jefeNombre: String? = null,
    val jefeCargo: String? = null,
    /** Iniciales para pie de página, ej. "CEMB/cvr" */
    val jefeIniciales: String? = null,
    /** Correo del departamento, para pie de página de los anexos que genera. */
    val correo: String? = null,
    // ── Campos tipo imagenes_anexos (documento único) ───────────────────────
    /** Overrides de imágenes de los anexos 32/33: clave = nombre de archivo en el
     *  classpath (ej. "logo-sep-tecnm.png"), valor = data URI subida desde el admin.
     *  Si una clave no está presente, se usa la imagen por defecto del classpath.
     *  Cubre los casilleros fijos: logos de encabezado e imagen anual. */
    val imagenesAnexos: Map<String, String>? = null,
    /** Lista ordenada (izquierda a derecha) de insignias del pie de los anexos 32/33.
     *  A diferencia de [imagenesAnexos], esta lista se puede agregar/quitar/reordenar
     *  libremente. Cuando es null, se usan las insignias por defecto del classpath. */
    val insigniasAnexos: List<InsigniaAnexoEntry>? = null,
    val fechaActualizacion: Instant = Instant.now(),
)

data class InsigniaAnexoEntry(
    val id: String,
    val dataUri: String,
)
