package com.sit_titulacion.sit.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Verifica que los PDFs generados llevan /ViewerPreferences /PrintScaling /None,
 * para que el visor no reescale al imprimir y las posiciones salgan como en digital.
 * Cubre las dos rutas del renderer: fast mode (anexos) y slow mode (oficio sinodales).
 */
class PdfPrintScalingTest {

    private val pdfService = HtmlAnexoPdfService()

    private val valoresAnexo91 = mapOf(
        "NOMBRE" to "MANUEL ALBERTO ROJAS",
        "NUMERO_CONTROL" to "8080",
        "CARRERA" to "INGENIERIA INFORMÁTICA",
        "NIVEL" to "LICENCIATURA",
        "FECHA_CARTA" to "7 de Julio de 2026",
        "TEXTO_OPCION_TI" to "REPORTE FINAL DE RESIDENCIA PROFESIONAL",
        "DESTINATARIO_SERVICIOS_ESCOLARES" to "LIC. EJEMPLO EJEMPLO",
        "CARGO_SERVICIOS_ESCOLARES" to "JEFE DEL DEPARTAMENTO DE SERVICIOS ESCOLARES",
        "QR_CODE" to "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    private val valoresOficio = mapOf(
        "NOMBRE" to "MANUEL ALBERTO ROJAS",
        "NUMERO_CONTROL" to "8080",
        "CARRERA" to "INGENIERIA INFORMÁTICA",
        "FRASE_TEMA_OFICIO" to "con el tema \"CONFIGURACIONES DE DATOS\"",
        "EXPEDIENTE" to "SIT-E566-EE14-1474",
        "OFICIO_NUMERO" to "DEA/583/2026",
        "LUGAR" to "Nazareno, Xoxocotlán, Oaxaca",
        "FECHA_OFICIO" to "20/mayo/2026",
        "ASUNTO" to "Vocal y vocal suplente",
        "JEFE_DIVISION_NOMBRE" to "MANUEL FABIAN ROJAS",
        "JEFE_DIVISION_CARGO" to "JEFE DE LA DIVISIÓN DE ESTUDIOS PROFESIONALES",
        "TRATAMIENTO_EGRESADO" to "del C.",
        "ETIQUETA_PRESIDENTE" to "PRESIDENTA",
        "ETIQUETA_SECRETARIO" to "SECRETARIO",
        "TEXTO_OPCION_TI" to "REPORTE FINAL DE RESIDENCIA PROFESIONAL",
        "PRESIDENTE" to "BETY",
        "SECRETARIO" to "FABIAN",
        "VOCAL" to "MERLIN",
        "VOCAL_SUPLENTE" to "PEDRO",
        "JEFA_DEPARTAMENTO_NOMBRE" to "MARIANA DÍAZ JARQUÍN",
        "JEFA_DEPARTAMENTO_CARGO" to "JEFA DEL DEPARTAMENTO ECONÓMICO-ADMINISTRATIVO",
        "NOMBRE_DEPARTAMENTO" to "Departamento Económico-Administrativo",
        "INICIALES_FIRMA" to "MDJ/mcgl",
        "NOMBRE_INSTITUTO" to "Instituto Tecnológico del Valle de Oaxaca",
    )

    @Test
    fun anexo91LlevaPrintScalingNone() {
        val bytes = pdfService.generarDesdeClasspath("templates/html/anexo-9-1.html", valoresAnexo91)
        assertNotNull(bytes)
        assertPrintScalingNone(bytes!!)
    }

    @Test
    fun oficioSinodalesLlevaPrintScalingNone() {
        val bytes = pdfService.generarDesdeClasspath(
            "templates/html/oficio-sinodales/oficio-asignacion-sinodales.html",
            valoresOficio,
        )
        assertNotNull(bytes)
        assertPrintScalingNone(bytes!!)
    }

    private fun assertPrintScalingNone(bytes: ByteArray) {
        PDDocument.load(bytes).use { doc ->
            val prefs = doc.documentCatalog.viewerPreferences
            assertNotNull(prefs, "El PDF no tiene /ViewerPreferences")
            assertEquals("None", prefs!!.printScaling, "PrintScaling debe ser None")
        }
    }
}
