package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.DatosPersonales
import com.sit_titulacion.sit.domain.DatosProyecto
import com.sit_titulacion.sit.domain.Departamento
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.domain.Revision
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.RevisionRepository
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class ReporteServiceTest {

    @Mock private lateinit var egresadoRepository: EgresadoRepository
    @Mock private lateinit var revisionRepository: RevisionRepository
    @Mock private lateinit var departamentoRepository: DepartamentoRepository
    @Mock private lateinit var egresadoService: EgresadoService

    private fun servicio() = ReporteService(egresadoRepository, revisionRepository, departamentoRepository, egresadoService)

    private fun egresado(
        modalidad: String,
        carrera: String,
        fechaCreacion: Instant,
        id: ObjectId = ObjectId(),
        departamentoId: ObjectId? = null,
    ): Egresado {
        val proceso = ProcesoTitulacion(
            datos_proyecto = DatosProyecto(nombre_proyecto = "x", modalidad = modalidad),
            fechaCreacion = fechaCreacion,
        )
        return Egresado(
            id = id,
            numero_control = "C$id",
            datos_personales = DatosPersonales(
                nombre = "x", apellido_paterno = "y", apellido_materno = "z",
                carrera = carrera, nivel = "Licenciatura", departamentoId = departamentoId,
            ),
            procesos = listOf(proceso),
        )
    }

    /** Stub de clasificarPlazo para cada egresado exacto (Egresado es data class → matching por equals). */
    private fun stubClasificacion(vararg egresados: Egresado, estado: String = "en_tiempo") {
        egresados.forEach { e ->
            lenient().`when`(egresadoService.clasificarPlazo(e, e.procesoActivoOrNull()))
                .thenReturn(EgresadoService.ClasificacionPlazo(estado, null, null, "", ""))
        }
    }

    @Test
    fun cuentaPorEstadoGeneralYDistribucionModalidad() {
        val e1 = egresado("Tesis", "INGENIERIA A", Instant.parse("2026-01-01T00:00:00Z"))
        val e2 = egresado("Tesis", "INGENIERIA B", Instant.parse("2026-02-01T00:00:00Z"))
        val e3 = egresado("Residencia Profesional", "INGENIERIA A", Instant.parse("2026-03-01T00:00:00Z"))
        `when`(egresadoRepository.findAll()).thenReturn(listOf(e1, e2, e3))
        stubClasificacion(e1, e2, e3)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(e1.id!!, e2.id!!, e3.id!!))).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())

        assertEquals(3, r.total_procesos)
        assertEquals(mapOf("registrado" to 3), r.conteo_por_estado_general)
        assertEquals(2, r.distribucion_modalidad.first { it.etiqueta == "Tesis" }.cantidad)
        // 8 etapas — una por bloque/módulo responsable de la cadena real
        // (registro → envío → liberación → confirmación anexos → Anexo 9.1 →
        // Anexo 9.2 → sinodales → acto 9.3 → documentación escaneada), ver
        // Parte F del plan.
        assertEquals(8, r.tiempos_promedio_etapa_dias.size)
        // 6 hitos (no 7): "documentación recibida" y "titulados" son el mismo instante,
        // se colapsan en un solo hito final.
        assertEquals(6, r.embudo_etapas.size)
    }

    @Test
    fun filtraPorRangoDeFechas() {
        val dentro = egresado("Tesis", "A", Instant.parse("2026-06-15T00:00:00Z"))
        val fuera = egresado("Tesis", "A", Instant.parse("2025-01-01T00:00:00Z"))
        `when`(egresadoRepository.findAll()).thenReturn(listOf(dentro, fuera))
        stubClasificacion(dentro)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(dentro.id!!))).thenReturn(emptyList())

        val filtros = ReporteFiltros(
            fechaDesde = Instant.parse("2026-01-01T00:00:00Z"),
            fechaHasta = Instant.parse("2026-12-31T23:59:59Z"),
        )
        val r = servicio().resumenCoordinador(filtros)

        assertEquals(1, r.total_procesos)
    }

    @Test
    fun tiempoPromedioEtapa_soloCuentaProcesosConAmbasFechas() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val conAmbasFechas = egresado("Tesis", "A", base).let {
            it.copy(procesos = it.procesos.map { p ->
                p.copy(fechaEnviadoDepartamentoAcademico = base.plus(10, ChronoUnit.DAYS))
            })
        }
        val soloInicio = egresado("Tesis", "A", base) // sin fechaEnviadoDepartamentoAcademico
        `when`(egresadoRepository.findAll()).thenReturn(listOf(conAmbasFechas, soloInicio))
        stubClasificacion(conAmbasFechas, soloInicio)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(conAmbasFechas.id!!, soloInicio.id!!))).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())
        val etapa = r.tiempos_promedio_etapa_dias.first { it.etapa == "registro_a_envio_departamento" }

        assertEquals(1, etapa.muestra) // el que no tiene ambas fechas no debe contar
        assertEquals(10.0, etapa.dias_promedio)
    }

    @Test
    fun etapaLiberacion_cuentaCualquieraDeLosDosCaminosDeLiberacion() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        // camino simple (no-residencia sin anteproyecto, o residencia vía liberar()).
        val caminoSimple = egresado("Tesis", "A", base).let {
            it.copy(procesos = it.procesos.map { p ->
                p.copy(
                    fechaEnviadoDepartamentoAcademico = base,
                    fechaRecibidoRegistroLiberacion = base.plus(3, ChronoUnit.DAYS),
                )
            })
        }
        // camino extendido (no-residencia con solicitud de registro de anteproyecto).
        val caminoExtendido = egresado("Tesis", "B", base).let {
            it.copy(procesos = it.procesos.map { p ->
                p.copy(
                    fechaEnviadoDepartamentoAcademico = base,
                    fechaLiberacionDocumentoCoordinacionCat = base.plus(5, ChronoUnit.DAYS),
                )
            })
        }
        `when`(egresadoRepository.findAll()).thenReturn(listOf(caminoSimple, caminoExtendido))
        stubClasificacion(caminoSimple, caminoExtendido)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(caminoSimple.id!!, caminoExtendido.id!!))).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())
        val etapa = r.tiempos_promedio_etapa_dias.first { it.etapa == "envio_a_liberacion" }

        // los dos cuentan, aunque cada uno llene un campo de liberación distinto.
        assertEquals(2, etapa.muestra)
        assertEquals(4.0, etapa.dias_promedio) // promedio de 3 y 5 días
    }

    @Test
    fun tendenciaMensual_agrupaPorMesEnOrdenCronologico() {
        val enero = egresado("Tesis", "A", Instant.parse("2026-01-15T00:00:00Z"))
        val eneroOtro = egresado("Tesis", "A", Instant.parse("2026-01-28T00:00:00Z"))
        val marzo = egresado("Tesis", "A", Instant.parse("2026-03-05T00:00:00Z"))
        `when`(egresadoRepository.findAll()).thenReturn(listOf(enero, eneroOtro, marzo))
        stubClasificacion(enero, eneroOtro, marzo)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(enero.id!!, eneroOtro.id!!, marzo.id!!))).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())

        assertEquals(2, r.tendencia_mensual.size) // enero y marzo, sin febrero (no hubo registros)
        assertEquals("Ene 2026", r.tendencia_mensual[0].etiqueta)
        assertEquals(2, r.tendencia_mensual[0].cantidad)
        assertEquals("Mar 2026", r.tendencia_mensual[1].etiqueta)
        assertEquals(1, r.tendencia_mensual[1].cantidad)
    }

    @Test
    fun embudoEtapas_cadaHitoCuentaIndependienteDelAnterior() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        // llega hasta "enviado a depto." pero nunca se le asignaron sinodales.
        val seAtoraEnDepto = egresado("Tesis", "A", base).let {
            it.copy(procesos = it.procesos.map { p -> p.copy(fechaEnviadoDepartamentoAcademico = base.plus(5, ChronoUnit.DAYS)) })
        }
        // caso con dato inconsistente: tiene sinodales asignados pero nunca se marcó el envío a depto.
        val saltaUnHito = egresado("Tesis", "A", base).let {
            it.copy(procesos = it.procesos.map { p -> p.copy(fechaAsignacionSinodales = base.plus(20, ChronoUnit.DAYS)) })
        }
        `when`(egresadoRepository.findAll()).thenReturn(listOf(seAtoraEnDepto, saltaUnHito))
        stubClasificacion(seAtoraEnDepto, saltaUnHito)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(seAtoraEnDepto.id!!, saltaUnHito.id!!))).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())

        assertEquals(2, r.embudo_etapas.first { it.clave == "registrados" }.cantidad)
        assertEquals(1, r.embudo_etapas.first { it.clave == "enviado_departamento" }.cantidad)
        assertEquals(0, r.embudo_etapas.first { it.clave == "sinodales_solicitados" }.cantidad)
        // se cuenta aunque el hito anterior (sinodales_solicitados) esté vacío para este caso — no depende de la cadena.
        assertEquals(1, r.embudo_etapas.first { it.clave == "sinodales_asignados" }.cantidad)
        assertEquals(0, r.embudo_etapas.first { it.clave == "titulados" }.cantidad)
    }

    @Test
    fun tasaAprobacionPrimeraRevision_mezclaAprobadaYConObservaciones() {
        val e1 = egresado("Tesis", "A", Instant.now())
        val e2 = egresado("Tesis", "A", Instant.now())
        `when`(egresadoRepository.findAll()).thenReturn(listOf(e1, e2))
        stubClasificacion(e1, e2)
        val revisiones = listOf(
            Revision(egresadoId = e1.id!!, procesoId = ObjectId(), numeroRevision = 1, resultado = "aprobado"),
            Revision(egresadoId = e2.id!!, procesoId = ObjectId(), numeroRevision = 1, resultado = "observaciones"),
            // segunda revisión de e2, no debe contar como "primera revisión"
            Revision(egresadoId = e2.id!!, procesoId = ObjectId(), numeroRevision = 2, resultado = "aprobado"),
        )
        `when`(revisionRepository.findByEgresadoIdIn(setOf(e1.id!!, e2.id!!))).thenReturn(revisiones)

        val r = servicio().resumenCoordinador(ReporteFiltros())

        assertEquals(2, r.tasa_aprobacion_primera_revision.total_primeras_revisiones)
        assertEquals(1, r.tasa_aprobacion_primera_revision.aprobadas_en_primera_revision)
        assertEquals(50.0, r.tasa_aprobacion_primera_revision.porcentaje)
    }

    @Test
    fun tasaTitulacion_cuentaSoloElProcesoActivoAunqueHayaIntentosPrevios() {
        val titulado = egresado("Tesis", "A", Instant.now()).let {
            it.copy(procesos = it.procesos.map { p -> p.copy(estado_general = "titulado") })
        }
        val registrado = egresado("Tesis", "A", Instant.now())
        // venció con Tesis y reintentó con Residencia Profesional — sigue siendo el MISMO
        // egresado (un solo Egresado, dos elementos en `procesos`) — ver activarNuevoProceso().
        // El proceso activo (el último, ya titulado) es el único que debe contar.
        val ventoLuegoTitulado = egresado("Tesis", "A", Instant.now().minus(200, ChronoUnit.DAYS)).let {
            val intentoVencido = it.procesos[0].copy(estado_general = "vencido")
            val intentoNuevo = it.procesos[0].copy(
                datos_proyecto = DatosProyecto(nombre_proyecto = "y", modalidad = "Residencia Profesional"),
                estado_general = "titulado",
            )
            it.copy(procesos = listOf(intentoVencido, intentoNuevo))
        }
        `when`(egresadoRepository.findAll()).thenReturn(listOf(titulado, registrado, ventoLuegoTitulado))
        stubClasificacion(titulado, registrado, ventoLuegoTitulado)
        `when`(revisionRepository.findByEgresadoIdIn(setOf(titulado.id!!, registrado.id!!, ventoLuegoTitulado.id!!)))
            .thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())

        // 3 egresados en el filtro, 2 titulados — el que vencía y reintentó cuenta UNA
        // sola vez, como titulado, no como vencido y titulado por separado.
        assertEquals(3, r.tasa_titulacion.total_procesos)
        assertEquals(2, r.tasa_titulacion.titulados)
        assertEquals(66.66666666666667, r.tasa_titulacion.porcentaje)
    }

    @Test
    fun sinEgresados_reporteVacioSinExplotar() {
        `when`(egresadoRepository.findAll()).thenReturn(emptyList())

        val r = servicio().resumenCoordinador(ReporteFiltros())

        assertEquals(0, r.tasa_aprobacion_primera_revision.total_primeras_revisiones)
        assertEquals(0.0, r.tasa_aprobacion_primera_revision.porcentaje)
        assertEquals(0, r.total_procesos)
    }

    @Test
    fun parseFecha_invalidaDevuelveNull() {
        assertNull(ReporteService.parseFecha("no-es-una-fecha"))
        assertNull(ReporteService.parseFecha(null))
        assertNull(ReporteService.parseFecha(""))
    }

    // ── Listado de egresados (reemplaza "por departamento"/"servicios escolares") ──

    private fun departamento(nombre: String, orden: Int = 0, id: ObjectId = ObjectId()) =
        Departamento(id = id, nombre = nombre, slug = nombre.lowercase(), orden = orden)

    /** Caso motivador real: "dame la lista de titulados de tal generación para la clausura". */
    @Test
    fun listadoEgresados_filtraPorGeneracionIngresoYEstado_casoTitulados() {
        val titulado2022 = egresado("Tesis", "A", Instant.now()).let {
            it.copy(numero_control = "220400", procesos = it.procesos.map { p -> p.copy(estado_general = "titulado") })
        }
        val registrado2022 = egresado("Tesis", "A", Instant.now()).let { it.copy(numero_control = "220401") }
        val titulado2019 = egresado("Tesis", "A", Instant.now()).let {
            it.copy(numero_control = "190123", procesos = it.procesos.map { p -> p.copy(estado_general = "titulado") })
        }
        `when`(egresadoRepository.findAll()).thenReturn(listOf(titulado2022, registrado2022, titulado2019))
        stubClasificacion(titulado2022, registrado2022, titulado2019)

        val items = servicio().listadoEgresados(
            ReporteFiltros(), generacionIngreso = 2022, departamentoSlug = null, estadoGeneral = "titulado",
        )

        assertEquals(1, items.size)
        assertEquals("220400", items[0].numero_control)
        assertEquals(2022, items[0].generacion_ingreso)
    }

    @Test
    fun listadoEgresados_filtraPorDepartamento() {
        val ingenierias = departamento("Ingenierías")
        val economico = departamento("Económico-Administrativo")
        val e1 = egresado("Tesis", "A", Instant.now(), departamentoId = ingenierias.id)
        val e2 = egresado("Tesis", "B", Instant.now(), departamentoId = economico.id)
        `when`(egresadoRepository.findAll()).thenReturn(listOf(e1, e2))
        `when`(departamentoRepository.findByActivoTrue()).thenReturn(listOf(ingenierias, economico))
        stubClasificacion(e1, e2)

        val items = servicio().listadoEgresados(
            ReporteFiltros(), generacionIngreso = null, departamentoSlug = ingenierias.slug, estadoGeneral = null,
        )

        assertEquals(1, items.size)
        assertEquals("Ingenierías", items[0].departamento_nombre)
    }

    @Test
    fun listadoEgresados_numeroControlSinAnioValido_generacionNullPeroNoSeDescarta() {
        val atipico = egresado("Tesis", "A", Instant.now()).let { it.copy(numero_control = "AB-1234") }
        `when`(egresadoRepository.findAll()).thenReturn(listOf(atipico))
        stubClasificacion(atipico)

        val sinFiltroGeneracion = servicio().listadoEgresados(
            ReporteFiltros(), generacionIngreso = null, departamentoSlug = null, estadoGeneral = null,
        )
        val conFiltroGeneracion = servicio().listadoEgresados(
            ReporteFiltros(), generacionIngreso = 2022, departamentoSlug = null, estadoGeneral = null,
        )

        assertEquals(1, sinFiltroGeneracion.size) // sigue apareciendo en el listado general
        assertNull(sinFiltroGeneracion[0].generacion_ingreso)
        assertTrue(conFiltroGeneracion.isEmpty()) // pero no puede encontrarse por generación
    }
}
