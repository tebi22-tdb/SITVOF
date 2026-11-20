package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Crea el usuario administrador de prueba (`coordinador`) solo si
 * `sit.seed.users.enabled=true` y hay contraseña en
 * `sit.seed.users.coordinador.password` / `SIT_SEED_COORDINADOR_PASSWORD`.
 * En producción el seed de usuarios va desactivado.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = ["sit.seed.users.enabled"], havingValue = "true", matchIfMissing = false)
class SeedCoordinadorRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${sit.seed.users.coordinador.password:}") private val passwordCoordinador: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedCoordinadorRunner::class.java)

    companion object {
        const val USERNAME_COORDINADOR = "coordinador"
        val ROL_COORDINADOR = Rol.ADMINISTRADOR
    }

    override fun run(args: ApplicationArguments) {
        if (usuarioRepository.existsByUsername(USERNAME_COORDINADOR)) {
            log.debug("Usuario coordinador ya existe, no se crea.")
            return
        }
        val password = passwordCoordinador.trim()
        if (password.isEmpty()) {
            log.warn(
                "Seed de usuarios activo pero falta sit.seed.users.coordinador.password " +
                    "(o SIT_SEED_COORDINADOR_PASSWORD). No se crea «$USERNAME_COORDINADOR».",
            )
            return
        }
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(password) as String
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
