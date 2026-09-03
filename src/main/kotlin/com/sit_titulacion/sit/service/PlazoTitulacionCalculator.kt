package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.ProcesoTitulacion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Clasificación de plazos para pintar en el dashboard/seguimiento: en_tiempo /
 * rezagado / vencido, con fecha límite y días restantes. Es la ÚNICA fuente de
 * verdad de esta regla de negocio — antes vivía solo en TypeScript
 * (`frontend/core/plazos-titulacion-no-residencia.ts` +
 * `plazo-desarrollo-proyecto-no-res.ts`), recalculada de forma independiente en
 * 3 componentes distintos del frontend, con riesgo real de que divergieran
 * entre sí (y del todo separado del mecanismo que marca `estado_general` en
 * BD, ver nota abajo).
 *
 * ## Nota para quien retome esto: hay DOS mecanismos de plazo, a propósito
 * [EgresadoService.plazoActivoLocal] decide cuándo marcar `estado_general =
 * "vencido"` en BD — usa los meses de vigencia configurados en el catálogo
 * (editable por un coordinador) y produce un flag binario persistido, revisado
 * en cada lectura. Este objeto calcula, también en cada lectura pero SIN
 * persistir nada, una clasificación más fina (con zona de "rezagado") usando
 * los mismos meses fijos que ya usaba el frontend (6/12/6 — no los del
 * catálogo). Unificar ambos mecanismos en uno solo queda deliberadamente
 * fuera de este port: mezclarlos ahora cambiaría fechas de vencimiento reales
 * sin que nadie lo haya decidido explícitamente. Ver plan de Fase 0.2.
 *
 * ## Aritmética de meses — diferencia intencional con el TS original
 * Usa `LocalDate.plusMonths`, que RECORTA al último día válido del mes
 * destino (31 ene + 1 mes = 28/29 feb). El TS original usaba `Date.setMonth`,
 * que en JavaScript se DESBORDA al mes siguiente (31 ene + 1 mes = 3 mar) —
 * un comportamiento no deseado para una fecha límite. Este port corrige eso
 * a propósito; es una mejora de fondo, no solo una traducción literal.
 */
object PlazoTitulacionCalculator {

    const val MESES_PLAZO_PROYECTO_NO_RES = 12L
    const val MESES_PLAZO_TITULACION_NO_RES = 6L
    const val MARGEN_REZAGO_DIAS_NO_RES = 30L
    const val MESES_PLAZO_RESIDENCIA = 6L

    enum class Estado(val valorApi: String) {
        EN_TIEMPO("en_tiempo"),
        REZAGADO("rezagado"),
        VENCIDO("vencido"),
    }

    data class Vista(
        val estadoGlobal: Estado,
        val lineaProyecto: String,
        val lineaTitulacion: String,
        val fechaLimiteMasCercana: LocalDate?,
        val diasHastaLimiteMasCercano: Long?,
    )

    private data class PlazoDesarrollo(
        val estado: Estado,
        val fechaInicio: LocalDate,
        val fasePostRecepcion: Boolean,
        val mesesTotales: Long,
        val fechaLimite: LocalDate,
        val diasHastaLimite: Long,
        val mesesRestantesCompletos: Long,
        val diasExtraDespuesDeMesesCompletos: Long,
    )

    /** Widget "cuadro verde" del paso 4 (desarrollo) en seguimiento del egresado/coordinador. */
    data class CuadroDesarrollo(
        val estado: Estado,
        val etiquetaEstado: String,
        val claseSemaforo: String,
        val fechaInicioTexto: String,
        val mesesTotales: Long,
        val fasePostRecepcion: Boolean,
        val fechaLimiteTexto: String,
        /** Resumen técnico (vista coordinador). */
        val textoMesesRestantes: String,
        /** Una línea: cuánto falta hasta el límite (vista alumno). */
        val textoTiempoRestanteCorto: String,
    )

