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
class ApiRateLimitFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiRateLimitFilter::class.java)

    private data class Regla(val path: String, val method: String, val maxIntentos: Int, val ventanaMs: Long)

    private data class Ventana(val contador: AtomicInteger, @Volatile var resetProgramado: Boolean = false)

    private val reglas = listOf(
        Regla("/api/auth/login", "POST", 5, 60_000),
        Regla("/api/auth/recuperar-password", "POST", 3, 900_000),
    )

    private val ventanas = ConcurrentHashMap<String, Ventana>()

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val regla = reglas.find { req.method == it.method && req.servletPath == it.path }
        if (regla == null) {
            chain.doFilter(req, res)
            return
        }

        val ip = req.remoteAddr?.ifBlank { "desconocida" } ?: "desconocida"
        val clave = "${regla.path}:$ip"
        val ventana = ventanas.computeIfAbsent(clave) { Ventana(AtomicInteger(0)) }
        val actual = ventana.contador.incrementAndGet()

        if (!ventana.resetProgramado) {
            ventana.resetProgramado = true
            Thread {
                Thread.sleep(regla.ventanaMs)
                ventanas.remove(clave)
            }.also { it.isDaemon = true }.start()
        }

        if (actual > regla.maxIntentos) {
            log.warn("Rate limit {} para IP={} intentos={}", regla.path, ip, actual)
            res.status = 429
            res.contentType = "application/json;charset=UTF-8"
            res.writer.write("""{"error":"Demasiados intentos. Espere un momento antes de reintentar."}""")
            return
        }

        chain.doFilter(req, res)
    }
}
