package com.sit_titulacion.sit.security

import com.sit_titulacion.sit.config.UsuarioDetailsService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: UsuarioDetailsService,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        val post = request.method.equals("POST", ignoreCase = true)
        return (path == "/api/auth/login" && post) || (path == "/api/auth/hash" && post)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val raw = header.substring(7).trim()
            if (raw.isNotEmpty()) {
                val username = jwtService.parseUsername(raw)
                if (username != null && SecurityContextHolder.getContext().authentication == null) {
                    try {
                        val user = userDetailsService.loadUserByUsername(username)
                        val auth = UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            user.authorities,
                        )
                        auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                        SecurityContextHolder.getContext().authentication = auth
                    } catch (_: Exception) {
                        SecurityContextHolder.clearContext()
                    }
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
