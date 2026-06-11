package com.sit_titulacion.sit.service

import org.springframework.web.multipart.MultipartFile

object ArchivoPdfValidator {
    /** Alineado con client_max_body_size de Nginx (100 MB). */
    const val MAX_BYTES: Long = 100L * 1024 * 1024

    fun validar(archivo: MultipartFile) {
        require(!archivo.isEmpty) { "El archivo está vacío." }
        require(archivo.size in 1..MAX_BYTES) { "El archivo no puede superar 100 MB." }
        val nombre = (archivo.originalFilename ?: "").trim().lowercase()
        if (nombre.isNotBlank() && !nombre.endsWith(".pdf")) {
            throw IllegalArgumentException("Solo se aceptan archivos PDF.")
        }
        val bytes = archivo.bytes
        require(esPdfPorContenido(bytes)) { "Solo se aceptan archivos PDF válidos." }
    }

    fun esPdfPorContenido(bytes: ByteArray): Boolean =
        bytes.size > 4 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte()
}
