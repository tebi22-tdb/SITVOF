package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.service.UsuarioService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CrearUsuarioRequest(
    val nombre: String = "",
    val rol: String = "",
    val correo_electronico: String = "",
    val curp: String = "",
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

    /** Coordinador o división administrativa (apoyo a titulación no lista ni crea usuarios staff). */
    private fun puedeAdministrarUsuariosStaff(rol: String): Boolean {
        val r = rol.trim().lowercase()
        return r == "coordinador" || r == "division_estudios_prof_admin"
    }

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
                rol = it.rol,
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
        val rol = body.rol.trim().ifBlank { "coordinador" }
        val correo = body.correo_electronico.trim()
        val curp = body.curp.trim().uppercase()
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
        if (curp.isBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "La CURP es obligatoria."))
        }
        if (!rol.equals("academico", ignoreCase = true)) {
            segmentoAcademico = null
            carrerasAsignadas = emptyList()
        }
        if (
            rol.equals("academico", ignoreCase = true) &&
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
            try {
                val enviado = emailService.enviarCredenciales(correo, user, password)
                if (enviado) {
                    ResponseEntity.ok(
                        mapOf(
                            "ok" to true,
                            "message" to "Usuario creado. Se han enviado las credenciales al correo.",
                            "correo_enviado" to true,
                        ),
                    )
                } else {
                    ResponseEntity.ok(
                        mapOf(
                            "ok" to true,
                            "message" to
                                "Usuario creado. No se envió correo (configure spring.mail o revise el remitente). Comunique la contraseña por otro medio.",
                            "correo_enviado" to false,
                        ),
                    )
                }
            } catch (mailEx: Exception) {
                log.error("Usuario staff creado pero falló el envío de correo a {}: {}", correo, mailEx.message, mailEx)
                ResponseEntity.ok(
                    mapOf(
                        "ok" to true,
                        "message" to
                            "Usuario creado. No se pudo enviar el correo; comunique la contraseña por otro medio o revise SMTP.",
                        "correo_enviado" to false,
                        "detalle_correo" to (mailEx.message ?: ""),
                    ),
                )
            }
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Datos inválidos.")))
        }
    }
}
