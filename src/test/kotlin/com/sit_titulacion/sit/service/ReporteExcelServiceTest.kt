package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.web.api.dto.ReporteConteoDto
import com.sit_titulacion.sit.web.api.dto.ReporteEgresadoListadoDto
import com.sit_titulacion.sit.web.api.dto.ReporteEmbudoEtapaDto
import com.sit_titulacion.sit.web.api.dto.ReporteFiltrosAplicadosDto
import com.sit_titulacion.sit.web.api.dto.ReporteResumenCoordinadorDto
import com.sit_titulacion.sit.web.api.dto.ReporteTasaAprobacionDto
import com.sit_titulacion.sit.web.api.dto.ReporteTasaTitulacionDto
import com.sit_titulacion.sit.web.api.dto.ReporteTendenciaMensualDto
import com.sit_titulacion.sit.web.api.dto.ReporteTiempoEtapaDto
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ReporteExcelServiceTest {

    private val servicio = ReporteExcelService()

    private val general = ReporteResumenCoordinadorDto(
        filtros = ReporteFiltrosAplicadosDto(null, null, null, null, null),
        total_procesos = 10,
        conteo_por_estado_general = mapOf("registrado" to 10),
        conteo_por_estado_plazo = mapOf("en_tiempo" to 7, "rezagado" to 3),
        distribucion_modalidad = listOf(ReporteConteoDto("Tesis", 10, 100.0)),
        distribucion_carrera = listOf(ReporteConteoDto("Sistemas", 10, 100.0)),
        tiempos_promedio_etapa_dias = listOf(ReporteTiempoEtapaDto("etapa1", "Etapa 1", 5.0, 4)),
        tasa_aprobacion_primera_revision = ReporteTasaAprobacionDto(4, 3, 75.0),
        tasa_titulacion = ReporteTasaTitulacionDto(10, 4, 40.0),
        tendencia_mensual = listOf(ReporteTendenciaMensualDto("2026-01", "Ene 2026", 10)),
        embudo_etapas = listOf(
            ReporteEmbudoEtapaDto("registrados", "Registrados", 10),
            ReporteEmbudoEtapaDto("titulados", "Titulados", 4),
        ),
    )

    private val listado = listOf(
        ReporteEgresadoListadoDto(
            id = "1", nombre_completo = "Ana Pérez López", numero_control = "220400",
            carrera = "Sistemas", departamento_nombre = "Ingenierías", generacion_ingreso = 2022,
            modalidad = "Tesis", estado_general = "titulado", estado_plazo = "concluido",
            fecha_titulacion = "2026-01-01T00:00:00Z",
        ),
        ReporteEgresadoListadoDto(
            id = "2", nombre_completo = "Beto Ruiz Soto", numero_control = "190123",
            carrera = "Contaduría", departamento_nombre = null, generacion_ingreso = null,
            modalidad = "Residencia Profesional", estado_general = "registrado", estado_plazo = "en_tiempo",
            fecha_titulacion = null,
        ),
    )

    @Test
    fun generar_produceUnaSolaHojaConElResumen() {
        val bytes = servicio.generar(general)

        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { libro ->
            assertEquals(1, libro.numberOfSheets)
            assertTrue(libro.getSheet("Resumen general") != null)
        }
    }

    @Test
    fun generar_libroVacio_noExplota() {
        val vacio = general.copy(
            total_procesos = 0,
            conteo_por_estado_plazo = emptyMap(),
            distribucion_modalidad = emptyList(),
            distribucion_carrera = emptyList(),
            tiempos_promedio_etapa_dias = emptyList(),
            tasa_aprobacion_primera_revision = ReporteTasaAprobacionDto(0, 0, 0.0),
            tasa_titulacion = ReporteTasaTitulacionDto(0, 0, 0.0),
            tendencia_mensual = emptyList(),
            embudo_etapas = emptyList(),
        )
        val bytes = servicio.generar(vacio)

        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { libro ->
            assertEquals(1, libro.numberOfSheets)
        }
    }

    @Test
    fun generarListado_unaFilaPorEgresadoMasEncabezado() {
        val bytes = servicio.generarListado(listado)

        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { libro ->
            assertEquals(1, libro.numberOfSheets)
            val hoja = libro.getSheet("Listado de egresados")
            assertEquals(listado.size, hoja.lastRowNum) // fila 0 = encabezado
            assertEquals("Ana Pérez López", hoja.getRow(1).getCell(0).stringCellValue)
            assertEquals("220400", hoja.getRow(1).getCell(1).stringCellValue)
            // el egresado sin departamento/generación no rompe el libro, sale con "—".
            assertEquals("—", hoja.getRow(2).getCell(3).stringCellValue)
        }
    }

    @Test
    fun generarListado_listaVacia_noExplota() {
        val bytes = servicio.generarListado(emptyList())

        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { libro ->
            assertEquals(1, libro.numberOfSheets)
        }
    }
}
