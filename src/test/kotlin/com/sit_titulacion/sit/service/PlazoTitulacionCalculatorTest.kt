package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.AnexoXxxi
import com.sit_titulacion.sit.domain.ConstanciaNoInconveniencia
import com.sit_titulacion.sit.domain.DatosProyecto
import com.sit_titulacion.sit.domain.Documentos
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests de paridad con la lógica original en TypeScript
 * (`plazos-titulacion-no-residencia.ts` + `plazo-desarrollo-proyecto-no-res.ts`),
 * antes de que el frontend deje de recalcular y empiece a pintar lo que
 * devuelve este objeto. Cada caso reproduce un escenario real de los 3
 * sub-flujos (residencia, no-residencia flujo 7 pasos, no-residencia legacy).
 */
class PlazoTitulacionCalculatorTest {

    private val zone = ZoneId.of("America/Mexico_City")
    private fun fecha(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant()
    private fun proceso(modalidad: String = "Tesis", cursoTitulacion: String = "no") =
        ProcesoTitulacion(datos_proyecto = DatosProyecto(nombre_proyecto = "x", modalidad = modalidad, curso_titulacion = cursoTitulacion))

    // ── Residencia ────────────────────────────────────────────────────────

    @Test
    fun residencia_enTiempo_bienDentroDelPlazo() {
        val hoy = LocalDate.of(2026, 1, 15)
        val p = proceso("Residencia Profesional").copy(
            documentos = Documentos(anexo_xxxi = AnexoXxxi(fecha_registro = fecha(2025, 12, 1))),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = true, hoy = hoy, zone = zone)
        // 1 dic 2025 + 6 meses = 1 jun 2026; hoy 15 ene 2026 -> muy dentro del plazo.
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
        assertEquals(LocalDate.of(2026, 6, 1), v.fechaLimiteMasCercana)
    }

    @Test
    fun residencia_rezagado_dentroDelMargenDe30Dias() {
        val hoy = LocalDate.of(2026, 5, 15) // límite 1 jun 2026 -> 17 días restantes (<=30)
        val p = proceso("Residencia Profesional").copy(
            documentos = Documentos(anexo_xxxi = AnexoXxxi(fecha_registro = fecha(2025, 12, 1))),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = true, hoy = hoy, zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.REZAGADO, v.estadoGlobal)
        assertEquals(17L, v.diasHastaLimiteMasCercano)
    }

    @Test
    fun residencia_vencido_pasadoElLimite() {
        val hoy = LocalDate.of(2026, 6, 2)
        val p = proceso("Residencia Profesional").copy(
            documentos = Documentos(anexo_xxxi = AnexoXxxi(fecha_registro = fecha(2025, 12, 1))),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = true, hoy = hoy, zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.VENCIDO, v.estadoGlobal)
    }

    @Test
    fun residencia_concluido_documentacionEscaneadaRecibida() {
        val p = proceso("Residencia Profesional").copy(
            fechaConfirmacionDocumentacionEscaneadaRecibida = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = true, hoy = LocalDate.of(2027, 1, 1), zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
        assertNull(v.fechaLimiteMasCercana)
    }

    @Test
    fun residencia_finDeMes_recortaEnVezDeDesbordar() {
        // 31 mar + 6 meses: LocalDate.plusMonths recorta al 30 sep (septiembre tiene 30 días).
        // El TS original (Date.setMonth) se hubiera desbordado al 1 oct — justo la diferencia
        // de comportamiento documentada en PlazoTitulacionCalculator.
        val hoy = LocalDate.of(2025, 4, 15)
        val p = proceso("Residencia Profesional").copy(
            documentos = Documentos(anexo_xxxi = AnexoXxxi(fecha_registro = fecha(2025, 3, 31))),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = true, hoy = hoy, zone = zone)
        assertEquals(LocalDate.of(2025, 9, 30), v.fechaLimiteMasCercana)
    }

    // ── No residencia: flujo 7 pasos ─────────────────────────────────────

    @Test
    fun flujo7_pendienteConfirmacionDepPaso3() {
        val p = proceso().copy(fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2026, 1, 1))
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = LocalDate.of(2026, 1, 10), zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
        assertNull(v.fechaLimiteMasCercana)
    }

    @Test
    fun flujo7_desarrolloEnTiempo_12mesesDesdeConfirmacionDep() {
        val hoy = LocalDate.of(2026, 3, 1)
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 12, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        // 1 ene 2026 + 12 meses = 1 ene 2027.
        assertEquals(LocalDate.of(2027, 1, 1), v.fechaLimiteMasCercana)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
    }

    @Test
    fun flujo7_cursoTitulacion_diaUnoEsFechaDeConstancia() {
        val hoy = LocalDate.of(2026, 3, 1)
        val p = proceso(cursoTitulacion = "si").copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 12, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1),
            documentos = Documentos(constancia_no_inconveniencia = ConstanciaNoInconveniencia(fecha_expedicion = fecha(2026, 2, 1))),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        // Debe usar la constancia (1 feb 2026), NO la confirmación DEP (1 ene 2026).
        assertEquals(LocalDate.of(2027, 2, 1), v.fechaLimiteMasCercana)
    }

