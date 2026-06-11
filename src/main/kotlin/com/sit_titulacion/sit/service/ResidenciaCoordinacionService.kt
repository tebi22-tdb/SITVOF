package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.DocumentoAdjunto
import com.sit_titulacion.sit.domain.ResidenciaCoordinacion
import com.sit_titulacion.sit.repository.ResidenciaCoordinacionRepository
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

data class ResidenciaRequest(
    val numero_control: String,
    val nombre_alumno: String,
    val carrera: String,
    val coordinacion: String,
    val tipo_proyecto: String,
    val nombre_proyecto: String,
    val asesor_interno: String,
    val asesor_externo: String? = null,
    val fecha_carta_aceptacion: String,
    val fecha_inicio: String,
    val fecha_fin: String,
    val estado: String = "activa",
)

data class ResidenciaListItemResponse(
    val id: String,
    val numero_control: String,
    val nombre_alumno: String,
    val carrera: String,
    val coordinacion: String,
    val tipo_proyecto: String,
    val nombre_proyecto: String,
    val asesor_interno: String,
    val asesor_externo: String?,
    val fecha_carta_aceptacion: String,
    val fecha_inicio: String,
    val fecha_fin: String,
    val estado: String,
    val tiene_anexo_29: Boolean,
    val nombre_anexo_29: String?,
    val tamanio_anexo_29: Long?,
    val tiene_anexo_30: Boolean,
    val nombre_anexo_30: String?,
    val tamanio_anexo_30: Long?,
    val fecha_creacion: String,
    val fecha_actualizacion: String,
)

data class ResidenciaStatsResponse(
    val total: Long,
    val activas: Long,
    val pendientes_anexos: Long,
    val finalizadas: Long,
)

