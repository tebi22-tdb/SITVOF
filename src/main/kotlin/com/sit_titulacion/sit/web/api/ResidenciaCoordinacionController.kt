package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.service.DocumentoStream
import com.sit_titulacion.sit.service.ResidenciaCoordinacionService
import com.sit_titulacion.sit.service.ResidenciaRequest
import org.bson.types.ObjectId
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/residencias-coordinacion")
class ResidenciaCoordinacionController(
    private val service: ResidenciaCoordinacionService,
) {

    @GetMapping
    fun listar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @RequestParam(required = false) coordinacion: String?,
        @RequestParam(required = false) carrera: String?,
        @RequestParam(required = false) tipo_proyecto: String?,
        @RequestParam(required = false) estado: String?,
        @RequestParam(required = false) busqueda: String?,
    ): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        return ResponseEntity.ok(service.listar(coordinacion, carrera, tipo_proyecto, estado, busqueda))
    }

    @GetMapping("/stats")
    fun stats(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        return ResponseEntity.ok(service.stats())
    }

    @GetMapping("/{id}")
    fun obtenerPorId(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        val oid = parseId(id) ?: return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        val item = service.obtenerPorId(oid) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(item)
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun crear(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @RequestPart("datos") req: ResidenciaRequest,
        @RequestPart(value = "anexo_29", required = false) anexo29: MultipartFile?,
        @RequestPart(value = "anexo_30", required = false) anexo30: MultipartFile?,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede registrar residencias"))
        return service.crear(req, anexo29, anexo30).fold(
            onSuccess = { ResponseEntity.status(HttpStatus.CREATED).body(it) },
            onFailure = { ResponseEntity.badRequest().body(mapOf("error" to it.message)) },
        )
    }

    @PutMapping("/{id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun actualizar(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
        @RequestPart("datos") req: ResidenciaRequest,
        @RequestPart(value = "anexo_29", required = false) anexo29: MultipartFile?,
        @RequestPart(value = "anexo_30", required = false) anexo30: MultipartFile?,
    ): ResponseEntity<Any> {
        if (principal == null || principal.getRol() != "coordinador")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede editar residencias"))
        val oid = parseId(id) ?: return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        return service.actualizar(oid, req, anexo29, anexo30).fold(
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Solo el coordinador puede eliminar residencias"))
        val oid = parseId(id) ?: return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        if (!service.eliminar(oid)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("ok" to true))
    }

    @GetMapping("/{id}/anexo-29")
    fun descargarAnexo29(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        val oid = parseId(id) ?: return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        val doc = service.getAnexo(oid, 29) ?: return ResponseEntity.notFound().build()
        return buildFileResponse(doc)
    }

    @GetMapping("/{id}/anexo-30")
    fun descargarAnexo30(
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "No autenticado"))
        val oid = parseId(id) ?: return ResponseEntity.badRequest().body(mapOf("error" to "ID inválido"))
        val doc = service.getAnexo(oid, 30) ?: return ResponseEntity.notFound().build()
        return buildFileResponse(doc)
    }

    private fun parseId(id: String): ObjectId? =
        try { ObjectId(id) } catch (_: Exception) { null }

    private fun buildFileResponse(doc: DocumentoStream): ResponseEntity<Any> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(doc.contentType)
            setContentDispositionFormData("inline", doc.fileName)
        }
        return ResponseEntity.ok()
            .headers(headers)
            .body(InputStreamResource(doc.inputStream)) as ResponseEntity<Any>
    }
}
