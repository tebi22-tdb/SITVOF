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
     * Compara [titulo] contra todos los proyectos registrados.
     * [excluirId]: id del egresado actual al editar, para no bloquearse con su propio título.
     *
     * Retorna BLOQUEADO si el conjunto normalizado de palabras coincide exactamente,
     * ADVERTENCIA si la similitud Jaccard ≥ 75%, LIBRE en caso contrario.
     */
    fun verificar(titulo: String, excluirId: String? = null): OriginalidadResultado {
        if (titulo.isBlank()) return OriginalidadResultado("LIBRE")
        val palabrasNuevas = normalizarPalabras(titulo)
        if (palabrasNuevas.isEmpty()) return OriginalidadResultado("LIBRE")

        for (e in egresadoRepository.findAll()) {
            if (excluirId != null && e.id?.toString() == excluirId) continue
            val tituloExistente = e.datos_proyecto.nombre_proyecto
            if (tituloExistente.isBlank()) continue
            val palabrasExistente = normalizarPalabras(tituloExistente)
            if (palabrasExistente.isEmpty()) continue

            if (palabrasNuevas == palabrasExistente) {
                return OriginalidadResultado("BLOQUEADO", tituloExistente, expedienteEstadoUi(e))
            }

            val interseccion = palabrasNuevas.intersect(palabrasExistente).size
            val union = (palabrasNuevas + palabrasExistente).size
            if (union > 0 && interseccion.toDouble() / union >= 0.75) {
                return OriginalidadResultado("ADVERTENCIA", tituloExistente)
            }
        }
        return OriginalidadResultado("LIBRE")
    }

    private fun expedienteEstadoUi(e: Egresado): String = when (e.estado_general) {
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
