package com.sit_titulacion.sit.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CorreosSinodalesTest {

    private val docentes = listOf(
        "Juan Pérez López" to "juan@itvo.edu.mx",
        "María García" to "maria@itvo.edu.mx",
        "Ana Ruiz" to "ana@itvo.edu.mx",
    )

    @Test
    fun `encuentra el correo aunque cambien mayusculas y acentos`() {
        val resultado = CorreosSinodales.resolver(listOf("JUAN PEREZ LOPEZ"), docentes)
        assertEquals(listOf("JUAN PEREZ LOPEZ" to "juan@itvo.edu.mx"), resultado)
    }

    @Test
    fun `quita el titulo y no repite la misma direccion`() {
        val resultado = CorreosSinodales.resolver(
            listOf("Mtro. Juan Pérez López", "juan perez lopez"),
            docentes,
        )
        assertEquals(listOf("Mtro. Juan Pérez López" to "juan@itvo.edu.mx"), resultado)
    }

    @Test
    fun `un nombre que no esta en el catalogo no corta a los demas`() {
        val resultado = CorreosSinodales.resolver(
            listOf("Persona Externa", "Ana Ruiz", ""),
            docentes,
        )
        assertEquals(listOf("Ana Ruiz" to "ana@itvo.edu.mx"), resultado)
    }

    @Test
    fun `si el nombre esta repetido en el catalogo usa el primero`() {
        val resultado = CorreosSinodales.resolver(
            listOf("María García"),
            listOf(
                "Maria Garcia" to "primera@itvo.edu.mx",
                "MARÍA GARCÍA" to "segunda@itvo.edu.mx",
            ),
        )
        assertEquals(listOf("María García" to "primera@itvo.edu.mx"), resultado)
    }

    @Test
    fun `encuentra el correo aunque el orden del nombre cambie`() {
        val resultado = CorreosSinodales.resolver(
            listOf("López Pérez Juan"),
            docentes,
        )
        assertEquals(listOf("López Pérez Juan" to "juan@itvo.edu.mx"), resultado)
    }
}
