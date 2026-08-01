package com.sit_titulacion.sit.service

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.PDXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifica que el pie de página reconstruido (logos de encabezado + imagen anual,
 * fijos vía classpath, e insignias dinámicas vía {{INSIGNIAS_HTML}}) realmente
 * incrusta imágenes en el PDF, y no falla silenciosamente si algún nombre de
 * archivo no coincide con el classpath.
 */
class Hoja32FooterPdfTest {

    private val pdfService = HtmlAnexoPdfService()

    /** Simula lo que ConfiguracionInstitucionalService.construirHtmlInsignias() le pasaría
     *  a EgresadoService: una insignia de prueba ya con su tamaño calculado. Debe ser un
     *  PNG distinto al QR_CODE para que no se deduplique al contar XObjects incrustados. */
    private val insigniasHtmlDePrueba =
        """<img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=" style="height:1cm;width:auto;" alt=""/>"""

    private val valores = mapOf(
        "NOMBRE" to "MANUEL ALBERTO ROJAS",
        "NUMERO_CONTROL" to "8080",
        "CARRERA" to "INGENIERIA INFORMÁTICA",
        "DEPARTAMENTO_NOMBRE" to "Departamento Académico de Sistemas y Computación",
        "FECHA_CARTA" to "7 de julio de 2026",
        "JEFE_DIVISION_NOMBRE" to "MANUEL FABIAN ROJAS",
        "JEFE_DIVISION_TITULO_LOWER" to "Jefe",
        "NOMBRE_PROYECTO" to "Lombricultura para el enriquecimiento de cultivos en el ITVO",
        "NOMBRE_ASESORES" to "ING. EJEMPLO EJEMPLO",
        "NUM_ESTUDIANTES" to "1",
        "JEFE_DEPTO_NOMBRE" to "EJEMPLO EJEMPLO",
        "JEFE_DEPTO_CARGO" to "JEFE DEL DEPARTAMENTO ACADÉMICO",
        "JEFE_DEPTO_INICIALES" to "EE/xy",
        "CORREO_DEPARTAMENTO" to "sistemas_voaxaca@tecnm.mx",
        "QR_CODE" to "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    @Test
    fun generaHoja32ConImagenesDelPieIncrustadas() {
        val bytes = pdfService.generarDesdeClasspath(
            "templates/html/hoja-32-registro-proyecto.html",
            valores,
            emptyMap(),
            mapOf("INSIGNIAS_HTML" to insigniasHtmlDePrueba),
        )
        assertNotNull(bytes, "La generación del PDF no debe fallar")
        assertTrue(bytes!!.size > 500)
        assertTrue(bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte())

        PDDocument.load(bytes).use { doc ->
            var totalImagenes = 0
            for (page in doc.pages) {
                val resources = page.resources ?: continue
                for (name in resources.xObjectNames) {
                    val xobj: PDXObject = resources.getXObject(name as COSName)
                    if (xobj is PDImageXObject) totalImagenes++
                }
            }
            // Encabezado (2) + imagen anual (1) + insignia de prueba vía {{INSIGNIAS_HTML}} (1) = al
            // menos 4 (el PDF puede deduplicar recursos de imagen idénticos, así que no exigimos exacto).
            assertTrue(totalImagenes >= 4, "Se esperaban al menos 4 imágenes incrustadas, hubo $totalImagenes")
        }
    }
}
