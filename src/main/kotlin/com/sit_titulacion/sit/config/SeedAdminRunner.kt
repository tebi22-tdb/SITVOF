package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Usuario alternativo de coordinación: admin / sitvo2026 (misma interfaz que coordinador).
 * Solo se crea si no existe; no sobrescribe contraseña de un admin ya registrado.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = ["sit.seed.enabled"], havingValue = "true", matchIfMissing = true)
class SeedAdminRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    private val env: Environment,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedAdminRunner::class.java)

    companion object {
        const val USERNAME_ADMIN = "admin"
        const val PASSWORD_ADMIN = "sitvo2026"
        /** Mismo acceso que coordinador → /home y menú de coordinación. */
        const val ROL_ADMIN = "coordinador"
    }

    override fun run(args: ApplicationArguments) {
        val sync = env.getProperty("sit.seed.sync-admin-credentials", "true").toBoolean()
        val existing = usuarioRepository.findByUsername(USERNAME_ADMIN)
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(PASSWORD_ADMIN) as String
        if (existing != null) {
            if (sync && !passwordEncoder.matches(PASSWORD_ADMIN, existing.passwordHash)) {
                usuarioRepository.save(
                    existing.copy(
                        passwordHash = passwordHash,
                        rol = ROL_ADMIN,
                        activo = true,
                        fechaActualizacion = Instant.now(),
                    ),
                )
                log.info("Usuario admin: credenciales sincronizadas (usuario: $USERNAME_ADMIN).")
            } else {
                log.debug("Usuario admin ya existe, no se modifica.")
            }
            return
        }
        val usuario = Usuario(
            username = USERNAME_ADMIN,
            passwordHash = passwordHash,
            rol = ROL_ADMIN,
            egresadoId = null,
            activo = true,
        )
        try {
            usuarioRepository.save(usuario)
            log.info("Usuario admin por defecto creado (usuario: $USERNAME_ADMIN).")
        } catch (ex: DuplicateKeyException) {
            log.warn("Usuario admin no creado (ya existe o conflicto de índice): {}", ex.mostSpecificCause.message)
        }
    }
}
