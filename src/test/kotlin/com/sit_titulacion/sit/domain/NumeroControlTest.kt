package com.sit_titulacion.sit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NumeroControlTest {

    @Test
    fun generacionIngreso_leeLosPrimeros2DigitosSinImportarElLargo() {
        assertEquals(2022, NumeroControl.generacionIngreso("220400"))
        assertEquals(2019, NumeroControl.generacionIngreso("1922638"))
    }

    @Test
    fun generacionIngreso_letraDeCambioDeCarreraOBajaTemporalNoAfectaElAnio() {
        // C = cambio de carrera, B = baja temporal — el prefijo de año se mantiene.
        assertEquals(2022, NumeroControl.generacionIngreso("220400C"))
        assertEquals(2019, NumeroControl.generacionIngreso("1922638B"))
    }

    @Test
    fun generacionIngreso_sinDosDigitosAlInicio_devuelveNull() {
        assertNull(NumeroControl.generacionIngreso("AB-1234"))
        assertNull(NumeroControl.generacionIngreso(""))
        assertNull(NumeroControl.generacionIngreso("A220400"))
    }

    @Test
    fun generacionIngreso_ignoraEspaciosAlInicioYFinal() {
        assertEquals(2022, NumeroControl.generacionIngreso("  220400  "))
    }
}
