package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.repository.EgresadoRepository
import org.springframework.stereotype.Service
import java.text.Normalizer

data class OriginalidadResultado(
    /** "LIBRE", "CONFIRMAR", "ADVERTENCIA" o "BLOQUEADO" */
    val estado: String,
    val tituloSimilar: String? = null,
    /** Solo si [estado] es BLOQUEADO: vencido, titulado o en_proceso (mensajes en alta). */
    val expedienteEstado: String? = null,
    /** cupo_modalidad | otra_modalidad | misma_modalidad_existente | pareja_residencia |
     *  cupo_pareja_residencia | titulo_vencido | titulo_concluido (ya usado en vencido o titulado). */
    val motivo: String? = null,
    /** Cuántos expedientes activos ya usan el mismo título en la misma modalidad. */
    val coincidenciasMismaModalidad: Int = 0,
    /** true solo cuando es Residencia Profesional y hay exactamente 1 coincidencia: proyecto en pareja reconocido. */
    val esParejaResidencia: Boolean = false,
    /** Nombre completo del compañero, solo si [esParejaResidencia]. */
    val companeroNombre: String? = null,
    /** Número de control del compañero, solo si [esParejaResidencia]. */
    val companeroNumeroControl: String? = null,
)

@Service
class OriginalidadService(
    private val egresadoRepository: EgresadoRepository,
    private val catalogoService: CatalogoService,
) {

    companion object {
        /**
         * Cupo general (Tesina, Monografía y demás): el título solo 1 vez en esa modalidad.
         * Tesis, Proyecto de Investigación y Residencia usan [MAX_CUPO_PAREJA] (= 2).
         */
        const val MAX_CUPO_MISMA_MODALIDAD = 1
        /** Residencia, Tesis y Proyecto de Investigación: máximo 2 (pareja). */
        const val MAX_CUPO_PAREJA = 2
        /** @deprecated usar [MAX_CUPO_PAREJA] */
        const val MAX_CUPO_RESIDENCIA_PAREJA = MAX_CUPO_PAREJA
    }

    private val STOPWORDS = setOf(
        "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas",
        "y", "e", "o", "u", "a", "en", "con", "por", "para", "que", "se",
        "al", "su", "sus", "es", "son", "como", "desde", "hacia", "hasta",
        "entre", "sobre", "bajo", "sin", "ante", "tras",
    )

    /**
     * Compara [titulo] contra proyectos registrados.
     * - Títulos de procesos **vencidos o concluidos (titulado)** (activo o historial): no reutilizables.
     * - Misma modalidad (procesos en curso): cupo según [cupoMaximoPara].
     * - Otra modalidad (en curso): título igual o muy similar bloquea/advierte.
     * [excluirId] omite el proceso activo en curso de ese egresado (edición); cerrados sí se revisan.
     */
    fun verificar(titulo: String, modalidad: String? = null, excluirId: String? = null): OriginalidadResultado {
        if (titulo.isBlank()) return OriginalidadResultado("LIBRE")
        if (esModalidadCeneval(modalidad)) return OriginalidadResultado("LIBRE")

        val palabrasNuevas = normalizarPalabras(titulo)
        if (palabrasNuevas.isEmpty()) return OriginalidadResultado("LIBRE")

        val modalidadNorm = normalizarModalidad(modalidad)
        var coincidenciasMismaModalidad = 0
        var tituloReferenciaMismaModalidad: String? = null
        var companeroMismaModalidad: Egresado? = null
        var conflictoOtraModalidad: Egresado? = null
        var tituloConflictoOtraModalidad: String? = null
        var advertenciaOtraModalidad: Pair<String, Egresado>? = null
        var tituloConflictoCerrado: String? = null
        var estadoConflictoCerrado: String? = null

        for (e in egresadoRepository.findAll()) {
            val esExcluido = excluirId != null && e.id?.toString() == excluirId

            // 1) Títulos de procesos vencidos o concluidos (incluye historial): no reutilizables
            for (proceso in e.procesos) {
                val estadoProc = proceso.estado_general.trim().lowercase()
                if (estadoProc != "vencido" && estadoProc != "titulado") continue
                val tituloExistente = proceso.datos_proyecto.nombre_proyecto
                if (tituloExistente.isBlank()) continue
                if (esModalidadCeneval(proceso.datos_proyecto.modalidad)) continue
                val palabrasExistente = normalizarPalabras(tituloExistente)
                if (palabrasExistente.isEmpty()) continue
                if (palabrasNuevas == palabrasExistente) {
                    tituloConflictoCerrado = tituloExistente
                    estadoConflictoCerrado = estadoProc
                    break
                }
            }
            if (tituloConflictoCerrado != null) break

            // 2) Proceso activo en curso (no vencido / no titulado)
            if (esExcluido) continue
            val proceso = e.procesoActivoOrNull() ?: continue
            val estadoActivo = proceso.estado_general.trim().lowercase()
            if (estadoActivo == "vencido" || estadoActivo == "titulado") continue

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
                    companeroMismaModalidad = e
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

        if (tituloConflictoCerrado != null) {
            val esConcluido = estadoConflictoCerrado == "titulado"
            return OriginalidadResultado(
                "BLOQUEADO",
                tituloConflictoCerrado,
                expedienteEstado = if (esConcluido) "titulado" else "vencido",
                motivo = if (esConcluido) "titulo_concluido" else "titulo_vencido",
            )
        }

        if (conflictoOtraModalidad != null) {
            return OriginalidadResultado(
                "BLOQUEADO",
                tituloConflictoOtraModalidad,
                expedienteEstadoUi(conflictoOtraModalidad),
                "otra_modalidad",
            )
        }

        val esResidencia = catalogoService.esResidenciaPorNombre(modalidad ?: "")
        val cupoAplicable = cupoMaximoPara(modalidad)

        if (coincidenciasMismaModalidad >= cupoAplicable) {
            return OriginalidadResultado(
                "BLOQUEADO",
                tituloReferenciaMismaModalidad,
                motivo = if (esResidencia) "cupo_pareja_residencia" else "cupo_modalidad",
                coincidenciasMismaModalidad = coincidenciasMismaModalidad,
            )
        }
        if (advertenciaOtraModalidad != null) {
            return OriginalidadResultado("ADVERTENCIA", advertenciaOtraModalidad.first)
        }
        if (esResidencia && coincidenciasMismaModalidad == 1) {
            // Proyecto en pareja: ya hay un egresado con este título en Residencia Profesional.
            // No es un conflicto, es el segundo integrante — se reconoce automáticamente y se
            // muestra quién es el compañero (el frontend pide confirmar con un checkbox).
            return OriginalidadResultado(
                "CONFIRMAR",
                tituloReferenciaMismaModalidad,
                motivo = "pareja_residencia",
                coincidenciasMismaModalidad = coincidenciasMismaModalidad,
                esParejaResidencia = true,
                companeroNombre = companeroMismaModalidad?.let { nombreCompletoDe(it) },
                companeroNumeroControl = companeroMismaModalidad?.numero_control,
            )
        }
        if (coincidenciasMismaModalidad in 1 until cupoAplicable) {
            return OriginalidadResultado(
                "CONFIRMAR",
                tituloReferenciaMismaModalidad,
                motivo = "misma_modalidad_existente",
                coincidenciasMismaModalidad = coincidenciasMismaModalidad,
            )
        }
        return OriginalidadResultado("LIBRE")
    }

    /** Cupo máximo de egresados con el mismo título en la misma modalidad. */
    fun cupoMaximoPara(modalidad: String?): Int {
        if (catalogoService.esResidenciaPorNombre(modalidad ?: "")) return MAX_CUPO_PAREJA
        if (esModalidadTesis(modalidad) || esModalidadProyectoInvestigacion(modalidad)) return MAX_CUPO_PAREJA
        return MAX_CUPO_MISMA_MODALIDAD
    }

    /** Tesis / TESIS PROFESIONAL (no Tesina). */
    private fun esModalidadTesis(modalidad: String?): Boolean {
        val m = normalizarModalidad(modalidad)
        if (m.isEmpty() || m.contains("tesina")) return false
        return m == "tesis" || m.startsWith("tesis ") || m.contains("tesis profesional") || m.contains("tesis")
    }

    private fun esModalidadProyectoInvestigacion(modalidad: String?): Boolean {
        val m = normalizarModalidad(modalidad)
        return m.contains("proyecto") && m.contains("investigacion")
    }

    private fun nombreCompletoDe(e: Egresado): String =
        listOf(e.datos_personales.nombre, e.datos_personales.apellido_paterno, e.datos_personales.apellido_materno)
            .filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { e.numero_control }

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
