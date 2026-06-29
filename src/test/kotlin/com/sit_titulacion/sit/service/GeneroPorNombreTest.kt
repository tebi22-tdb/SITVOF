package com.sit_titulacion.sit.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneroPorNombreTest {

    @Test
    fun inferirGeneroPorPrimerNombre() {
        assertEquals(GeneroDocumento.MASCULINO, GeneroPorNombre.inferir("MANUEL FABIAN ROJAS"))
        assertEquals(GeneroDocumento.FEMENINO, GeneroPorNombre.inferir("MARIANA DÍAZ JARQUÍN"))
        assertEquals(GeneroDocumento.FEMENINO, GeneroPorNombre.inferir("BETY GARCÍA"))
        assertEquals(GeneroDocumento.MASCULINO, GeneroPorNombre.inferir("PEDRO LÓPEZ"))
    }

    @Test
    fun etiquetasYTratamientoSegunGenero() {
        assertEquals("del C.", GeneroPorNombre.tratamientoEgresadoOficio(GeneroDocumento.MASCULINO))
        assertEquals("de la C.C.", GeneroPorNombre.tratamientoEgresadoOficio(GeneroDocumento.FEMENINO))
        assertEquals("PRESIDENTA", GeneroPorNombre.etiquetaPresidente(GeneroDocumento.FEMENINO))
        assertEquals("SECRETARIO", GeneroPorNombre.etiquetaSecretario(GeneroDocumento.MASCULINO))
    }

    @Test
    fun resolverPriorizaCodigoGuardado() {
        assertEquals(GeneroDocumento.FEMENINO, GeneroPorNombre.resolver("F", "MANUEL ROJAS"))
        assertEquals(GeneroDocumento.MASCULINO, GeneroPorNombre.resolver("M", "MARIANA DÍAZ"))
        assertEquals(GeneroDocumento.FEMENINO, GeneroPorNombre.resolver(null, "MARIANA DÍAZ"))
    }

    @Test
    fun cargoConGeneroAjustaJefeJefa() {
        val cargo = "JEFE DE LA DIVISIÓN DE ESTUDIOS PROFESIONALES"
        assertTrue(GeneroPorNombre.cargoConGenero(cargo, "MARIANA PÉREZ").contains("JEFA"))
        assertTrue(GeneroPorNombre.cargoConGenero(cargo, "MANUEL ROJAS").contains("JEFE"))
    }
}
