package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Al arrancar, crea el usuario departamento académico por defecto (academico / 12345)
 * en la colección usuarios si aún no existe.
 */
@Component
@Order(2)
class SeedAcademicoRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedAcademicoRunner::class.java)

    companion object {
        const val USERNAME_ACADEMICO = "academico"
        const val PASSWORD_ACADEMICO = "12345"
        const val ROL_ACADEMICO = "academico"
    }

    override fun run(args: ApplicationArguments) {
        if (usuarioRepository.existsByUsername(USERNAME_ACADEMICO)) {
            log.debug("Usuario academico ya existe, no se crea.")
            return
        }
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(PASSWORD_ACADEMICO) as String
        val usuario = Usuario(
            username = USERNAME_ACADEMICO,
            passwordHash = passwordHash,
            rol = ROL_ACADEMICO,
            egresadoId = null,
            activo = true,
        )
        usuarioRepository.save(usuario)
        log.info("Usuario departamento académico creado (usuario: $USERNAME_ACADEMICO).")
    }
}
