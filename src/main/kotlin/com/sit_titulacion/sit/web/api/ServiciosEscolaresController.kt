package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.RolSoporte
import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.ServiciosEscolaresService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/servicios-escolares")
class ServiciosEscolaresController(
    private val serviciosEscolaresService: ServiciosEscolaresService,
) {
    @GetMapping("/constancias-9-2")
    fun listarConstancias92(
        @RequestParam(defaultValue = "pendientes") estado: String,
        @RequestParam(name = "numero_control", required = false) numeroControl: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        requireServiciosEscolares(principal)?.let { return it }
        val estadoNorm = if (estado == "atendidos") "atendidos" else "pendientes"
        return ResponseEntity.ok(serviciosEscolaresService.listarBandeja(estadoNorm, numeroControl))
    }

    @GetMapping("/constancias-9-2/{id}")
    fun detalleConstancia92(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        requireServiciosEscolares(principal)?.let { return it }
        val detalle = serviciosEscolaresService.obtenerDetalle(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "No se encontró el expediente o la DEP aún no solicitó el anexo 9.2."))
        return ResponseEntity.ok(detalle)
    }

    @PostMapping("/constancias-9-2/{id}/confirmar")
    fun confirmarConstancia92(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        requireServiciosEscolares(principal)?.let { return it }
        return if (serviciosEscolaresService.confirmarConstancia92(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo confirmar: revisa que exista la solicitud y que no esté atendida."),
            )
        }
    }

    private fun requireServiciosEscolares(principal: UsuarioPrincipal?): ResponseEntity<*>? {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        if (!RolSoporte.tieneAlgunRol(principal.getRol(), "servicios_escolares")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        return null
    }
}
