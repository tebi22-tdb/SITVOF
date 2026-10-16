package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.UsuarioRepository
import com.sit_titulacion.sit.security.JwtService
import com.sit_titulacion.sit.service.EmailService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

data class UsuarioActualDto(
    val username: String,
    val rol: String,
    val nombre: String? = null,
    val segmento_academico: String? = null,
    val carreras_asignadas: List<String> = emptyList(),
)
data class HashRequest(val password: String? = null)
data class HashResponse(val hash: String)
data class LoginRequest(val username: String = "", val password: String = "")
data class RecuperarPasswordRequest(val numeroControl: String = "")
data class RestablecerPasswordRequest(
    val token: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val usuarioRepository: UsuarioRepository,
    private val egresadoRepository: EgresadoRepository,
    private val emailService: EmailService,
    @Value("\${sit.cert.base-url:https://sitvo.net}") private val publicBaseUrl: String,
) {
    private val secureRandom = SecureRandom()

    companion object {
        private const val RESET_TOKEN_MINUTOS = 30L
        private const val PASSWORD_MIN_LEN = 8
    }

    /**
     * Login JSON: devuelve JWT para sessionStorage (cada pestaña tiene su propio token).
     */
    @PostMapping("/login")
    fun login(@RequestBody body: LoginRequest): ResponseEntity<Any> {
        val u = body.username.trim()
        if (u.isBlank() || body.password.isEmpty()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Usuario y contraseña son obligatorios"))
        }
        return try {
            val auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(u, body.password),
            )
            val principal = auth.principal as UsuarioPrincipal
            val token = jwtService.generateToken(principal)
            ResponseEntity.ok(
                mapOf(
                    "username" to principal.username,
                    "rol" to principal.getRol().valorBd, // contrato con el frontend no cambia: sigue viajando en minúsculas
                    "nombre" to principal.getNombre(),
                    "segmento_academico" to principal.getSegmentoAcademico(),
                    "carreras_asignadas" to principal.getCarrerasAsignadas(),
                    "access_token" to token,
                ),
            )
        } catch (_: BadCredentialsException) {
            ResponseEntity.status(401).body(mapOf("error" to "Credenciales inválidas"))
        } catch (_: Exception) {
            ResponseEntity.status(401).body(mapOf("error" to "Credenciales inválidas"))
        }
    }

    /** Solo perfil dev: hash BCrypt para crear usuario coordinador en MongoDB. */
    @Profile("dev")
    @PostMapping("/hash")
    fun hash(@RequestBody body: HashRequest): ResponseEntity<HashResponse> {
        body.password
            ?.takeIf { it.isNotBlank() }
            ?.let { pwd ->
                @Suppress("USELESS_CAST")
                val hash = passwordEncoder.encode(pwd) as String
                return ResponseEntity.ok(HashResponse(hash))
            }
        return ResponseEntity.badRequest().build()
    }

    /**
     * Solicitud de recuperación: no cambia la contraseña.
     * Si la cuenta existe y tiene correo, guarda un token de un solo uso (30 min) y envía el enlace.
     * Siempre responde el mismo mensaje para no revelar si la cuenta existe.
     */
    @PostMapping("/recuperar-password")
    fun recuperarPassword(@RequestBody body: RecuperarPasswordRequest): ResponseEntity<Any> {
        val numeroControl = body.numeroControl.trim()
        if (numeroControl.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El número de control es obligatorio"))
        }
        val respuestaGenerica = mapOf(
            "ok" to true,
            "message" to "Si el número de control está registrado y tiene correo asociado, recibirás un enlace para restablecer tu contraseña. Revisa tu bandeja (y spam).",
        )
        val usuario = usuarioRepository.findByUsername(numeroControl)
        val correoDestino = usuario?.correoElectronico
            ?: usuario?.egresadoId?.let { eid ->
                egresadoRepository.findById(eid).orElse(null)?.datos_personales?.correo_electronico
            }
        if (usuario != null && usuario.activo && !correoDestino.isNullOrBlank()) {
            val tokenPlano = generarTokenReset()
            val ahora = Instant.now()
            usuarioRepository.save(
                usuario.copy(
                    passwordResetTokenHash = hashTokenReset(tokenPlano),
                    passwordResetExpira = ahora.plus(RESET_TOKEN_MINUTOS, ChronoUnit.MINUTES),
                    fechaActualizacion = ahora,
                ),
            )
            val base = publicBaseUrl.trim().trimEnd('/')
            val enlace = "$base/#/restablecer-password?token=${java.net.URLEncoder.encode(tokenPlano, Charsets.UTF_8)}"
            val enviado = try {
                emailService.enviarEnlaceRestablecerPassword(correoDestino, usuario.username, enlace, RESET_TOKEN_MINUTOS.toInt())
            } catch (_: Exception) {
                false
            }
            if (!enviado) {
                // No dejar token huérfano ni cambiar la contraseña si el correo no salió.
                usuarioRepository.save(
                    usuario.copy(
                        passwordResetTokenHash = null,
                        passwordResetExpira = null,
                        fechaActualizacion = Instant.now(),
                    ),
                )
            }
        }
        return ResponseEntity.ok(respuestaGenerica)
    }

    /**
     * Confirma el restablecimiento con el token del correo y la nueva contraseña.
     * Solo aquí se actualiza el hash de la cuenta.
     */
    @PostMapping("/restablecer-password")
    fun restablecerPassword(@RequestBody body: RestablecerPasswordRequest): ResponseEntity<Any> {
        val token = body.token.trim()
        val password = body.password
        val confirm = body.passwordConfirm
        if (token.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El enlace no es válido."))
        }
        if (password.length < PASSWORD_MIN_LEN) {
            return ResponseEntity.badRequest().body(mapOf("error" to "La contraseña debe tener al menos $PASSWORD_MIN_LEN caracteres."))
        }
        if (password != confirm) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Las contraseñas no coinciden."))
        }
        val usuario = usuarioRepository.findByPasswordResetTokenHash(hashTokenReset(token))
            ?: return ResponseEntity.status(400).body(mapOf("error" to "El enlace no es válido o ya fue utilizado."))
        val expira = usuario.passwordResetExpira
        if (expira == null || Instant.now().isAfter(expira)) {
            usuarioRepository.save(
                usuario.copy(
                    passwordResetTokenHash = null,
                    passwordResetExpira = null,
                    fechaActualizacion = Instant.now(),
                ),
            )
            return ResponseEntity.status(400).body(mapOf("error" to "El enlace ha caducado. Solicita uno nuevo."))
        }
        if (!usuario.activo) {
            return ResponseEntity.status(400).body(mapOf("error" to "La cuenta no está activa."))
        }
        @Suppress("USELESS_CAST")
        val nuevoHash = passwordEncoder.encode(password) as String
        usuarioRepository.save(
            usuario.copy(
                passwordHash = nuevoHash,
                passwordResetTokenHash = null,
                passwordResetExpira = null,
                fechaActualizacion = Instant.now(),
            ),
        )
        return ResponseEntity.ok(
            mapOf(
                "ok" to true,
                "message" to "Contraseña actualizada. Ya puedes iniciar sesión.",
            ),
        )
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<UsuarioActualDto> {
        val auth = principal ?: (SecurityContextHolder.getContext().authentication?.principal as? UsuarioPrincipal)
        if (auth == null) {
            return ResponseEntity.status(401).build()
        }
        return ResponseEntity.ok(UsuarioActualDto(
            username = auth.username,
            rol = auth.getRol().valorBd,
            nombre = auth.getNombre(),
            segmento_academico = auth.getSegmentoAcademico(),
            carreras_asignadas = auth.getCarrerasAsignadas(),
        ))
    }

    private fun generarTokenReset(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashTokenReset(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
