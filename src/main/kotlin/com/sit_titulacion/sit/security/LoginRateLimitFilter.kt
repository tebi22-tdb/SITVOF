package com.sit_titulacion.sit.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class LoginRateLimitFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(LoginRateLimitFilter::class.java)
    private val intentos = ConcurrentHashMap<String, AtomicInteger>()

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (req.method == "POST" && req.servletPath == "/api/auth/login") {
            val ip = req.remoteAddr
            val contador = intentos.computeIfAbsent(ip) { AtomicInteger(0) }
            val actual = contador.incrementAndGet()

            if (actual == 1) {
                // Primer intento: programa el reseteo en 60 segundos
                Thread {
                    Thread.sleep(60_000)
                    intentos.remove(ip)
                }.also { it.isDaemon = true }.start()
            }

            if (actual > 5) {
                log.warn("Rate limit excedido para IP={} intentos={}", ip, actual)
                res.status = 429
                res.contentType = "application/json;charset=UTF-8"
                res.writer.write("""{"error":"Demasiados intentos. Espere un momento antes de reintentar."}""")
                return
            }
        }
        chain.doFilter(req, res)
    }
}
