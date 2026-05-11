package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.CatalogoRequest
import com.sit_titulacion.sit.service.CatalogoService
import org.bson.types.ObjectId
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

@RestController
@RequestMapping("/api/catalogos")
class CatalogoController(
    private val catalogoService: CatalogoService,
) {

    // ─── Endpoints públicos de lectura (sin autenticación) ───────────────────

    @GetMapping("/carreras")
    fun carreras(): List<String> = catalogoService.carreras()

    @GetMapping("/niveles")
    fun niveles(): List<String> = catalogoService.niveles()

    @GetMapping("/modalidades")
    fun modalidades() = catalogoService.modalidades()

    @GetMapping("/departamentos")
    fun departamentos() = catalogoService.departamentos()

    // ─── Endpoints de gestión (solo coordinador) ─────────────────────────────

    @GetMapping
    fun listarTodos(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede gestionar catálogos"))
        return ResponseEntity.ok(catalogoService.listarTodos())
    }

    @PostMapping
    fun crear(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @RequestBody request: CatalogoRequest,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede crear catálogos"))
        return catalogoService.crear(request).fold(
            onSuccess = { ResponseEntity.status(HttpStatus.CREATED).body(it) },
            onFailure = { ResponseEntity.badRequest().body(mapOf("error" to it.message)) },
        )
    }

    @PutMapping("/{id}")
    fun actualizar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
        @RequestBody request: CatalogoRequest,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede editar catálogos"))
        val oid = try { ObjectId(id) } catch (_: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        }
        return catalogoService.actualizar(oid, request).fold(
            onSuccess = { item ->
                if (item == null) ResponseEntity.notFound().build()
                else ResponseEntity.ok(item)
            },
            onFailure = { ResponseEntity.badRequest().body(mapOf("error" to it.message)) },
        )
    }

    @DeleteMapping("/{id}")
    fun desactivar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede desactivar catálogos"))
        val oid = try { ObjectId(id) } catch (_: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        }
        if (!catalogoService.desactivar(oid))
            return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("ok" to true))
    }

    @PostMapping("/{id}/activar")
    fun activar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede activar catálogos"))
        val oid = try { ObjectId(id) } catch (_: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        }
        if (!catalogoService.activar(oid))
            return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("ok" to true))
    }
}
