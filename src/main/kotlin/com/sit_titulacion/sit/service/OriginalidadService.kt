package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.repository.EgresadoRepository
import org.springframework.stereotype.Service
import java.text.Normalizer

data class OriginalidadResultado(
    /** "LIBRE", "ADVERTENCIA" o "BLOQUEADO" */
    val estado: String,
    val tituloSimilar: String? = null,
    /** Solo si [estado] es BLOQUEADO: vencido, titulado o en_proceso (mensajes en alta). */
    val expedienteEstado: String? = null,
    /** cupo_modalidad: ya hay 2 con el mismo título en esa modalidad; otra_modalidad: título igual en otra modalidad. */
    val motivo: String? = null,
)

@Service
class OriginalidadService(private val egresadoRepository: EgresadoRepository) {

    private val STOPWORDS = setOf(
        "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas",
        "y", "e", "o", "u", "a", "en", "con", "por", "para", "que", "se",
        "al", "su", "sus", "es", "son", "como", "desde", "hacia", "hasta",
        "entre", "sobre", "bajo", "sin", "ante", "tras",
    )

    /**
     * Compara [titulo] contra proyectos registrados.
     * [modalidad]: misma modalidad permite hasta 2 egresados con el mismo título (ej. residencia en pareja).
     * Otra modalidad: título igual o muy similar sigue bloqueando/advirtiendo.
     */
    fun verificar(titulo: String, modalidad: String? = null, excluirId: String? = null): OriginalidadResultado {
        if (titulo.isBlank()) return OriginalidadResultado("LIBRE")
        if (esModalidadCeneval(modalidad)) return OriginalidadResultado("LIBRE")

        val palabrasNuevas = normalizarPalabras(titulo)
        if (palabrasNuevas.isEmpty()) return OriginalidadResultado("LIBRE")

        val modalidadNorm = normalizarModalidad(modalidad)
        var coincidenciasMismaModalidad = 0
        var tituloReferenciaMismaModalidad: String? = null
        var conflictoOtraModalidad: Egresado? = null
        var tituloConflictoOtraModalidad: String? = null
        var advertenciaOtraModalidad: Pair<String, Egresado>? = null

        for (e in egresadoRepository.findAll()) {
            if (excluirId != null && e.id?.toString() == excluirId) continue
            val proceso = e.procesoActivoOrNull() ?: continue
            val tituloExistente = proceso.datos_proyecto.nombre_proyecto
            if (tituloExistente.isBlank()) continue
            if (esModalidadCeneval(proceso.datos_proyecto.modalidad)) continue

            val palabrasExistente = normalizarPalabras(tituloExistente)
            if (palabrasExistente.isEmpty()) continue
            val modExistente = normalizarModalidad(proceso.datos_proyecto.modalidad)

            if (palabrasNuevas == palabrasExistente) {
                if (modalidadNorm.isNotEmpty() && modalidadNorm == modExistente) {
                    coincidenciasMismaModalidad++
                    tituloReferenciaMismaModalidad = tituloExistente
                } else if (conflictoOtraModalidad == null) {
                    conflictoOtraModalidad = e
                    tituloConflictoOtraModalidad = tituloExistente
                }
                continue
            }

            val interseccion = palabrasNuevas.intersect(palabrasExistente).size
            val union = (palabrasNuevas + palabrasExistente).size
            if (union > 0 && interseccion.toDouble() / union >= 0.75) {
                if (modalidadNorm.isEmpty() || modalidadNorm != modExistente) {
                    if (advertenciaOtraModalidad == null) {
                        advertenciaOtraModalidad = tituloExistente to e
                    }
                }
            }
        }

        if (conflictoOtraModalidad != null) {
            return OriginalidadResultado(
                "BLOQUEADO",
                tituloConflictoOtraModalidad,
                expedienteEstadoUi(conflictoOtraModalidad),
                "otra_modalidad",
            )
        }
        if (coincidenciasMismaModalidad >= 2) {
            return OriginalidadResultado(
                "BLOQUEADO",
                tituloReferenciaMismaModalidad,
                motivo = "cupo_modalidad",
            )
        }
        if (advertenciaOtraModalidad != null) {
            return OriginalidadResultado("ADVERTENCIA", advertenciaOtraModalidad.first)
        }
        return OriginalidadResultado("LIBRE")
    }

    private fun esModalidadCeneval(modalidad: String?): Boolean {
        val m = normalizarModalidad(modalidad)
        return m.contains("ceneval")
    }

    private fun normalizarModalidad(modalidad: String?): String {
        if (modalidad.isNullOrBlank()) return ""
        val sinAcentos = Normalizer.normalize(modalidad.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
        return sinAcentos.lowercase().replace(Regex("\\s+"), " ")
    }

    private fun expedienteEstadoUi(e: Egresado): String = when (e.procesoActivoOrNull()?.estado_general) {
        "vencido" -> "vencido"
        "titulado" -> "titulado"
        else -> "en_proceso"
    }

    private fun normalizarPalabras(titulo: String): Set<String> {
        val sinAcentos = Normalizer.normalize(titulo, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
        return sinAcentos.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()
    }
}