    @Test
    fun flujo7_fasePostRecepcion_porLiberacionProducto_6meses() {
        val hoy = LocalDate.of(2026, 3, 1)
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 1, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2025, 1, 1),
            fechaSolicitudRegistroLiberacionDeptoAcademico = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        // Liberación (1 ene 2026) tiene prioridad sobre la fecha de confirmación DEP (irrelevante aquí);
        // fase post-recepción = 6 meses -> límite 1 jul 2026.
        assertEquals(LocalDate.of(2026, 7, 1), v.fechaLimiteMasCercana)
    }

    @Test
    fun flujo7_yaEnviadoADepartamento_sinLimite() {
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 1, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2025, 1, 1),
            fechaSolicitudRegistroLiberacionDeptoAcademico = fecha(2025, 6, 1),
            fechaEnviadoDepartamentoAcademico = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = LocalDate.of(2027, 1, 1), zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
        assertNull(v.fechaLimiteMasCercana)
    }

    @Test
    fun flujo7_vencido_pasadoElLimiteDeDesarrollo() {
        val hoy = LocalDate.of(2027, 2, 1)
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 12, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.VENCIDO, v.estadoGlobal)
    }

    // ── No residencia: legacy (sin flujo 7) ──────────────────────────────

    @Test
    fun legacy_soloProyectoEnCurso_titulacionAunNoAplica() {
        val hoy = LocalDate.of(2026, 3, 1)
        val p = proceso().copy(fechaEnviadoDepartamentoAcademico = fecha(2026, 1, 1))
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        // Proyecto: 1 ene 2026 + 12 meses = 1 ene 2027. Titulación: no aplica aún (sin fecha).
        assertEquals(LocalDate.of(2027, 1, 1), v.fechaLimiteMasCercana)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
    }

    @Test
    fun legacy_proyectoConcluido_titulacionEnCurso() {
        val hoy = LocalDate.of(2026, 3, 1)
        val p = proceso().copy(
            fechaEnviadoDepartamentoAcademico = fecha(2025, 1, 1),
            fechaConfirmacionRecibidosAnexoXxxiXxxii = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        // Proyecto concluido (no aporta candidato); titulación: 1 ene 2026 + 6 meses = 1 jul 2026.
        assertEquals(LocalDate.of(2026, 7, 1), v.fechaLimiteMasCercana)
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, v.estadoGlobal)
    }

    @Test
    fun legacy_estadoGlobalEsElPeorDeLosDosSubPlazos() {
        val hoy = LocalDate.of(2026, 6, 25) // proyecto: 25 días restantes (rezagado); titulación: sin iniciar
        val p = proceso().copy(fechaEnviadoDepartamentoAcademico = fecha(2025, 6, 25))
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        assertEquals(PlazoTitulacionCalculator.Estado.REZAGADO, v.estadoGlobal)
    }

    @Test
    fun legacy_eligeElCandidatoConMenosDiasRestantes() {
        val hoy = LocalDate.of(2026, 6, 1)
        val p = proceso().copy(
            // Proyecto: 1 ene 2026 + 12m = 1 ene 2027 (muchos días restantes).
            fechaEnviadoDepartamentoAcademico = fecha(2026, 1, 1),
            // Titulación: 1 may 2026 + 6m = 1 nov 2026 (menos días restantes que el proyecto).
            fechaConfirmacionRecibidosAnexoXxxiXxxii = fecha(2026, 5, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = hoy, zone = zone)
        assertEquals(LocalDate.of(2026, 11, 1), v.fechaLimiteMasCercana)
    }

    @Test
    fun legacy_ambosConcluidos_sinCandidatos() {
        val p = proceso().copy(
            fechaEnviadoDepartamentoAcademico = fecha(2025, 1, 1),
            fechaConfirmacionRecibidosAnexoXxxiXxxii = fecha(2025, 6, 1),
            fechaConfirmacionDocumentacionEscaneadaRecibida = fecha(2026, 1, 1),
        )
        val v = PlazoTitulacionCalculator.vista(p, esResidencia = false, hoy = LocalDate.of(2027, 1, 1), zone = zone)
        assertNull(v.fechaLimiteMasCercana)
        assertNull(v.diasHastaLimiteMasCercano)
    }

    // ── Cuadro "desarrollo" (widget del paso 4) ──────────────────────────

    @Test
    fun cuadroDesarrollo_nullSinEnvioAnteproyecto() {
        val p = proceso().copy(fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1))
        assertNull(PlazoTitulacionCalculator.cuadroDesarrollo(p, LocalDate.of(2026, 2, 1), zone))
    }

    @Test
    fun cuadroDesarrollo_nullSinConfirmacionDep() {
        val p = proceso().copy(fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2026, 1, 1))
        assertNull(PlazoTitulacionCalculator.cuadroDesarrollo(p, LocalDate.of(2026, 2, 1), zone))
    }

    @Test
    fun cuadroDesarrollo_nullTrasLiberacionDeProducto() {
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 1, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2025, 1, 1),
            fechaSolicitudRegistroLiberacionDeptoAcademico = fecha(2026, 1, 1),
        )
        // Ya liberado -> este cuadro deja de aplicar (la vista general sí lo muestra, este widget no).
        assertNull(PlazoTitulacionCalculator.cuadroDesarrollo(p, LocalDate.of(2026, 2, 1), zone))
    }

    @Test
    fun cuadroDesarrollo_enTiempo_desgloseDeMesesYDias() {
        val hoy = LocalDate.of(2026, 2, 15)
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 12, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1),
        )
        val c = PlazoTitulacionCalculator.cuadroDesarrollo(p, hoy, zone)!!
        assertEquals(PlazoTitulacionCalculator.Estado.EN_TIEMPO, c.estado)
        assertEquals("En tiempo", c.etiquetaEstado)
        assertEquals("plazo-desarrollo-rec--ok", c.claseSemaforo)
        assertEquals("01/01/2026", c.fechaInicioTexto)
        assertEquals("01/01/2027", c.fechaLimiteTexto)
        assertEquals(
            "10 meses y 17 días restantes hasta el límite del periodo (12 meses calendario desde la fecha de inicio).",
            c.textoMesesRestantes,
        )
        assertEquals("Te falta: 10 meses y 17 días para llegar al límite (01/01/2027).", c.textoTiempoRestanteCorto)
    }

    @Test
    fun cuadroDesarrollo_vencido_textosDeRetraso() {
        val hoy = LocalDate.of(2027, 1, 20) // límite 01/01/2027 -> 19 días de retraso
        val p = proceso().copy(
            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = fecha(2025, 12, 1),
            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = fecha(2026, 1, 1),
        )
        val c = PlazoTitulacionCalculator.cuadroDesarrollo(p, hoy, zone)!!
        assertEquals(PlazoTitulacionCalculator.Estado.VENCIDO, c.estado)
        assertEquals("plazo-desarrollo-rec--bad", c.claseSemaforo)
        assertEquals("Plazo vencido (19 día(s) de retraso respecto al límite del periodo).", c.textoMesesRestantes)
        assertEquals("Plazo vencido: el límite era el 01/01/2027 (llevas 19 día(s) de retraso).", c.textoTiempoRestanteCorto)
    }
}
