package com.sit_titulacion.sit.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextosPlantillaTest {

    private val html = """
        <p class="tit" data-texto="92-titulo">Anexo 9.2</p>
        <p data-texto="92-cuerpo">Hola <span class="b">{{NOMBRE}}</span> listo.</p>
        <div class="hdr-fixed">Código {{CODIGO_DOCUMENTO}}</div>
    """.trimIndent()

    @Test
    fun `sustituye solo el bloque pedido y deja el encabezado`() {
        val out = TextosPlantilla.aplicar(html, mapOf("92-cuerpo" to "Adios <span class=\"b\">{{NOMBRE}}</span>."))
        assertTrue(out.contains("Adios <span class=\"b\">{{NOMBRE}}</span>."))
        assertTrue(out.contains("Anexo 9.2"))
        assertTrue(out.contains("{{CODIGO_DOCUMENTO}}"))
    }

    @Test
    fun `el editor no deja borrar un dato del expediente`() {
        val sucio = """Hola <span class="ph-fijo" contenteditable="false" data-ph="NOMBRE">{{NOMBRE}}</span> listo."""
        val limpio = TextosPlantilla.sanitizar(sucio)
        assertEquals("Hola {{NOMBRE}} listo.", limpio)
        assertTrue("NOMBRE" in TextosPlantilla.tokens(limpio))
    }

    @Test
    fun `conserva letra tamaño subrayado y color y tira el resto`() {
        val sucio = """<span style="font-family: Arial; font-size: 14pt; font-style: italic; text-decoration: underline; color: #1f4c8f; display: block; text-align: center; behavior: url(x)">Hola</span>"""
        val limpio = TextosPlantilla.sanitizar(sucio)
        assertTrue(limpio.contains("font-family: Arial"))
        assertTrue(limpio.contains("font-size: 14pt"))
        assertTrue(limpio.contains("font-style: italic"))
        assertTrue(limpio.contains("text-decoration: underline"))
        assertTrue(limpio.contains("color: #1f4c8f"))
        assertTrue(limpio.contains("display: block"))
        assertTrue(limpio.contains("text-align: center"))
        assertTrue(!limpio.contains("behavior"))
    }
}
