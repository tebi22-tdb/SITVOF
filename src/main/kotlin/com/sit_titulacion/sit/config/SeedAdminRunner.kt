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
import org.springframework.core.env.Environment
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Usuario DEP de prueba (`admin`). Contraseña solo desde
 * `sit.seed.users.admin.password` / `SIT_SEED_ADMIN_PASSWORD`.
 * En producción el seed de usuarios va desactivado.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = ["sit.seed.users.enabled"], havingValue = "true", matchIfMissing = false)
class SeedAdminRunner(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    private val env: Environment,
    @Value("\${sit.seed.users.admin.password:}") private val passwordAdmin: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedAdminRunner::class.java)

    companion object {
        const val USERNAME_ADMIN = "admin"
        /** DEP: Inicio del proceso y Seguimiento del proceso. */
        val ROL_ADMIN = Rol.DEP
    }

    override fun run(args: ApplicationArguments) {
        val password = passwordAdmin.trim()
        if (password.isEmpty()) {
            log.warn(
                "Seed de usuarios activo pero falta sit.seed.users.admin.password " +
                    "(o SIT_SEED_ADMIN_PASSWORD). No se crea/sincroniza «$USERNAME_ADMIN».",
            )
            return
        }

        val sync = env.getProperty("sit.seed.sync-admin-credentials", "false").toBoolean()
        val existing = usuarioRepository.findByUsername(USERNAME_ADMIN)
        @Suppress("USELESS_CAST")
        val passwordHash = passwordEncoder.encode(password) as String
        if (existing != null) {
            val passwordDesincronizada = sync && !passwordEncoder.matches(password, existing.passwordHash)
            val rolDesincronizado = existing.rol != ROL_ADMIN
            if (passwordDesincronizada || rolDesincronizado) {
                usuarioRepository.save(
                    existing.copy(
                        passwordHash = if (passwordDesincronizada) passwordHash else existing.passwordHash,
                        rol = ROL_ADMIN,
                        activo = true,
                        fechaActualizacion = Instant.now(),
                    ),
                )
                val queSincronizo = listOfNotNull(
                    "credenciales".takeIf { passwordDesincronizada },
                    "rol".takeIf { rolDesincronizado },
                ).joinToString(" y ")
                log.info("Usuario admin: se sincronizó $queSincronizo (usuario: $USERNAME_ADMIN).")
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
