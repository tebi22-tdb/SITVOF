package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.DocenteRequest
import com.sit_titulacion.sit.service.DocenteService
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
@RequestMapping("/api/docentes")
class DocenteController(private val docenteService: DocenteService) {

    @GetMapping
    fun listar(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        return ResponseEntity.ok(docenteService.listar())
    }

    @PostMapping
    fun crear(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @RequestBody req: DocenteRequest,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede registrar docentes"))
        return docenteService.crear(req).fold(
            onSuccess = { ResponseEntity.status(HttpStatus.CREATED).body(it) },
            onFailure = { ResponseEntity.badRequest().body(mapOf("error" to it.message)) },
        )
    }

    @PutMapping("/{id}")
    fun actualizar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
        @RequestBody req: DocenteRequest,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede editar docentes"))
        val oid = try { ObjectId(id) } catch (_: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        }
        return docenteService.actualizar(oid, req).fold(
            onSuccess = { item ->
                if (item == null) ResponseEntity.notFound().build()
                else ResponseEntity.ok(item)
            },
            onFailure = { ResponseEntity.badRequest().body(mapOf("error" to it.message)) },
        )
    }

    @DeleteMapping("/{id}")
    fun eliminar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede eliminar docentes"))
        val oid = try { ObjectId(id) } catch (_: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        }
        if (!docenteService.eliminar(oid))
            return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("ok" to true))
    }
}
