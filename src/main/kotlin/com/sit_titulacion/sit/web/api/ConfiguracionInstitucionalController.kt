package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.service.ConfiguracionInstitucionalService
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.web.api.dto.CodigosAnexosDto
import com.sit_titulacion.sit.web.api.dto.TextosAnexoDto
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

    /** Avisos de correos que siguen en reintento, para la pantalla que corresponde. */
    @GetMapping("/estado-correo")
    fun getEstadoCorreo(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        val rol = principal?.getRol() ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!rol.esAlgunoDe(Rol.ADMINISTRADOR, Rol.DEP, Rol.ACADEMICO)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        val segmento = principal.getSegmentoAcademico()?.trim()?.lowercase()?.ifBlank { null }
        val avisos = emailService.avisosPendientes().filter { aviso ->
            when (rol) {
                Rol.ADMINISTRADOR -> true
                Rol.DEP -> aviso.modulo == "alta-egresados" || aviso.modulo == "seguimiento"
                Rol.ACADEMICO -> {
                    aviso.modulo == "departamento" &&
                        (segmento == null || aviso.segmento == null || aviso.segmento == segmento)
                }
                else -> false
            }
        }
        val estado = emailService.estadoActual()
        val cuerpo = mutableMapOf<String, Any?>(
            "ok" to estado.ok,
            "avisos" to avisos.map { mapOf("modulo" to it.modulo, "segmento" to it.segmento) },
        )
        if (rol == Rol.ADMINISTRADOR) {
            cuerpo["ultimo_error"] = estado.ultimoError
            cuerpo["momento"] = estado.momento?.toString()
        }
        return ResponseEntity.ok(cuerpo)
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
        return try {
            ResponseEntity.ok(service.actualizarImagenAnexo(clave, imagen))
        } catch (ex: IllegalArgumentException) {
            log.warn(">>> subirImagenAnexo clave='{}': clave inválida: {}", clave, ex.message)
            ResponseEntity.badRequest().body(mapOf("error" to "No se pudo usar esa imagen."))
        } catch (ex: Exception) {
            log.error(">>> subirImagenAnexo clave='{}' FALLÓ: {}", clave, ex.message, ex)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "No se pudo subir la imagen."))
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
        return try {
            ResponseEntity.ok(service.agregarInsigniaAnexo(imagen))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "No se pudo usar esa imagen.")))
        }
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

    @GetMapping("/textos-anexos/{anexo}", produces = [MediaType.TEXT_HTML_VALUE])
    fun getTextosAnexo(
        @PathVariable anexo: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return try {
            ResponseEntity.ok(service.htmlVistaTextos(anexo))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ex.message ?: "Este anexo no tiene hoja editable.")
        }
    }

    @PutMapping("/textos-anexos/{anexo}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putTextosAnexo(
        @PathVariable anexo: String,
        @RequestBody dto: TextosAnexoDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return try {
            ResponseEntity.ok(mapOf("bloques" to service.guardarTextosAnexo(anexo, dto.bloques)))
        } catch (ex: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "No se pudieron guardar los textos.")))
        }
    }

    @GetMapping("/codigos-anexos")
    fun getCodigosAnexos(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.obtenerCodigosAnexos())
    }

    @PutMapping("/codigos-anexos", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putCodigosAnexos(
        @RequestBody dto: CodigosAnexosDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        return ResponseEntity.ok(service.actualizarCodigosAnexos(dto))
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
