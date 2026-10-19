package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.service.UsuarioService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CambiarCorreoRequest(val nuevo_correo: String = "")

data class CrearUsuarioRequest(
    val nombre: String = "",
    val rol: String = "",
    val correo_electronico: String = "",
    val curp: String? = null,
    val segmento_academico: String? = null,
    val carreras_asignadas: List<String>? = null,
)

data class UsuarioStaffItemDto(
    val id: String,
    val nombre: String,
    val username: String,
    val rol: String,
    val curp: String?,
    val correo_electronico: String?,
    val segmento_academico: String?,
    val carreras_asignadas: List<String>,
    val activo: Boolean,
)

@RestController
@RequestMapping("/api/usuarios")
class UsuarioController(
    private val usuarioService: UsuarioService,
    private val emailService: EmailService,
) {
    private val log = LoggerFactory.getLogger(UsuarioController::class.java)

    /** Gestión de cuentas de staff: solo Administrador (DEP dejó de administrar cuentas). */
    private fun puedeAdministrarUsuariosStaff(rol: Rol): Boolean =
        rol == Rol.ADMINISTRADOR

    private fun puedeGestionarCuentas(rol: Rol): Boolean =
        rol == Rol.ADMINISTRADOR

    @GetMapping
    fun listar(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<Any> {
        if (principal == null || !puedeAdministrarUsuariosStaff(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "No tienes permiso para ver usuarios del personal."))
        }
        val lista = usuarioService.listarUsuariosStaff().map {
            UsuarioStaffItemDto(
                id = it.id?.toHexString() ?: "",
                nombre = it.nombre ?: it.username,
                username = it.username,
                rol = it.rol.valorBd,
                curp = it.curp,
                correo_electronico = it.correoElectronico,
                segmento_academico = it.segmentoAcademico,
                carreras_asignadas = it.carrerasAsignadas,
                activo = it.activo,
            )
        }
        return ResponseEntity.ok(lista)
    }

    /**
     * Crea un usuario de personal (coordinador o división administrativa).
     * Genera contraseña, guarda el usuario y envía credenciales al correo.
     */
    @PostMapping
    fun crear(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @RequestBody body: CrearUsuarioRequest,
    ): ResponseEntity<Any> {
        if (principal == null || !puedeAdministrarUsuariosStaff(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "No tienes permiso para agregar usuarios del personal."))
        }
        val nombre = body.nombre.trim()
        val rolTexto = body.rol.trim().ifBlank { "coordinador" }
        val rol = Rol.desdeBd(rolTexto)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Rol inválido: '$rolTexto'."))
        val correo = body.correo_electronico.trim()
        val curp = body.curp?.trim()?.uppercase() ?: ""
        var segmentoAcademico = body.segmento_academico?.trim()?.lowercase()?.ifBlank { null }
        var carrerasAsignadas =
            body.carreras_asignadas
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: emptyList()
        if (correo.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El correo electrónico es obligatorio."))
        }
        if (rol != Rol.ACADEMICO) {
            segmentoAcademico = null
            carrerasAsignadas = emptyList()
        }
        if (
            rol == Rol.ACADEMICO &&
            segmentoAcademico != null &&
            carrerasAsignadas.isEmpty()
        ) {
            return ResponseEntity.badRequest().body(
                mapOf("error" to "Para rol académico debes indicar segmento_academico y carreras_asignadas."),
            )
        }
        return try {
            val (user, password) =
                usuarioService.crearUsuarioStaff(
                    nombre = nombre,
                    usernameLogin = correo,
                    rol = rol,
                    correoElectronico = correo,
                    curp = curp,
                    segmentoAcademico = segmentoAcademico,
                    carrerasAsignadas = carrerasAsignadas,
                )
            // Espera hasta 1 minuto el correo. Solo si falla o tarda más, se muestran temporales.
            val envioFuture =
                CompletableFuture.supplyAsync {
                    try {
                        emailService.enviarCredenciales(correo, user, password)
                    } catch (mailEx: Exception) {
                        log.error(
                            "Usuario staff {} creado; falló correo a {}: {}",
                            user,
                            correo,
                            mailEx.message,
                            mailEx,
                        )
                        false
                    }
                }
            val enviado =
                try {
                    envioFuture.get(1, TimeUnit.MINUTES)
                } catch (_: TimeoutException) {
                    envioFuture.cancel(true)
                    log.warn("Correo de credenciales a {} superó 1 minuto (usuario={})", correo, user)
                    false
                } catch (ex: Exception) {
                    log.error("Error esperando envío de correo a {}: {}", correo, ex.message, ex)
                    false
                }
            if (enviado) {
                ResponseEntity.ok(
                    mapOf(
                        "ok" to true,
                        "message" to "Usuario creado. Se han enviado las credenciales al correo.",
                        "correo_enviado" to true,
                        "username" to user,
                    ),
                )
            } else {
                ResponseEntity.ok(
                    mapOf(
                        "ok" to true,
                        "message" to
                            "Usuario creado. El correo no llegó a tiempo o falló. " +
                            "Entrega estas credenciales en persona.",
                        "correo_enviado" to false,
                        "username" to user,
                        "password_temporal" to password,
                    ),
                )
            }
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Datos inválidos.")))
        }
    }

    /** Busca un usuario por username (número de control para egresados, correo para staff). */
    @GetMapping("/buscar/{username}")
    fun buscar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable username: String,
    ): ResponseEntity<Any> {
        if (principal == null || !puedeGestionarCuentas(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Sin permiso."))
        }
        val u = usuarioService.buscarPorUsername(username.trim())
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(u)
    }

    /** Cambia el correo de cualquier usuario. Solo coordinador/admin. */
    @PutMapping("/{username}/correo")
    fun cambiarCorreo(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable username: String,
        @RequestBody body: CambiarCorreoRequest,
    ): ResponseEntity<Any> {
        if (principal == null || !puedeGestionarCuentas(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Sin permiso."))
        }
        val error = usuarioService.cambiarCorreo(username, body.nuevo_correo)
        return if (error == null) ResponseEntity.ok(mapOf("ok" to true))
        else ResponseEntity.badRequest().body(mapOf("error" to error))
    }

    /** Genera una contraseña temporal para cualquier usuario. Solo coordinador/admin. */
    @PostMapping("/{username}/resetear-contrasena")
    fun resetearContrasena(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable username: String,
    ): ResponseEntity<Any> {
        if (principal == null || !puedeGestionarCuentas(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Sin permiso."))
        }
        val password = usuarioService.resetearContrasena(username)
        return if (password != null) {
            ResponseEntity.ok(mapOf("ok" to true, "password_temporal" to password))
        } else {
            ResponseEntity.badRequest().body(mapOf("error" to "No se encontró ningún usuario con ese nombre de usuario."))
        }
    }

    /** Elimina la cuenta de acceso. Solo coordinador/admin. No elimina expediente de egresado. */
    @DeleteMapping("/{username}")
    fun eliminarCuenta(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable username: String,
    ): ResponseEntity<Any> {
        if (principal == null || !puedeGestionarCuentas(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Sin permiso."))
        }
        val error = usuarioService.eliminarCuenta(username, principal.username)
        return if (error == null) {
            ResponseEntity.ok(mapOf("ok" to true, "message" to "Cuenta eliminada."))
        } else {
            ResponseEntity.badRequest().body(mapOf("error" to error))
        }
    }
}