@Service
class ResidenciaCoordinacionService(
    private val repo: ResidenciaCoordinacionRepository,
    private val gridFsTemplate: GridFsTemplate,
) {

    fun listar(
        coordinacion: String? = null,
        carrera: String? = null,
        tipo_proyecto: String? = null,
        estado: String? = null,
        busqueda: String? = null,
    ): List<ResidenciaListItemResponse> {
        val todos = repo.findAll()
        return todos.filter { r ->
            (coordinacion.isNullOrBlank() || r.coordinacion == coordinacion) &&
            (carrera.isNullOrBlank() || r.carrera.contains(carrera, ignoreCase = true)) &&
            (tipo_proyecto.isNullOrBlank() || r.tipo_proyecto == tipo_proyecto) &&
            (estado.isNullOrBlank() || r.estado == estado) &&
            (busqueda.isNullOrBlank() || r.nombre_alumno.contains(busqueda, ignoreCase = true) ||
                r.numero_control.contains(busqueda, ignoreCase = true) ||
                r.nombre_proyecto.contains(busqueda, ignoreCase = true))
        }
        .sortedByDescending { it.fecha_creacion }
        .map { it.toListItem() }
    }

    fun obtenerPorId(id: ObjectId): ResidenciaListItemResponse? =
        repo.findById(id).orElse(null)?.toListItem()

    fun buscarPorNumeroControl(numeroControl: String): List<ResidenciaListItemResponse> =
        repo.findByNumeroControl(numeroControl.trim()).map { it.toListItem() }

    fun stats(): ResidenciaStatsResponse {
        val todos = repo.findAll()
        return ResidenciaStatsResponse(
            total = todos.size.toLong(),
            activas = todos.count { it.estado == "activa" }.toLong(),
            pendientes_anexos = todos.count { it.estado == "pendiente_anexos" }.toLong(),
            finalizadas = todos.count { it.estado == "finalizada" }.toLong(),
        )
    }

    fun crear(req: ResidenciaRequest, anexo29: MultipartFile?, anexo30: MultipartFile?): Result<ResidenciaListItemResponse> {
        val validacion = validar(req)
        if (validacion != null) return Result.failure(IllegalArgumentException(validacion))

        val docAnexo29 = anexo29?.takeIf { !it.isEmpty }?.let { guardarAnexo(it) }
        val docAnexo30 = anexo30?.takeIf { !it.isEmpty }?.let { guardarAnexo(it) }

        val residencia = ResidenciaCoordinacion(
            numero_control = req.numero_control.trim(),
            nombre_alumno = req.nombre_alumno.trim(),
            carrera = req.carrera.trim(),
            coordinacion = req.coordinacion.trim(),
            tipo_proyecto = req.tipo_proyecto.trim(),
            nombre_proyecto = req.nombre_proyecto.trim(),
            asesor_interno = req.asesor_interno.trim(),
            asesor_externo = req.asesor_externo?.trim()?.takeIf { it.isNotBlank() },
            fecha_carta_aceptacion = req.fecha_carta_aceptacion.trim(),
            fecha_inicio = req.fecha_inicio.trim(),
            fecha_fin = req.fecha_fin.trim(),
            estado = req.estado.trim(),
            anexo_29 = docAnexo29,
            anexo_30 = docAnexo30,
        )
        return Result.success(repo.save(residencia).toListItem())
    }

    fun actualizar(
        id: ObjectId,
        req: ResidenciaRequest,
        anexo29: MultipartFile?,
        anexo30: MultipartFile?,
    ): Result<ResidenciaListItemResponse?> {
        val existing = repo.findById(id).orElse(null) ?: return Result.success(null)
        val validacion = validar(req)
        if (validacion != null) return Result.failure(IllegalArgumentException(validacion))

        val docAnexo29 = when {
            anexo29 != null && !anexo29.isEmpty -> {
                borrarAnexo(existing.anexo_29)
                guardarAnexo(anexo29)
            }
            else -> existing.anexo_29
        }
        val docAnexo30 = when {
            anexo30 != null && !anexo30.isEmpty -> {
                borrarAnexo(existing.anexo_30)
                guardarAnexo(anexo30)
            }
            else -> existing.anexo_30
        }

        val actualizado = existing.copy(
            numero_control = req.numero_control.trim(),
            nombre_alumno = req.nombre_alumno.trim(),
            carrera = req.carrera.trim(),
            coordinacion = req.coordinacion.trim(),
            tipo_proyecto = req.tipo_proyecto.trim(),
            nombre_proyecto = req.nombre_proyecto.trim(),
            asesor_interno = req.asesor_interno.trim(),
            asesor_externo = req.asesor_externo?.trim()?.takeIf { it.isNotBlank() },
            fecha_carta_aceptacion = req.fecha_carta_aceptacion.trim(),
            fecha_inicio = req.fecha_inicio.trim(),
            fecha_fin = req.fecha_fin.trim(),
            estado = req.estado.trim(),
            anexo_29 = docAnexo29,
            anexo_30 = docAnexo30,
            fecha_actualizacion = Instant.now(),
        )
        return Result.success(repo.save(actualizado).toListItem())
    }

    fun eliminar(id: ObjectId): Boolean {
        val existing = repo.findById(id).orElse(null) ?: return false
        borrarAnexo(existing.anexo_29)
        borrarAnexo(existing.anexo_30)
        repo.deleteById(id)
        return true
    }

    fun getAnexo(id: ObjectId, numero: Int): DocumentoStream? {
        val residencia = repo.findById(id).orElse(null) ?: return null
        val doc = if (numero == 29) residencia.anexo_29 else residencia.anexo_30
        val gridId = doc?.gridfs_id ?: return null
        val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(gridId))) ?: return null
        val resource = gridFsTemplate.getResource(file)
        return DocumentoStream(
            inputStream = resource.inputStream,
            contentType = doc.content_type.ifBlank { "application/pdf" },
            fileName = doc.nombre_original.ifBlank { "anexo-$numero.pdf" },
        )
    }

    private fun guardarAnexo(archivo: MultipartFile): DocumentoAdjunto {
        val bytes = archivo.bytes
        val isPdf = bytes.size > 4 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        require(isPdf) { "Solo se aceptan archivos PDF para los anexos" }
        val gridId = gridFsTemplate.store(
            bytes.inputStream(),
            archivo.originalFilename ?: "anexo.pdf",
            "application/pdf",
            null,
        ) as ObjectId
        return DocumentoAdjunto(
            gridfs_id = gridId,
            nombre_original = archivo.originalFilename ?: "anexo.pdf",
            content_type = "application/pdf",
            tamanio_bytes = archivo.size,
            fecha_subida = Instant.now(),
        )
    }

    private fun borrarAnexo(doc: DocumentoAdjunto?) {
        val gridId = doc?.gridfs_id ?: return
        try {
            gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gridId)))
        } catch (_: Exception) {}
    }

    private fun validar(req: ResidenciaRequest): String? {
        if (req.numero_control.isBlank()) return "El número de control es requerido"
        if (req.nombre_alumno.isBlank()) return "El nombre del alumno es requerido"
        if (req.carrera.isBlank()) return "La carrera es requerida"
        if (req.coordinacion.isBlank()) return "La coordinación es requerida"
        if (req.tipo_proyecto.isBlank()) return "El tipo de proyecto es requerido"
        if (req.nombre_proyecto.isBlank()) return "El nombre del proyecto es requerido"
        if (req.asesor_interno.isBlank()) return "El asesor interno es requerido"
        if (req.fecha_carta_aceptacion.isBlank()) return "La fecha de carta de aceptación es requerida"
        if (req.fecha_inicio.isBlank()) return "La fecha de inicio es requerida"
        if (req.fecha_fin.isBlank()) return "La fecha de fin es requerida"
        return null
    }

    private fun ResidenciaCoordinacion.toListItem() = ResidenciaListItemResponse(
        id = id!!.toHexString(),
        numero_control = numero_control,
        nombre_alumno = nombre_alumno,
        carrera = carrera,
        coordinacion = coordinacion,
        tipo_proyecto = tipo_proyecto,
        nombre_proyecto = nombre_proyecto,
        asesor_interno = asesor_interno,
        asesor_externo = asesor_externo,
        fecha_carta_aceptacion = fecha_carta_aceptacion,
        fecha_inicio = fecha_inicio,
        fecha_fin = fecha_fin,
        estado = estado,
        tiene_anexo_29 = anexo_29?.gridfs_id != null,
        nombre_anexo_29 = anexo_29?.nombre_original,
        tamanio_anexo_29 = anexo_29?.tamanio_bytes,
        tiene_anexo_30 = anexo_30?.gridfs_id != null,
        nombre_anexo_30 = anexo_30?.nombre_original,
        tamanio_anexo_30 = anexo_30?.tamanio_bytes,
        fecha_creacion = fecha_creacion.toString(),
        fecha_actualizacion = fecha_actualizacion.toString(),
    )
}
