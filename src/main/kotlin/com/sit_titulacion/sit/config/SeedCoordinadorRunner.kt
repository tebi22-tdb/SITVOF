package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Al arrancar la aplicación, crea el usuario administrador por defecto
 * (username "coordinador" / 12345 — el username de login no cambió, solo el
 * rol que se le asigna) en la colección usuarios si aún no existe. Es
 * temporal hasta definir cómo se gestionan usuarios.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = ["sit.seed.enabled"], havingValue = "true", matchIfMissing = true)
class SeedCoordinadorRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedCoordinadorRunner::class.java)

    companion object {
        const val USERNAME_COORDINADOR = "coordinador"
        const val PASSWORD_COORDINADOR = "12345"
        val ROL_COORDINADOR = Rol.ADMINISTRADOR
    }

    override fun run(args: ApplicationArguments) {
        if (usuarioRepository.existsByUsername(USERNAME_COORDINADOR)) {
            log.debug("Usuario coordinador ya existe, no se crea.")
            return
        }
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(PASSWORD_COORDINADOR) as String
        val usuario = Usuario(
            username = USERNAME_COORDINADOR,
            passwordHash = passwordHash,
            rol = ROL_COORDINADOR,
            egresadoId = null,
            activo = true,
        )
        try {
            usuarioRepository.save(usuario)
            log.info("Usuario coordinador por defecto creado (usuario: $USERNAME_COORDINADOR).")
        } catch (ex: DuplicateKeyException) {
            log.warn("Usuario coordinador no creado (ya existe o conflicto de índice): {}", ex.mostSpecificCause.message)
        }
    }
}
