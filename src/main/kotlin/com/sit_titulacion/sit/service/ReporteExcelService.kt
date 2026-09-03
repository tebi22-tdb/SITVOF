package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.web.api.dto.ReporteConteoDto
import com.sit_titulacion.sit.web.api.dto.ReporteEgresadoListadoDto
import com.sit_titulacion.sit.web.api.dto.ReporteResumenCoordinadorDto
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

/**
 * Exporta lo que ve el coordinador en pantalla (`ReportesComponent`) a
 * libros de Excel — dos documentos independientes, no uno solo, porque son
 * consultas de naturaleza distinta: [generar] es el resumen de KPIs (una
 * hoja), [generarListado] es el directorio de egresados (potencialmente
 * cientos de filas, sin sentido mezclarlo con el libro de KPIs). Sin
 * gráficas embebidas a propósito: el archivo es para acreditación/SEP,
 * tablas limpias es lo esperado — las gráficas quedan solo en pantalla.
 *
 * Usa Apache POI (`org.apache.poi:poi-ooxml`), ya declarado en el proyecto
 * para generar `.docx` en [EmailService] — aquí se usa la parte de hojas de
 * cálculo (`XSSFWorkbook`) de la misma librería, sin dependencia nueva.
 */
@Service
class ReporteExcelService {

    fun generar(general: ReporteResumenCoordinadorDto): ByteArray {
        XSSFWorkbook().use { libro ->
            val encabezado = estiloEncabezado(libro)
            hojaGeneral(libro.createSheet("Resumen general"), general, encabezado)

            val salida = ByteArrayOutputStream()
            libro.write(salida)
            return salida.toByteArray()
        }
    }

    fun generarListado(items: List<ReporteEgresadoListadoDto>): ByteArray {
        XSSFWorkbook().use { libro ->
            val encabezado = estiloEncabezado(libro)
            val hoja = libro.createSheet("Listado de egresados")
            var fila = escribirFila(
                hoja, 0, encabezado,
                listOf(
                    "Nombre", "Número de control", "Carrera", "Departamento",
                    "Generación de ingreso", "Modalidad", "Estado", "Estado de plazo", "Fecha de titulación",
                ),
            )
            for (item in items) {
                fila = escribirFila(
                    hoja, fila, null,
                    listOf(
                        item.nombre_completo,
                        item.numero_control,
                        item.carrera,
                        item.departamento_nombre ?: "—",
                        item.generacion_ingreso?.toString() ?: "—",
                        item.modalidad,
                        item.estado_general,
                        item.estado_plazo,
                        item.fecha_titulacion ?: "—",
                    ),
                )
            }
            autoajustar(hoja, 9)

            val salida = ByteArrayOutputStream()
            libro.write(salida)
            return salida.toByteArray()
        }
    }

    private fun estiloEncabezado(libro: XSSFWorkbook): CellStyle {
        val fuente = libro.createFont().apply { bold = true }
        return libro.createCellStyle().apply { setFont(fuente) }
    }

    private fun hojaGeneral(hoja: Sheet, r: ReporteResumenCoordinadorDto, encabezado: CellStyle) {
        var fila = escribirFila(hoja, 0, encabezado, listOf("Indicador", "Valor"))
        fila = escribirFila(hoja, fila, null, listOf("Total de procesos", r.total_procesos.toString()))
        for ((estado, cantidad) in r.conteo_por_estado_plazo) {
            fila = escribirFila(hoja, fila, null, listOf("Estado de plazo: $estado", cantidad.toString()))
        }
        fila = escribirFila(
            hoja, fila, null,
            listOf(
                "Tasa de titulación",
                "${r.tasa_titulacion.titulados}/${r.tasa_titulacion.total_procesos} " +
                    "(${"%.1f".format(r.tasa_titulacion.porcentaje)}%)",
            ),
        )
        fila = escribirFila(
            hoja, fila, null,
            listOf(
                "Aprobación en 1ª revisión",
                "${r.tasa_aprobacion_primera_revision.aprobadas_en_primera_revision}/" +
                    "${r.tasa_aprobacion_primera_revision.total_primeras_revisiones} " +
                    "(${"%.1f".format(r.tasa_aprobacion_primera_revision.porcentaje)}%)",
            ),
        )

        fila = escribirTablaConteo(hoja, fila + 1, "Modalidad", r.distribucion_modalidad, encabezado)
        fila = escribirTablaConteo(hoja, fila + 1, "Carrera", r.distribucion_carrera, encabezado)

        fila = escribirFila(hoja, fila + 1, encabezado, listOf("Etapa", "Días promedio", "Muestra"))
        for (item in r.tiempos_promedio_etapa_dias) {
            fila = escribirFila(
                hoja, fila, null,
                listOf(item.etiqueta, item.dias_promedio?.let { "%.1f".format(it) } ?: "—", item.muestra.toString()),
            )
        }

        fila = escribirFila(hoja, fila + 1, encabezado, listOf("Mes", "Procesos registrados"))
        for (item in r.tendencia_mensual) {
            fila = escribirFila(hoja, fila, null, listOf(item.etiqueta, item.cantidad.toString()))
        }

        fila = escribirFila(hoja, fila + 1, encabezado, listOf("Hito", "Procesos que lo alcanzaron"))
        for (item in r.embudo_etapas) {
            fila = escribirFila(hoja, fila, null, listOf(item.etiqueta, item.cantidad.toString()))
        }

        autoajustar(hoja, 3)
    }

    private fun escribirTablaConteo(
        hoja: Sheet,
        desde: Int,
        etiquetaColumna: String,
        items: List<ReporteConteoDto>,
        encabezado: CellStyle,
    ): Int {
        var fila = escribirFila(hoja, desde, encabezado, listOf(etiquetaColumna, "Cantidad", "Porcentaje"))
        for (item in items) {
            fila = escribirFila(hoja, fila, null, listOf(item.etiqueta, item.cantidad.toString(), "%.1f%%".format(item.porcentaje)))
        }
        return fila
    }

    /** Escribe una fila y regresa el índice de la siguiente. */
    private fun escribirFila(hoja: Sheet, indice: Int, estilo: CellStyle?, valores: List<String>): Int {
        val fila: Row = hoja.createRow(indice)
        valores.forEachIndexed { i, valor ->
            val celda: Cell = fila.createCell(i)
            celda.setCellValue(valor)
            if (estilo != null) celda.cellStyle = estilo
        }
        return indice + 1
    }

    private fun autoajustar(hoja: Sheet, columnas: Int) {
        for (i in 0 until columnas) hoja.autoSizeColumn(i)
    }
}
