package com.sit_titulacion.sit.security

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ClientIpResolverTest {

    @Test
    fun usaXRealIpCuandoPeerEsLocalhost() {
        val req = mock(HttpServletRequest::class.java)
        `when`(req.remoteAddr).thenReturn("127.0.0.1")
        `when`(req.getHeader("X-Real-IP")).thenReturn("203.0.113.45")
        assertEquals("203.0.113.45", ClientIpResolver.resolve(req))
    }

    @Test
    fun usaPrimerIpDeXForwardedForSiNoHayXRealIp() {
        val req = mock(HttpServletRequest::class.java)
        `when`(req.remoteAddr).thenReturn("127.0.0.1")
        `when`(req.getHeader("X-Real-IP")).thenReturn(null)
        `when`(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.1")
        assertEquals("198.51.100.7", ClientIpResolver.resolve(req))
    }

    @Test
    fun noConfiaHeadersSiPeerNoEsProxyLocal() {
        val req = mock(HttpServletRequest::class.java)
        `when`(req.remoteAddr).thenReturn("203.0.113.99")
        `when`(req.getHeader("X-Real-IP")).thenReturn("1.2.3.4")
        assertEquals("203.0.113.99", ClientIpResolver.resolve(req))
    }

    @Test
    fun fallbackARemoteAddrSiNoHayHeaders() {
        val req = mock(HttpServletRequest::class.java)
        `when`(req.remoteAddr).thenReturn("127.0.0.1")
        `when`(req.getHeader("X-Real-IP")).thenReturn(null)
        `when`(req.getHeader("X-Forwarded-For")).thenReturn(null)
        assertEquals("127.0.0.1", ClientIpResolver.resolve(req))
    }
}
