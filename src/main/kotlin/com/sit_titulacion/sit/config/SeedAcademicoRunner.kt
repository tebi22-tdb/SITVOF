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
 * Usuario académico de prueba (`academico`). Contraseña solo desde
 * `sit.seed.users.academico.password` / `SIT_SEED_ACADEMICO_PASSWORD`.
 * En producción el seed de usuarios va desactivado.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = ["sit.seed.users.enabled"], havingValue = "true", matchIfMissing = false)
class SeedAcademicoRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${sit.seed.users.academico.password:}") private val passwordAcademico: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedAcademicoRunner::class.java)

    companion object {
        const val USERNAME_ACADEMICO = "academico"
        val ROL_ACADEMICO = Rol.ACADEMICO
    }

    override fun run(args: ApplicationArguments) {
        if (usuarioRepository.existsByUsername(USERNAME_ACADEMICO)) {
            log.debug("Usuario academico ya existe, no se crea.")
            return
        }
        val password = passwordAcademico.trim()
        if (password.isEmpty()) {
            log.warn(
                "Seed de usuarios activo pero falta sit.seed.users.academico.password " +
                    "(o SIT_SEED_ACADEMICO_PASSWORD). No se crea «$USERNAME_ACADEMICO».",
            )
            return
        }
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(password) as String
        val usuario = Usuario(
            username = USERNAME_ACADEMICO,
            passwordHash = passwordHash,
            rol = ROL_ACADEMICO,
            egresadoId = null,
            activo = true,
        )
        try {
            usuarioRepository.save(usuario)
            log.info("Usuario departamento académico creado (usuario: $USERNAME_ACADEMICO).")
        } catch (ex: DuplicateKeyException) {
            log.warn("Usuario academico no creado (ya existe o conflicto de índice): {}", ex.mostSpecificCause.message)
        }
    }
}