    /** Punto de entrada único: [esResidencia] ya resuelto por el caller (necesita el catálogo). */
    fun vista(
        p: ProcesoTitulacion,
        esResidencia: Boolean,
        hoy: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Vista = if (esResidencia) vistaResidencia(p, hoy, zone) else vistaNoResidencia(p, hoy, zone)

    // ── Helpers de fecha/estado (equivalentes a los de ambos archivos TS) ────

    private fun Instant.aFechaLocal(zone: ZoneId): LocalDate = this.atZone(zone).toLocalDate()

    private fun diffDias(fin: LocalDate, inicio: LocalDate): Long = ChronoUnit.DAYS.between(inicio, fin)

    private fun estadoDesdeDias(diasRest: Long): Estado = when {
        diasRest < 0 -> Estado.VENCIDO
        diasRest <= MARGEN_REZAGO_DIAS_NO_RES -> Estado.REZAGADO
        else -> Estado.EN_TIEMPO
    }

    private fun mesesRestantesTexto(diasRest: Long, maxMeses: Long): String {
        if (diasRest < 0) {
            val m = ceil(-diasRest / 30.4375).toLong()
            return "plazo vencido (aprox. $m mes(es) de retraso)"
        }
        val m = min(maxMeses, max(0L, ceil(diasRest / 30.4375).toLong()))
        return "$m mes(es) restantes de $maxMeses"
    }

    // ── Residencia: 6 meses desde Anexo XXXI ──────────────────────────────────

    private fun vistaResidencia(p: ProcesoTitulacion, hoy: LocalDate, zone: ZoneId): Vista {
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) {
            return Vista(Estado.EN_TIEMPO, "Residencia: proceso concluido en el sistema.", "", null, null)
        }
        // p.fechaCreacion siempre existe (default Instant.now() en el dominio) — a diferencia
        // del TS, aquí no hay caso real de "sin fecha de inicio para el plazo".
        val inicio = (p.documentos.anexo_xxxi.fecha_registro ?: p.fechaCreacion).aFechaLocal(zone)
        val fin = inicio.plusMonths(MESES_PLAZO_RESIDENCIA)
        val dias = diffDias(fin, hoy)
        return Vista(
            estadoGlobal = estadoDesdeDias(dias),
            lineaProyecto = "Residencia: ${mesesRestantesTexto(dias, MESES_PLAZO_RESIDENCIA)} (desde Anexo XXXI).",
            lineaTitulacion = "",
            fechaLimiteMasCercana = fin,
            diasHastaLimiteMasCercano = dias,
        )
    }

    // ── No residencia: flujo de 7 pasos (tesis) vs. legacy ───────────────────

    private fun esFlujo7Pasos(p: ProcesoTitulacion): Boolean =
        p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null ||
            p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null

    private fun vistaNoResidencia(p: ProcesoTitulacion, hoy: LocalDate, zone: ZoneId): Vista =
        if (esFlujo7Pasos(p)) vistaFlujo7(p, hoy, zone) else vistaLegacy(p, hoy, zone)

    /**
     * Plazo de desarrollo del flujo de 7 pasos: 12 meses desde confirmación DEP paso 3
     * (o desde expedición de constancia de no inconveniencia si es curso de titulación);
     * al liberar producto o recibir el trabajo en división, cambia a 6 meses (fase post-recepción).
     */
    private fun plazoDesarrollo(p: ProcesoTitulacion, hoy: LocalDate, zone: ZoneId): PlazoDesarrollo? {
        val finDesarrolloInstant = p.fechaSolicitudRegistroLiberacionDeptoAcademico
            ?: p.fechaRecepcionTrabajoDivisionEstudiosProf
        val fechaInicio: LocalDate
        val mesesTotales: Long
        val fasePostRecepcion: Boolean
        if (finDesarrolloInstant != null) {
            fechaInicio = finDesarrolloInstant.aFechaLocal(zone)
            mesesTotales = MESES_PLAZO_TITULACION_NO_RES
            fasePostRecepcion = true
        } else {
            val esCurso = p.datos_proyecto.curso_titulacion.equals("si", ignoreCase = true)
            val inicioInstant = (
                if (esCurso) p.documentos.constancia_no_inconveniencia.fecha_expedicion
                else p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii
                ) ?: return null
            fechaInicio = inicioInstant.aFechaLocal(zone)
            mesesTotales = MESES_PLAZO_PROYECTO_NO_RES
            fasePostRecepcion = false
        }
        val fechaLimite = fechaInicio.plusMonths(mesesTotales)
        val diasHastaLimite = diffDias(fechaLimite, hoy)

        // Desglose "N meses y M días restantes" contando meses calendario completos desde hoy.
        var mesesRestantesCompletos = 0L
        var diasExtra = 0L
        if (diasHastaLimite >= 0) {
            while (hoy.plusMonths(mesesRestantesCompletos + 1) <= fechaLimite) {
                mesesRestantesCompletos++
            }
            val fechaTrasMeses = hoy.plusMonths(mesesRestantesCompletos)
            diasExtra = max(0L, diffDias(fechaLimite, fechaTrasMeses))
        }

        return PlazoDesarrollo(
            estado = estadoDesdeDias(diasHastaLimite),
            fechaInicio = fechaInicio,
            fasePostRecepcion = fasePostRecepcion,
            mesesTotales = mesesTotales,
            fechaLimite = fechaLimite,
            diasHastaLimite = diasHastaLimite,
            mesesRestantesCompletos = mesesRestantesCompletos,
            diasExtraDespuesDeMesesCompletos = diasExtra,
        )
    }

