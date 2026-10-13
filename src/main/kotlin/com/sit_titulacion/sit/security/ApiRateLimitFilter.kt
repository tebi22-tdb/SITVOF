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
        Regla("/api/auth/restablecer-password", "POST", 5, 900_000),
    )

    private val ventanas = ConcurrentHashMap<String, Ventana>()

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val regla = reglas.find { req.method == it.method && req.servletPath == it.path }
        if (regla == null) {
            chain.doFilter(req, res)
            return
        }

        val ip = ClientIpResolver.resolve(req)
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

/**
 * IP del cliente detrás de Nginx.
 *
 * Nginx ya envía `X-Real-IP` / `X-Forwarded-For`. Solo se confían esos headers
 * cuando el peer inmediato es un proxy local (127.0.0.1 / ::1), para no aceptar
 * spoofing si alguien llega directo al puerto 8081.
 */
internal object ClientIpResolver {

    private val IPV4 =
        Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
    private val IPV6_CHARS =
        Regex("""^[0-9a-fA-F:]+$""")

    fun resolve(req: HttpServletRequest): String {
        val remote = req.remoteAddr?.trim().orEmpty()
        if (esPeerProxyLocal(remote)) {
            val realIp = req.getHeader("X-Real-IP")?.trim()?.takeIf { esIpPlausible(it) }
            if (!realIp.isNullOrBlank()) return realIp

            val forwarded = req.getHeader("X-Forwarded-For")
                ?.split(",")
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() && esIpPlausible(it) }
            if (!forwarded.isNullOrBlank()) return forwarded
        }
        return remote.ifBlank { "desconocida" }
    }

    private fun esPeerProxyLocal(ip: String): Boolean =
        ip == "127.0.0.1" ||
            ip == "::1" ||
            ip == "0:0:0:0:0:0:0:1" ||
            ip == "https://example.com/user/juliet"

    /** Validación ligera: evita cabeceras basura; no abre el filtro a spoof remoto. */
    private fun esIpPlausible(ip: String): Boolean {
        if (ip.length > 45 || ip.contains(' ') || ip.contains('\n') || ip.contains('\r')) return false
        if (IPV4.matches(ip)) {
            return ip.split('.').all { part ->
                part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        return IPV6_CHARS.matches(ip) && ip.contains(':')
    }
}
