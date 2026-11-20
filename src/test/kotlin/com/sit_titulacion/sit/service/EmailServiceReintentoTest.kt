package com.sit_titulacion.sit.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mail.javamail.JavaMailSender
import java.net.SocketTimeoutException
import java.util.Properties

@ExtendWith(MockitoExtension::class)
class EmailServiceReintentoTest {

    @Mock private lateinit var mailSender: JavaMailSender
    @Mock private lateinit var config: ConfiguracionInstitucionalService

    private fun servicio(esperaInicialMs: Long = 0L) = EmailService(
        mailSender,
        "avisos@ejemplo.test",
        "https://sitvo.net",
        config,
        esperaInicialMs,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> cualquiera(): T {
        any<T>()
        return null as T
    }

    private fun stubMensaje() {
        val session = Session.getInstance(Properties())
        `when`(mailSender.createMimeMessage()).thenAnswer { MimeMessage(session) }
        `when`(config.obtenerJefeDivisionNombre()).thenReturn("JEFE DE PRUEBA")
        `when`(config.obtenerJefeDivisionTitulo()).thenReturn("JEFE")
    }

    @Test
    fun `si el envio tarda, lo reintenta hasta mandarlo`() {
        stubMensaje()
        var envios = 0
        doAnswer {
            envios += 1
            if (envios == 1) throw SocketTimeoutException("Read timed out")
            null
        }.`when`(mailSender).send(cualquiera<MimeMessage>())

        val servicio = servicio()
        assertThrows(SocketTimeoutException::class.java) {
            servicio.enviarCredenciales("alumno@ejemplo.test", "20180001", "clave-temporal")
        }
        assertFalse(servicio.estadoActual().ok)

        servicio.reintentarCorreosPendientes()

        verify(mailSender, times(2)).send(cualquiera<MimeMessage>())
        assertTrue(servicio.estadoActual().ok)
    }

    @Test
    fun `una direccion inexistente no se reintenta`() {
        stubMensaje()
        doThrow(IllegalStateException("550 5.1.1 user unknown"))
            .`when`(mailSender).send(cualquiera<MimeMessage>())

        val servicio = servicio()
        assertThrows(IllegalStateException::class.java) {
            servicio.enviarCredenciales("nadie@ejemplo.test", "20180001", "clave-temporal")
        }

        servicio.reintentarCorreosPendientes()

        verify(mailSender, times(1)).send(cualquiera<MimeMessage>())
        assertFalse(servicio.estadoActual().ok)
    }
}