    /**
     * Widget "cuadro verde" del paso 4 (desarrollo, no residencia flujo 7 pasos). Distinto de
     * [vistaFlujo7]: solo aplica ANTES de la liberación de producto (una vez liberado, deja de
     * mostrarse — la fase post-recepción de 6 meses se ve en la vista general, no en este cuadro).
     */
    fun cuadroDesarrollo(p: ProcesoTitulacion, hoy: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): CuadroDesarrollo? {
        if (p.fechaSolicitudRegistroLiberacionDeptoAcademico != null) return null
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii == null) return null
        if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return null
        val d = plazoDesarrollo(p, hoy, zone) ?: return null
        val claseSemaforo = when (d.estado) {
            Estado.VENCIDO -> "plazo-desarrollo-rec--bad"
            Estado.REZAGADO -> "plazo-desarrollo-rec--warn"
            Estado.EN_TIEMPO -> "plazo-desarrollo-rec--ok"
        }
        return CuadroDesarrollo(
            estado = d.estado,
            etiquetaEstado = etiquetaEstadoPlazo(d.estado),
            claseSemaforo = claseSemaforo,
            fechaInicioTexto = formatearFechaDdMmYyyy(d.fechaInicio),
            mesesTotales = d.mesesTotales,
            fasePostRecepcion = d.fasePostRecepcion,
            fechaLimiteTexto = formatearFechaDdMmYyyy(d.fechaLimite),
            textoMesesRestantes = descripcionMesesRestantes(d),
            textoTiempoRestanteCorto = textoTiempoRestanteCorto(d),
        )
    }

    private fun etiquetaEstadoPlazo(e: Estado): String = when (e) {
        Estado.VENCIDO -> "Vencido"
        Estado.REZAGADO -> "Rezagado"
        Estado.EN_TIEMPO -> "En tiempo"
    }

    private fun formatearFechaDdMmYyyy(d: LocalDate): String =
        "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year)

    private fun descripcionMesesRestantes(v: PlazoDesarrollo): String {
        if (v.diasHastaLimite < 0) {
            return "Plazo vencido (${-v.diasHastaLimite} día(s) de retraso respecto al límite del periodo)."
        }
        val partes = mutableListOf<String>()
        if (v.mesesRestantesCompletos > 0) {
            partes.add("${v.mesesRestantesCompletos} mes${if (v.mesesRestantesCompletos != 1L) "es" else ""}")
        }
        if (v.diasExtraDespuesDeMesesCompletos > 0) {
            partes.add("${v.diasExtraDespuesDeMesesCompletos} día${if (v.diasExtraDespuesDeMesesCompletos != 1L) "s" else ""}")
        }
        if (partes.isEmpty()) {
            return "Último día del plazo (0 meses restantes en conteo por meses calendario)."
        }
        val mesesLabel = "${v.mesesTotales} mes${if (v.mesesTotales != 1L) "es" else ""}"
        return "${partes.joinToString(" y ")} restantes hasta el límite del periodo ($mesesLabel calendario desde la fecha de inicio)."
    }

    private fun textoTiempoRestanteCorto(v: PlazoDesarrollo): String {
        val lim = formatearFechaDdMmYyyy(v.fechaLimite)
        if (v.diasHastaLimite < 0) {
            return "Plazo vencido: el límite era el $lim (llevas ${-v.diasHastaLimite} día(s) de retraso)."
        }
        val partes = mutableListOf<String>()
        if (v.mesesRestantesCompletos > 0) {
            partes.add("${v.mesesRestantesCompletos} mes${if (v.mesesRestantesCompletos != 1L) "es" else ""}")
        }
        if (v.diasExtraDespuesDeMesesCompletos > 0) {
            partes.add("${v.diasExtraDespuesDeMesesCompletos} día${if (v.diasExtraDespuesDeMesesCompletos != 1L) "s" else ""}")
        }
        val tiempo = if (partes.isNotEmpty()) partes.joinToString(" y ") else "menos de un día"
        return "Te falta: $tiempo para llegar al límite ($lim)."
    }

    private fun vistaFlujo7(p: ProcesoTitulacion, hoy: LocalDate, zone: ZoneId): Vista {
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) {
            return Vista(Estado.EN_TIEMPO, "Proceso concluido en el sistema.", "", null, null)
        }
        if (p.fechaEnviadoDepartamentoAcademico != null) {
            return Vista(
                Estado.EN_TIEMPO,
                "Desarrollo y liberación concluidos; expediente en revisión (Apoyo a Titulación).",
                "", null, null,
            )
        }
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii == null) {
            return Vista(
                Estado.EN_TIEMPO,
                "Pendiente que la DEP confirme el registro de la tesis (paso 3) para iniciar el plazo de desarrollo.",
                "", null, null,
            )
        }
        val desarrollo = plazoDesarrollo(p, hoy, zone)
            ?: return Vista(Estado.EN_TIEMPO, "Plazo de desarrollo no aplica en esta etapa.", "", null, null)
        val linea = if (desarrollo.fasePostRecepcion) {
            "Trámite tras liberación: ${mesesRestantesTexto(desarrollo.diasHastaLimite, MESES_PLAZO_TITULACION_NO_RES)} " +
                "($MESES_PLAZO_TITULACION_NO_RES meses desde liberación en departamento)."
        } else {
            "Desarrollo de tesis: ${mesesRestantesTexto(desarrollo.diasHastaLimite, desarrollo.mesesTotales)} " +
                "($MESES_PLAZO_PROYECTO_NO_RES meses desde confirmación DEP paso 3)."
        }
        return Vista(desarrollo.estado, linea, "", desarrollo.fechaLimite, desarrollo.diasHastaLimite)
    }

    /** Expedientes antiguos (sin flujo 7): dos sub-plazos independientes, proyecto y titulación. */
    private fun vistaLegacy(p: ProcesoTitulacion, hoy: LocalDate, zone: ZoneId): Vista {
        val confAnexosInstant = p.fechaConfirmacionRecibidosAnexoXxxiXxxii
        val proyectoConcluido = confAnexosInstant != null
        val titulacionConcluido = p.fechaConfirmacionDocumentacionEscaneadaRecibida != null

        val lineaProyecto: String
        var estPro: Estado? = null
        var finPro: LocalDate? = null
        var diasPro: Long? = null
        if (proyectoConcluido) {
            lineaProyecto = "Proyecto: etapa concluida (plazo de $MESES_PLAZO_PROYECTO_NO_RES meses superado)."
        } else {
            // p.fechaCreacion siempre existe: a diferencia del TS, no hay caso "sin fecha".
            val inicioPro = (p.fechaEnviadoDepartamentoAcademico ?: p.fechaCreacion).aFechaLocal(zone)
            finPro = inicioPro.plusMonths(MESES_PLAZO_PROYECTO_NO_RES)
            diasPro = diffDias(finPro, hoy)
            estPro = estadoDesdeDias(diasPro)
            lineaProyecto = "Proyecto: ${mesesRestantesTexto(diasPro, MESES_PLAZO_PROYECTO_NO_RES)}."
        }

        val lineaTitulacion: String
        var estTit: Estado? = null
        var finTit: LocalDate? = null
        var diasTit: Long? = null
        if (titulacionConcluido) {
            lineaTitulacion = "Proceso de titulación: concluido en el sistema."
        } else if (confAnexosInstant == null) {
            lineaTitulacion = "Titulación: inicia al confirmar anexos XXXII/XXXIII ($MESES_PLAZO_TITULACION_NO_RES meses)."
        } else {
            val confAnexos = confAnexosInstant.aFechaLocal(zone)
            finTit = confAnexos.plusMonths(MESES_PLAZO_TITULACION_NO_RES)
            diasTit = diffDias(finTit, hoy)
            estTit = estadoDesdeDias(diasTit)
            lineaTitulacion = "Titulación: ${mesesRestantesTexto(diasTit, MESES_PLAZO_TITULACION_NO_RES)}."
        }

        val rank = mapOf(Estado.VENCIDO to 0, Estado.REZAGADO to 1, Estado.EN_TIEMPO to 2)
        var estadoGlobal = Estado.EN_TIEMPO
        for (est in listOf(estPro, estTit)) {
            if (est != null && rank.getValue(est) < rank.getValue(estadoGlobal)) estadoGlobal = est
        }
        // Nota: el TS original tenía acá un tercer ajuste ("if (proyectoConcluido && !confAnexos)
        // estadoGlobal = estPro ?? 'en_tiempo'") que es código muerto — proyectoConcluido SE
        // DEFINE como `!!confAnexos`, así que esa condición nunca puede darse. Se omite en el
        // port (limpieza real, no solo traducción; no cambia el comportamiento observable).

        val candidatos = buildList {
            if (!proyectoConcluido && finPro != null && diasPro != null) add(finPro to diasPro)
            if (!titulacionConcluido && confAnexosInstant != null && finTit != null && diasTit != null) add(finTit to diasTit)
        }
        val masCercano = candidatos.minByOrNull { it.second }

        return Vista(estadoGlobal, lineaProyecto, lineaTitulacion, masCercano?.first, masCercano?.second)
    }
}
