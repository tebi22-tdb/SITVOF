package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Catalogo
import com.sit_titulacion.sit.repository.CatalogoRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class CatalogoRequest(
    val tipo: String,
    val nombre: String,
    val orden: Int? = null,
    val mesesVigencia: Int? = null,
    val esResidencia: Boolean? = null,
    val esCursoTitulacion: Boolean? = null,
    val tipoMentores: String? = null,
    val slug: String? = null,
    val carreras: List<String>? = null,
)

data class CatalogoResponse(
    val id: String,
    val tipo: String,
    val nombre: String,
    val activo: Boolean,
    val orden: Int,
    val mesesVigencia: Int?,
    val esResidencia: Boolean,
    val esCursoTitulacion: Boolean,
    val tipoMentores: String?,
    val slug: String?,
    val carreras: List<String>,
)

data class ModalidadDto(
    val nombre: String,
    val mesesVigencia: Int?,
    val esResidencia: Boolean,
    val esCursoTitulacion: Boolean,
    val tipoMentores: String?,
)

data class DepartamentoDto(
    val nombre: String,
    val slug: String?,
    val carreras: List<String>,
)

@Service
class CatalogoService(
    private val catalogoRepository: CatalogoRepository,
) {
    private val log = LoggerFactory.getLogger(CatalogoService::class.java)

    private val TIPOS_VALIDOS = setOf("carrera", "nivel", "modalidad", "departamento")

    fun listarPorTipo(tipo: String): List<Catalogo> =
        catalogoRepository.findByTipoAndActivoTrue(tipo)
            .sortedWith(compareBy({ it.orden }, { it.nombre }))

    fun listarTodos(): List<CatalogoResponse> =
        catalogoRepository.findAll()
            .sortedWith(compareBy({ it.tipo }, { it.orden }, { it.nombre }))
            .map { it.toResponse() }

    fun carreras(): List<String> = listarPorTipo("carrera").map { it.nombre }

    fun niveles(): List<String> = listarPorTipo("nivel").map { it.nombre }

    fun modalidades(): List<ModalidadDto> = listarPorTipo("modalidad").map {
        ModalidadDto(
            nombre = it.nombre,
            mesesVigencia = it.mesesVigencia,
            esResidencia = it.esResidencia,
            esCursoTitulacion = it.esCursoTitulacion,
            tipoMentores = it.tipoMentores ?: if (it.esResidencia) "residencia" else "estandar",
        )
    }

    /** Resuelve el tipo de mentores para una modalidad por nombre.
     *  Fallback: si no hay tipoMentores en el catálogo, usa esResidencia. */
    fun tipoMentoresPorNombre(nombre: String): String {
        val m = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre.trim(), ignoreCase = true) }
        if (m?.tipoMentores != null) return m.tipoMentores
        val esRes = m?.esResidencia ?: nombre.trim().equals("Residencia Profesional", ignoreCase = true)
        return if (esRes) "residencia" else "estandar"
    }

    fun departamentos(): List<DepartamentoDto> = listarPorTipo("departamento").map {
        DepartamentoDto(nombre = it.nombre, slug = it.slug, carreras = it.carreras)
    }

    /**
     * Duración en meses para una modalidad dada.
     * Si el catálogo está vacío o la modalidad no existe, cae al switch original para no romper nada.
     */
    fun mesesVigenciaPorNombre(nombre: String): Long? {
        val m = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre.trim(), ignoreCase = true) }
        if (m != null) return m.mesesVigencia?.toLong()
        // Fallback con la lógica original
        log.warn("Modalidad '{}' no encontrada en catálogo, usando fallback hardcodeado", nombre)
        val lower = nombre.trim().lowercase()
        return when {
            lower.contains("residencia")  -> 6L
            lower.contains("tesina")      -> 18L
            lower.contains("tesis")       -> 18L
            lower.contains("curso")       -> 12L
            lower.contains("investigaci") -> 12L
            lower.contains("ceneval")     -> null
            else                          -> 12L
        }
    }

    /**
     * Indica si una modalidad es Residencia Profesional (afecta flujo de certificación y filtrado de bandejas).
     * Cae al equals original si el catálogo está vacío.
     */
    fun esResidenciaPorNombre(nombre: String): Boolean {
        val m = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre.trim(), ignoreCase = true) }
        return m?.esResidencia
            ?: nombre.trim().equals("Residencia Profesional", ignoreCase = true)
    }

    fun crear(request: CatalogoRequest): Result<CatalogoResponse> {
        if (request.tipo !in TIPOS_VALIDOS)
            return Result.failure(IllegalArgumentException("Tipo '${request.tipo}' no es válido. Valores permitidos: $TIPOS_VALIDOS"))
        if (request.nombre.isBlank())
            return Result.failure(IllegalArgumentException("El nombre no puede estar vacío"))
        if (catalogoRepository.existsByTipoAndNombreIgnoreCase(request.tipo, request.nombre.trim()))
            return Result.failure(IllegalArgumentException("Ya existe un catálogo de tipo '${request.tipo}' con ese nombre"))

        val catalogo = Catalogo(
            tipo = request.tipo,
            nombre = request.nombre.trim(),
            activo = true,
            orden = request.orden ?: 0,
            mesesVigencia = request.mesesVigencia,
            esResidencia = request.esResidencia ?: false,
            esCursoTitulacion = request.esCursoTitulacion ?: false,
            tipoMentores = request.tipoMentores?.trim()?.ifBlank { null },
            slug = request.slug?.trim()?.ifBlank { null },
            carreras = request.carreras ?: emptyList(),
        )
        return Result.success(catalogoRepository.save(catalogo).toResponse())
    }

    fun actualizar(id: ObjectId, request: CatalogoRequest): Result<CatalogoResponse?> {
        val existing = catalogoRepository.findById(id).orElse(null)
            ?: return Result.success(null)

        val nuevoNombre = request.nombre.trim()
        if (!existing.nombre.equals(nuevoNombre, ignoreCase = true) &&
            catalogoRepository.existsByTipoAndNombreIgnoreCase(existing.tipo, nuevoNombre)
        ) {
            return Result.failure(IllegalArgumentException("Ya existe un catálogo de tipo '${existing.tipo}' con ese nombre"))
        }

        val updated = existing.copy(
            nombre = nuevoNombre,
            orden = request.orden ?: existing.orden,
            mesesVigencia = request.mesesVigencia,
            esResidencia = request.esResidencia ?: existing.esResidencia,
            esCursoTitulacion = request.esCursoTitulacion ?: existing.esCursoTitulacion,
            tipoMentores = if (request.tipoMentores != null) request.tipoMentores.trim().ifBlank { null } else existing.tipoMentores,
            slug = request.slug?.trim()?.ifBlank { null } ?: existing.slug,
            carreras = request.carreras ?: existing.carreras,
            fechaActualizacion = Instant.now(),
        )
        return Result.success(catalogoRepository.save(updated).toResponse())
    }

    fun desactivar(id: ObjectId): Boolean {
        val existing = catalogoRepository.findById(id).orElse(null) ?: return false
        catalogoRepository.save(existing.copy(activo = false, fechaActualizacion = Instant.now()))
        return true
    }

    fun activar(id: ObjectId): Boolean {
        val existing = catalogoRepository.findById(id).orElse(null) ?: return false
        catalogoRepository.save(existing.copy(activo = true, fechaActualizacion = Instant.now()))
        return true
    }
}

private fun Catalogo.toResponse() = CatalogoResponse(
    id = id?.toHexString() ?: "",
    tipo = tipo,
    nombre = nombre,
    activo = activo,
    orden = orden,
    mesesVigencia = mesesVigencia,
    esResidencia = esResidencia,
    esCursoTitulacion = esCursoTitulacion,
    tipoMentores = tipoMentores,
    slug = slug,
    carreras = carreras,
)
