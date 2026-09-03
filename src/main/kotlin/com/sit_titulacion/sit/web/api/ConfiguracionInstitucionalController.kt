package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.service.ConfiguracionInstitucionalService
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresDto
import com.sit_titulacion.sit.web.api.dto.ReordenarInsigniasDto
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/config-institucional")
class ConfiguracionInstitucionalController(
    private val service: ConfiguracionInstitucionalService,
    private val emailService: EmailService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Estado del envío de correos (SMTP), para el aviso en el panel de coordinación. */
    @GetMapping("/estado-correo")
    fun getEstadoCorreo(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val estado = emailService.estadoActual()
        return ResponseEntity.ok(
            mapOf(
                "ok" to estado.ok,
                "ultimo_error" to estado.ultimoError,
                "momento" to estado.momento?.toString(),
            ),
        )
    }

    @GetMapping("/global")
    fun getGlobal(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerGlobal())
    }

    @PutMapping("/global", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putGlobal(
        @RequestBody dto: ConfigGlobalDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.actualizarGlobal(dto))
    }

    @GetMapping("/departamentos")
    fun getDepartamentos(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerTodosDepartamentos())
    }

    @PutMapping("/departamento/{slug}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putDepartamento(
        @PathVariable slug: String,
        @RequestBody dto: ConfigDepartamentoDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.actualizarDepartamento(slug, dto))
    }

    @GetMapping("/imagenes-anexos")
    fun getImagenesAnexos(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerImagenesAnexos())
    }

    @PostMapping("/imagenes-anexos/{clave:.+}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun subirImagenAnexo(
        @PathVariable clave: String,
        @RequestParam("imagen") imagen: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!imagen.contentType.orEmpty().startsWith("image/")) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El archivo debe ser una imagen."))
        }
        return try {
            ResponseEntity.ok(service.actualizarImagenAnexo(clave, imagen))
        } catch (ex: IllegalArgumentException) {
            log.warn(">>> subirImagenAnexo clave='{}': clave inválida: {}", clave, ex.message)
            ResponseEntity.badRequest().body(mapOf("error" to ex.message))
        } catch (ex: Exception) {
            log.error(">>> subirImagenAnexo clave='{}' FALLÓ: {}", clave, ex.message, ex)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to (ex.message ?: ex.javaClass.simpleName)))
        }
    }

    @GetMapping("/insignias-anexos")
    fun getInsigniasAnexos(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerInsigniasAnexos())
    }

    @PostMapping("/insignias-anexos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun agregarInsigniaAnexo(
        @RequestParam("imagen") imagen: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!imagen.contentType.orEmpty().startsWith("image/")) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El archivo debe ser una imagen."))
        }
        return ResponseEntity.ok(service.agregarInsigniaAnexo(imagen))
    }

    @DeleteMapping("/insignias-anexos/{id}")
    fun eliminarInsigniaAnexo(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.eliminarInsigniaAnexo(id))
    }

    @PutMapping("/insignias-anexos/orden", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reordenarInsigniasAnexos(
        @RequestBody dto: ReordenarInsigniasDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.reordenarInsigniasAnexos(dto.idsEnOrden))
    }

    @GetMapping("/servicios-escolares")
    fun getServiciosEscolares(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerServiciosEscolares())
    }

    @PutMapping("/servicios-escolares", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putServiciosEscolares(
        @RequestBody dto: ConfigServiciosEscolaresDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.actualizarServiciosEscolares(dto))
    }

    private fun esAdmin(principal: UsuarioPrincipal?): Boolean {
        if (principal == null) return false
        return principal.getRol() == Rol.ADMINISTRADOR
    }
}
