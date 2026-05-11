package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.UsuarioRepository
import com.sit_titulacion.sit.security.JwtService
import com.sit_titulacion.sit.service.EmailService
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
import java.time.Instant

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

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val usuarioRepository: UsuarioRepository,
    private val egresadoRepository: EgresadoRepository,
    private val emailService: EmailService,
) {

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
                    "rol" to principal.getRol(),
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
     * Recuperación de contraseña por correo electrónico.
     * Siempre devuelve el mismo mensaje para no revelar si el correo existe en el sistema.
     */
    /**
     * Recuperación de contraseña: el usuario ingresa su número de control (username).
     * Si la cuenta existe y tiene correo registrado, se genera nueva contraseña y se envía ahí.
     * Siempre devuelve el mismo mensaje para no revelar si la cuenta existe.
     */
    @PostMapping("/recuperar-password")
    fun recuperarPassword(@RequestBody body: RecuperarPasswordRequest): ResponseEntity<Any> {
        val numeroControl = body.numeroControl.trim()
        if (numeroControl.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El número de control es obligatorio"))
        }
        val respuestaGenerica = mapOf(
            "ok" to true,
            "message" to "Si el número de control está registrado y tiene correo asociado, recibirás una nueva contraseña en breve.",
        )
        val usuario = usuarioRepository.findByUsername(numeroControl)
        // Para egresados el correo vive en Egresado.datos_personales; para staff en Usuario.correoElectronico.
        val correoDestino = usuario?.correoElectronico
            ?: usuario?.egresadoId?.let { eid ->
                egresadoRepository.findById(eid).orElse(null)?.datos_personales?.correo_electronico
            }
        if (usuario != null && usuario.activo && !correoDestino.isNullOrBlank()) {
            val nuevaPassword = generarPasswordAleatoria()
            @Suppress("USELESS_CAST")
            val hash = passwordEncoder.encode(nuevaPassword) as String
            usuarioRepository.save(usuario.copy(passwordHash = hash, fechaActualizacion = Instant.now()))
            try {
                emailService.enviarRecuperacionPassword(correoDestino, usuario.username, nuevaPassword)
            } catch (_: Exception) {
                // El correo falló pero la contraseña ya se actualizó — el EmailService ya logea el error
            }
        }
        return ResponseEntity.ok(respuestaGenerica)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<UsuarioActualDto> {
        val auth = principal ?: (SecurityContextHolder.getContext().authentication?.principal as? UsuarioPrincipal)
        if (auth == null) {
            return ResponseEntity.status(401).build()
        }
        return ResponseEntity.ok(UsuarioActualDto(
            username = auth.username,
            rol = auth.getRol(),
            nombre = auth.getNombre(),
            segmento_academico = auth.getSegmentoAcademico(),
            carreras_asignadas = auth.getCarrerasAsignadas(),
        ))
    }

    private fun generarPasswordAleatoria(longitud: Int = 10): String {
        // Sin caracteres ambiguos (0/O, 1/l/I) para facilitar la lectura en el correo
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..longitud).map { chars.random() }.joinToString("")
    }
}
