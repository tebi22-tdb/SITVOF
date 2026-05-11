package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.service.EgresadoService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// TEMPORAL — eliminar antes de producción
@RestController
@RequestMapping("/dev")
class DevDocsController(
    private val egresadoRepository: EgresadoRepository,
    private val egresadoService: EgresadoService,
) {

    @GetMapping("/docs", produces = [MediaType.TEXT_HTML_VALUE])
    fun paginaListado(): String {
        val egresados = egresadoRepository.findAll().sortedByDescending { it.id }
        val filas = egresados.joinToString("") { e ->
            val id = e.id?.toString() ?: ""
            val nombre = listOfNotNull(
                e.datos_personales.nombre,
                e.datos_personales.apellido_paterno,
                e.datos_personales.apellido_materno,
            ).joinToString(" ").ifBlank { "(sin nombre)" }
            val control = e.numero_control
            val tieneDoc = e.documento_adjunto.gridfs_id != null
            val certificado = e.fechaCertificacion != null
            val docCell = if (tieneDoc)
                """<a href="/dev/docs/$id/download" target="_blank">Descargar</a>"""
            else
                "<em>Sin documento</em>"
            val badge = when {
                certificado -> "Con PKI+QR"
                tieneDoc    -> "Sin certificar"
                else        -> "—"
            }
            "<tr><td>$control</td><td>$nombre</td><td>$badge</td><td>$docCell</td></tr>"
        }
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
              <meta charset="UTF-8">
              <title>[DEV] Documentos egresados</title>
              <style>
                body { font-family: sans-serif; padding: 2rem; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
                th { background: #f0f0f0; }
                tr:hover { background: #fafafa; }
                h1 { color: #c00; }
              </style>
            </head>
            <body>
              <p>Total: ${egresados.size} egresados</p>
              <table>
                <thead><tr><th>N° Control</th><th>Nombre</th><th>Estado PKI</th><th>Documento</th></tr></thead>
                <tbody>$filas</tbody>
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    @GetMapping("/docs/{id}/download")
    fun descargarDocumento(@PathVariable id: String): ResponseEntity<*> {
        val doc = egresadoService.obtenerDocumentoAdjunto(id)
            ?: return ResponseEntity.notFound().build<Void>()
        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${doc.fileName.replace("\"", "%22")}\"")
        }
        val mediaType = try { MediaType.parseMediaType(doc.contentType) } catch (_: Exception) { MediaType.APPLICATION_OCTET_STREAM }
        return ResponseEntity.ok()
            .headers(headers)
            .contentType(mediaType)
            .body(InputStreamResource(doc.inputStream))
    }
}
