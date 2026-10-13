package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.repository.EgresadoRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/verificar")
class VerificacionController(
    private val egresadoRepository: EgresadoRepository,
) {
    private val formatter = DateTimeFormatter.ISO_INSTANT

    @GetMapping("/{uuid}")
    fun verificar(@PathVariable uuid: String): ResponseEntity<Map<String, Any?>> {
        val egresado = egresadoRepository.findByCertUuid(uuid.trim())
        return if (egresado != null) {
            // Buscar el proceso específico que tiene este cert_uuid
            val proceso = egresado.procesos.find { it.cert_uuid?.trim() == uuid.trim() }
                ?: egresado.procesoActivoOrNull()
            val p = egresado.datos_personales
            val nombre = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            val estatus = when (proceso?.estado_general) {
                "titulado" -> "Finalizado"
                "vencido"  -> "Vencido"
                else       -> "En proceso"
            }
            // Sin numero_control: la verificación pública no necesita ese PII;
            // basta nombre + carrera + estatus para confirmar autenticidad del documento.
            ResponseEntity.ok(
                mapOf(
                    "valido" to true,
                    "nombre" to nombre,
                    "titulo_proyecto" to (proceso?.datos_proyecto?.nombre_proyecto ?: ""),
                    "modalidad" to (proceso?.datos_proyecto?.modalidad ?: ""),
                    "carrera" to p.carrera,
                    "institucion" to "TECNM Campus Valle de Oaxaca (ITVO)",
                    "fecha_certificacion" to proceso?.fechaCertificacion?.let { formatter.format(it) },
                    "estatus" to estatus,
                ),
            )
        } else {
            ResponseEntity.ok(
                mapOf(
                    "valido" to false,
                    "mensaje" to "Documento no encontrado en el sistema.",
                ),
            )
        }
    }
}
