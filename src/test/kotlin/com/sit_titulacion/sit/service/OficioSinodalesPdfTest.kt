package com.sit_titulacion.sit.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OficioSinodalesPdfTest {

    private val pdfService = HtmlAnexoPdfService()

    private val valoresBase = mapOf(
        "NOMBRE" to "MANUEL ALBERTO ROJAS",
        "NUMERO_CONTROL" to "8080",
        "CARRERA" to "INGENIERIA INFORMÁTICA",
        "NIVEL" to "LICENCIATURA",
        "PROYECTO" to "CONFIGURACIONES DE DATOS",
        "EXPEDIENTE" to "SIT-E566-EE14-1474",
        "OFICIO_NUMERO" to "DEA/583/2026",
        "LUGAR" to "Nazareno, Xoxocotlán, Oaxaca",
        "FECHA_OFICIO" to "20/mayo/2026",
        "ASUNTO" to "Vocal y vocal suplente",
        "JEFE_DIVISION_NOMBRE" to "MANUEL FABIAN ROJAS",
        "JEFE_DIVISION_CARGO" to "JEFE DE LA DIVISIÓN DE ESTUDIOS PROFESIONALES",
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
    fun generaPdfOficioSinodalesConPlantilla() {
        val bytes = generarPdf(valoresBase)
        assertNotNull(bytes)
        assertTrue(bytes!!.size > 500)
        assertTrue(bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte())
    }

    @Test
    fun acentosCorrectosSinMojibake() {
        val mojibake = String(
            "Instituto Tecnológico del Valle de Oaxaca".toByteArray(Charsets.UTF_8),
            Charsets.ISO_8859_1,
        )
        val valores = valoresBase + mapOf(
            "NOMBRE_INSTITUTO" to mojibake,
            "LUGAR" to String(
                "Nazareno, Xoxocotlán, Oaxaca".toByteArray(Charsets.UTF_8),
                Charsets.ISO_8859_1,
            ),
            "JEFA_DEPARTAMENTO_NOMBRE" to String(
                "MARIANA DÍAZ JARQUÍN".toByteArray(Charsets.UTF_8),
                Charsets.ISO_8859_1,
            ),
            "JEFE_DIVISION_CARGO" to String(
                "JEFE DE LA DIVISIÓN DE ESTUDIOS PROFESIONALES".toByteArray(Charsets.UTF_8),
                Charsets.ISO_8859_1,
            ),
        )
        val texto = extraerTextoPdf(generarPdf(valores)!!)
        assertFalse(texto.contains("Ã"), "PDF con mojibake: $texto")
        assertTrue(texto.contains("TECNOL") && texto.contains("GICO"))
        assertTrue(texto.contains("JARQU"))
    }

    @Test
    fun normalizarTextoUtf8ReparaMojibake() {
        val roto = String("Tecnológico".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertTrue(pdfService.normalizarTextoUtf8(roto).contains("ó"))
    }

    @Test
    fun cabeEnUnaSolaHoja() {
        val bytes = generarPdf(valoresBase)!!
        PDDocument.load(bytes).use { doc ->
            assertTrue(doc.numberOfPages == 1, "El oficio debe ser de 1 página, tiene ${doc.numberOfPages}")
        }
    }

    private fun generarPdf(valores: Map<String, String>): ByteArray? =
        pdfService.generarDesdeClasspath(
            "templates/html/oficio-sinodales/oficio-asignacion-sinodales.html",
            valores,
        )

    private fun extraerTextoPdf(bytes: ByteArray): String =
        PDDocument.load(bytes).use { doc ->
            PDFTextStripper().getText(doc)
        }
}
