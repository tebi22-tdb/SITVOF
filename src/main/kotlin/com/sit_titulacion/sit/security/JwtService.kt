package com.sit_titulacion.sit.security

import com.sit_titulacion.sit.config.UsuarioPrincipal
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${sit.jwt.secret}") private val secretRaw: String,
    @Value("\${sit.jwt.expiration-hours:12}") private val expirationHours: Long,
) {
    private val key: SecretKey by lazy {
        val s = secretRaw.trim().toByteArray(Charsets.UTF_8)
        require(s.size >= 32) { "sit.jwt.secret debe tener al menos 32 bytes (UTF-8)" }
        Keys.hmacShaKeyFor(s)
    }

    fun generateToken(principal: UsuarioPrincipal): String {
        val now = Instant.now()
        val exp = now.plusSeconds(expirationHours * 3600)
        return Jwts.builder()
            .subject(principal.username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key)
            .compact()
    }

    fun parseUsername(token: String): String? =
        try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
        } catch (_: ExpiredJwtException) {
            null
        } catch (_: Exception) {
            null
        }
}
