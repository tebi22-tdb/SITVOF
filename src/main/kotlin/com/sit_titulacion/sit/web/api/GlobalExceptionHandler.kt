package com.sit_titulacion.sit.web.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

data class ErrorResponse(val error: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val mensaje = ex.message?.trim()?.takeIf { esMensajeSeguroParaCliente(it) }
            ?: "La solicitud no es válida."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(mensaje))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        log.warn("Acceso denegado: {}", ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("No tienes permiso para realizar esta acción."))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse("Uno o más archivos superan el tamaño máximo permitido (100 MB)."))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Error no controlado: {}", ex.javaClass.simpleName, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Ocurrió un error al procesar la solicitud. Intenta de nuevo."))
    }

    /** Evita filtrar trazas, rutas internas o detalles técnicos al cliente. */
    private fun esMensajeSeguroParaCliente(mensaje: String): Boolean {
        if (mensaje.length > 220) return false
        val lower = mensaje.lowercase()
        val bloqueados = listOf(
            "exception", "stacktrace", "mongodb", "gridfs", "kotlin", "java.",
            "org.springframework", "nullpointer", "timeout", "connection refused",
        )
        return bloqueados.none { lower.contains(it) }
    }
}
