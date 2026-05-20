package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Docente
import com.sit_titulacion.sit.repository.DocenteRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

data class DocenteRequest(
    val nombreCompleto: String,
    val correo: String,
    val cedula: String,
)

data class DocenteResponse(
    val id: String,
    val nombreCompleto: String,
    val correo: String,
    val cedula: String,
    val activo: Boolean,
)

@Service
class DocenteService(private val repo: DocenteRepository) {

    fun listar(): List<DocenteResponse> =
        repo.findByActivoTrue()
            .sortedBy { it.nombreCompleto }
            .map { it.toResponse() }

    fun crear(req: DocenteRequest): Result<DocenteResponse> {
        val nombre = req.nombreCompleto.trim()
        val correo = req.correo.trim()
        val cedula = req.cedula.trim()
        if (nombre.isBlank()) return Result.failure(IllegalArgumentException("El nombre completo es requerido"))
        if (correo.isBlank()) return Result.failure(IllegalArgumentException("El correo es requerido"))
        if (cedula.isBlank()) return Result.failure(IllegalArgumentException("La cédula es requerida"))
        val doc = Docente(nombreCompleto = nombre, correo = correo, cedula = cedula)
        return Result.success(repo.save(doc).toResponse())
    }

    fun actualizar(id: ObjectId, req: DocenteRequest): Result<DocenteResponse?> {
        val existing = repo.findById(id).orElse(null) ?: return Result.success(null)
        val updated = existing.copy(
            nombreCompleto = req.nombreCompleto.trim(),
            correo = req.correo.trim(),
            cedula = req.cedula.trim(),
            fechaActualizacion = Instant.now(),
        )
        return Result.success(repo.save(updated).toResponse())
    }

    fun eliminar(id: ObjectId): Boolean {
        val existing = repo.findById(id).orElse(null) ?: return false
        repo.save(existing.copy(activo = false, fechaActualizacion = Instant.now()))
        return true
    }

    private fun Docente.toResponse() = DocenteResponse(
        id = id!!.toHexString(),
        nombreCompleto = nombreCompleto,
        correo = correo,
        cedula = cedula,
        activo = activo,
    )
}
