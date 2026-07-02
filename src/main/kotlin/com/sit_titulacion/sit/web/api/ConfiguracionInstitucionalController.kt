package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.RolSoporte
import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.ConfiguracionInstitucionalService
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
) {
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

    @PostMapping("/imagen-anual", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun subirImagenAnual(
        @RequestParam("imagen") imagen: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (!esAdmin(principal)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!imagen.contentType.orEmpty().startsWith("image/")) {
            return ResponseEntity.badRequest().body(mapOf("error" to "El archivo debe ser una imagen."))
        }
        return ResponseEntity.ok(service.actualizarImagenAnual(imagen))
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
        return RolSoporte.tieneAlgunRol(principal.getRol(), "coordinador", "administrador")
    }
}
