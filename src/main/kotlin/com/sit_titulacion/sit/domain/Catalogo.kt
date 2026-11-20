package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

data class PasoFlujoCatalogo(
    val nombre: String = "",
    val hace: String = "",
    val recibe: String = "",
    val anexo: String = "",
    val accion: String = "",
    val pideArchivo: Boolean = false,
    val permiteRegreso: Boolean = false,
    val cierraProceso: Boolean = false,
    /** Pestaña de la bandeja del departamento académico. Vacío si el paso no llega ahí. */
    val pestanaDepartamento: String = "",
    val llevaPlazo: Boolean = false,
    val tipoPlazo: String = "",
    val meses: Int? = null,
)

data class PlazoFlujoCatalogo(
    /** anexo_xxxi, carta o paso. */
    val inicia: String = "",
    /** Índice del paso cuando inicia es "paso". */
    val pasoInicio: Int? = null,
    /** ninguno o paso. */
    val termina: String = "ninguno",
    val pasoFin: Int? = null,
    val meses: Int? = null,
)

@Document(collection = "catalogos")
data class Catalogo(
    @Id val id: ObjectId? = null,
    val tipo: String,
    val nombre: String,
    val activo: Boolean = true,
    val orden: Int = 0,
    /** Solo para tipo = "modalidad": duración en meses calendario (null = sin plazo, ej. CENEVAL).
     *  Se mantiene por compatibilidad: residencia guarda aquí el plazo de titulación; el resto, el de proyecto. */
    val mesesVigencia: Int? = null,
    /** Solo para tipo = "modalidad": meses para desarrollar el proyecto (1–12, null = no aplica). */
    val mesesProyecto: Int? = null,
    /** Solo para tipo = "modalidad": meses para cerrar la titulación (1–12, null = no aplica). */
    val mesesTitulacion: Int? = null,
    /** Solo para tipo = "modalidad": true cuando la modalidad es Residencia Profesional (afecta flujo de certificación). */
    val esResidencia: Boolean = false,
    /** Solo para tipo = "modalidad": true cuando la modalidad aplica ÚNICAMENTE en el flujo "Curso de titulación" (ej. Monografía). */
    val esCursoTitulacion: Boolean = false,
    /** Solo para tipo = "modalidad": controla qué campos de mentores se muestran en alta/edición.
     *  "residencia" → asesor_interno + asesor_externo
     *  "estandar"   → director + asesor_1 + asesor_2
     *  "ninguno"    → sin campos de mentores
     *  null         → fallback según esResidencia (compatibilidad con registros anteriores) */
    val tipoMentores: String? = null,
    /** Solo modalidad: "titulacion" o "integral". Null = las de siempre (integral). */
    val tipoUso: String? = null,
    /** Solo modalidad: tesis, residencia, ceneval o personalizado. */
    val origenPlazo: String? = null,
    val origenFlujo: String? = null,
    /** Solo modalidad: usa el flujo CENEVAL aunque el nombre no diga CENEVAL. */
    val esCeneval: Boolean = false,
    val pasos: List<PasoFlujoCatalogo> = emptyList(),
    val plazos: List<PlazoFlujoCatalogo> = emptyList(),
    val mentoresExtra: List<String> = emptyList(),
    /** Solo para tipo = "departamento": clave interna (ej. "virtuales", "ingenierias"). Debe coincidir con segmento_academico en usuarios. */
    val slug: String? = null,
    /** Solo para tipo = "departamento": lista de carreras asignadas. */
    val carreras: List<String> = emptyList(),
    val fechaCreacion: Instant = Instant.now(),
    val fechaActualizacion: Instant = Instant.now(),
)
