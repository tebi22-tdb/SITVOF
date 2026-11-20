package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Catalogo
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.domain.PasoFlujoCatalogo
import com.sit_titulacion.sit.domain.PlazoFlujoCatalogo
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
    val mesesProyecto: Int? = null,
    val mesesTitulacion: Int? = null,
    val esResidencia: Boolean? = null,
    val esCursoTitulacion: Boolean? = null,
    val tipoMentores: String? = null,
    val tipoUso: String? = null,
    val origenPlazo: String? = null,
    val origenFlujo: String? = null,
    val esCeneval: Boolean? = null,
    val pasos: List<PasoFlujoCatalogo>? = null,
    val plazos: List<PlazoFlujoCatalogo>? = null,
    val mentoresExtra: List<String>? = null,
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
    val mesesProyecto: Int?,
    val mesesTitulacion: Int?,
    val esResidencia: Boolean,
    val esCursoTitulacion: Boolean,
    val tipoMentores: String?,
    val tipoUso: String?,
    val origenPlazo: String?,
    val origenFlujo: String?,
    val esCeneval: Boolean,
    val pasos: List<PasoFlujoCatalogo>,
    val plazos: List<PlazoFlujoCatalogo>,
    val mentoresExtra: List<String>,
    val slug: String?,
    val carreras: List<String>,
)

data class ModalidadDto(
    val nombre: String,
    val mesesVigencia: Int?,
    val esResidencia: Boolean,
    val esCursoTitulacion: Boolean,
    val tipoMentores: String?,
    val tipoUso: String? = null,
    val esCeneval: Boolean = false,
    val origenFlujo: String? = null,
    val pasos: List<PasoFlujoCatalogo> = emptyList(),
)

data class DepartamentoDto(
    val nombre: String,
    val slug: String?,
    val carreras: List<String>,
)

data class OpcionTitulacionDto(
    val nombre: String,
    val clave: String,
)

@Service
class CatalogoService(
    private val catalogoRepository: CatalogoRepository,
) {
    private val log = LoggerFactory.getLogger(CatalogoService::class.java)

    private val TIPOS_VALIDOS = setOf("carrera", "nivel", "modalidad", "departamento", "opcion")

    fun listarPorTipo(tipo: String): List<Catalogo> =
        catalogoRepository.findByTipoAndActivoTrue(tipo)
            .sortedWith(compareBy({ it.orden }, { it.nombre }))

    fun listarTodos(): List<CatalogoResponse> {
        asegurarOpcionesBase()
        return catalogoRepository.findAll()
            .sortedWith(compareBy({ it.tipo }, { it.orden }, { it.nombre }))
            .map { it.toResponse() }
    }

    /** Titulación, titulación integral y curso de titulación existen desde el primer listado. */
    private fun asegurarOpcionesBase() {
        listOf(
            Triple("Titulación", "titulacion", 0),
            Triple("Titulación integral", "integral", 1),
            Triple("Curso de titulación", "curso", 2),
        ).forEach { (nombre, slug, orden) ->
            val yaExiste = catalogoRepository.existsByTipoAndNombreIgnoreCase("opcion", nombre) ||
                catalogoRepository.countByTipoAndSlug("opcion", slug) > 0
            if (!yaExiste) {
                catalogoRepository.save(
                    com.sit_titulacion.sit.domain.Catalogo(
                        tipo = "opcion",
                        nombre = nombre,
                        orden = orden,
                        slug = slug,
                    ),
                )
            }
        }
    }

    fun carreras(): List<String> = listarPorTipo("carrera").map { it.nombre }

    fun niveles(): List<String> = listarPorTipo("nivel").map { it.nombre }

    fun modalidades(): List<ModalidadDto> = listarPorTipo("modalidad").map {
        ModalidadDto(
            nombre = it.nombre,
            mesesVigencia = it.mesesVigencia,
            esResidencia = it.esResidencia,
            esCursoTitulacion = it.esCursoTitulacion,
            tipoMentores = it.tipoMentores ?: if (it.esResidencia) "residencia" else "estandar",
            tipoUso = it.tipoUso,
            esCeneval = it.esCeneval || it.origenFlujo == "ceneval" || it.nombre.contains("ceneval", ignoreCase = true),
            origenFlujo = it.origenFlujo,
            pasos = if (it.origenFlujo == "personalizado") it.pasos else emptyList(),
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

    fun opciones(): List<OpcionTitulacionDto> {
        asegurarOpcionesBase()
        return listarPorTipo("opcion").map {
            OpcionTitulacionDto(nombre = it.nombre, clave = it.slug?.trim().orEmpty().ifBlank { it.nombre })
        }
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
        validarPlazos(request)?.let { return Result.failure(IllegalArgumentException(it)) }

        val esResidencia = request.esResidencia ?: false
        val slugOpcion = if (request.tipo == "opcion") slugDeOpcion(request.nombre) else null
        if (slugOpcion != null && catalogoRepository.countByTipoAndSlug("opcion", slugOpcion) > 0) {
            return Result.failure(IllegalArgumentException("Ya existe una opción de titulación equivalente."))
        }
        val catalogo = Catalogo(
            tipo = request.tipo,
            nombre = request.nombre.trim(),
            activo = true,
            orden = request.orden ?: 0,
            mesesVigencia = mesesVigenciaCompatible(request.tipo, esResidencia, request),
            mesesProyecto = if (request.tipo == "modalidad") request.mesesProyecto else null,
            mesesTitulacion = if (request.tipo == "modalidad") request.mesesTitulacion else null,
            esResidencia = esResidencia,
            esCursoTitulacion = request.esCursoTitulacion ?: false,
            tipoMentores = request.tipoMentores?.trim()?.ifBlank { null },
            tipoUso = if (request.tipo == "modalidad") request.tipoUso?.trim()?.ifBlank { null } else null,
            origenPlazo = if (request.tipo == "modalidad") request.origenPlazo?.trim()?.ifBlank { null } else null,
            origenFlujo = if (request.tipo == "modalidad") request.origenFlujo?.trim()?.ifBlank { null } else null,
            esCeneval = if (request.tipo == "modalidad") request.esCeneval ?: false else false,
            pasos = if (request.tipo == "modalidad") limpiarPasos(request.pasos) else emptyList(),
            plazos = if (request.tipo == "modalidad") limpiarPlazos(request.plazos, request.pasos?.size ?: 0) else emptyList(),
            mentoresExtra = if (request.tipo == "modalidad") limpiarMentores(request.mentoresExtra) else emptyList(),
            slug = slugOpcion ?: normalizarSlugCatalogo(request.slug),
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
        validarPlazos(request)?.let { return Result.failure(IllegalArgumentException(it)) }

        val esResidencia = request.esResidencia ?: existing.esResidencia
        val updated = existing.copy(
            nombre = nuevoNombre,
            orden = request.orden ?: existing.orden,
            // La duración que ya leen los expedientes en curso no se reescribe al editar el formulario.
            mesesVigencia = if (existing.tipo == "modalidad") existing.mesesVigencia
                else request.mesesVigencia ?: existing.mesesVigencia,
            mesesProyecto = if (existing.tipo == "modalidad") request.mesesProyecto else existing.mesesProyecto,
            mesesTitulacion = if (existing.tipo == "modalidad") request.mesesTitulacion else existing.mesesTitulacion,
            esResidencia = esResidencia,
            esCursoTitulacion = request.esCursoTitulacion ?: existing.esCursoTitulacion,
            tipoMentores = if (request.tipoMentores != null) request.tipoMentores.trim().ifBlank { null } else existing.tipoMentores,
            tipoUso = if (existing.tipo == "modalidad") request.tipoUso?.trim()?.ifBlank { null } ?: existing.tipoUso else existing.tipoUso,
            origenPlazo = if (existing.tipo == "modalidad") request.origenPlazo?.trim()?.ifBlank { null } ?: existing.origenPlazo else existing.origenPlazo,
            origenFlujo = if (existing.tipo == "modalidad") request.origenFlujo?.trim()?.ifBlank { null } ?: existing.origenFlujo else existing.origenFlujo,
            esCeneval = if (existing.tipo == "modalidad") request.esCeneval ?: existing.esCeneval else existing.esCeneval,
            pasos = if (existing.tipo == "modalidad" && request.pasos != null) limpiarPasos(request.pasos) else existing.pasos,
            plazos = if (existing.tipo == "modalidad" && request.plazos != null) limpiarPlazos(request.plazos, (request.pasos ?: existing.pasos).size) else existing.plazos,
            mentoresExtra = if (existing.tipo == "modalidad" && request.mentoresExtra != null) limpiarMentores(request.mentoresExtra) else existing.mentoresExtra,
            slug = if (existing.tipo == "opcion") existing.slug else if (request.slug != null) normalizarSlugCatalogo(request.slug) else existing.slug,
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

    /** Solo borra un catálogo ya desactivado. `null` si se eliminó; mensaje si no se puede. */
    fun eliminar(id: ObjectId): String? {
        val existing = catalogoRepository.findById(id).orElse(null) ?: return "No se encontró el elemento."
        if (existing.activo) return "Desactiva el elemento antes de eliminarlo."
        catalogoRepository.deleteById(id)
        return null
    }

    private fun normalizarSlugCatalogo(slug: String?): String? =
        slug?.trim()?.takeIf { it.isNotBlank() }?.let { NormalizacionTexto.normalizarSlug(it) }

    private fun slugDeOpcion(nombre: String): String {
        val plano = NormalizacionTexto.normalizarSlug(nombre).replace('_', ' ')
        return when (plano) {
            "titulacion" -> "titulacion"
            "titulacion integral" -> "integral"
            "curso de titulacion" -> "curso"
            else -> NormalizacionTexto.normalizarSlug(nombre).ifBlank { "opcion" }
        }
    }

    private fun limpiarPasos(pasos: List<PasoFlujoCatalogo>?): List<PasoFlujoCatalogo> {
        val acciones = setOf(
            "Enviar",
            "Confirmar",
            "Confirmar (hasta que avance el proceso)",
            "Generar anexo",
            "Solicitar",
            "Agendar",
            "Subir documento",
            "Ninguna",
        )
        var yaCierra = false
        return pasos.orEmpty()
            .map {
                val accion = it.accion.trim().takeIf { nombre -> nombre in acciones } ?: "Ninguna"
                val cierra = it.cierraProceso && !yaCierra
                if (cierra) yaCierra = true
                PasoFlujoCatalogo(
                    nombre = it.nombre.trim(),
                    hace = it.hace.trim(),
                    recibe = it.recibe.trim(),
                    anexo = it.anexo.trim(),
                    accion = accion,
                    pideArchivo = it.pideArchivo || accion == "Subir documento",
                    permiteRegreso = it.permiteRegreso,
                    cierraProceso = cierra,
                    pestanaDepartamento = it.pestanaDepartamento.trim(),
                    llevaPlazo = it.llevaPlazo,
                    tipoPlazo = it.tipoPlazo.trim(),
                    meses = it.meses?.takeIf { mes -> mes in 1..12 },
                )
            }
            .filter { it.nombre.isNotBlank() }
            .take(40)
    }

    private fun limpiarPlazos(plazos: List<PlazoFlujoCatalogo>?, cantidadPasos: Int): List<PlazoFlujoCatalogo> {
        val inicios = setOf("anexo_xxxi", "carta", "paso")
        return plazos.orEmpty().mapNotNull { plazo ->
            val inicia = plazo.inicia.trim()
            if (inicia !in inicios) return@mapNotNull null
            val meses = plazo.meses?.takeIf { it in 1..12 } ?: return@mapNotNull null
            val pasoInicio = if (inicia == "paso") plazo.pasoInicio?.takeIf { it in 0 until cantidadPasos } ?: return@mapNotNull null else null
            val termina = if (plazo.termina.trim() == "paso") "paso" else "ninguno"
            val pasoFin = if (termina == "paso") plazo.pasoFin?.takeIf { it in 0 until cantidadPasos } ?: return@mapNotNull null else null
            PlazoFlujoCatalogo(inicia, pasoInicio, termina, pasoFin, meses)
        }.take(12)
    }

    private fun limpiarMentores(nombres: List<String>?): List<String> =
        nombres.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)

    private fun validarPlazos(request: CatalogoRequest): String? {
        if (request.tipo != "modalidad") return null
        return validarRangoMeses("El plazo de realización del proyecto", request.mesesProyecto)
            ?: validarRangoMeses("El plazo del proceso de titulación", request.mesesTitulacion)
    }

    private fun validarRangoMeses(etiqueta: String, valor: Int?): String? {
        if (valor == null) return null
        if (valor !in 1..12) return "$etiqueta debe ser un número de 1 a 12."
        return null
    }

    /** Conserva `mesesVigencia` para el resto del sistema: residencia usa el plazo de titulación; las demás, el de proyecto. */
    private fun mesesVigenciaCompatible(tipo: String, esResidencia: Boolean, request: CatalogoRequest): Int? {
        if (tipo != "modalidad") return request.mesesVigencia
        val explicito = if (esResidencia) request.mesesTitulacion ?: request.mesesProyecto
        else request.mesesProyecto ?: request.mesesTitulacion
        return explicito ?: request.mesesVigencia
    }

    fun activar(id: ObjectId): Boolean {
        val existing = catalogoRepository.findById(id).orElse(null) ?: return false
        catalogoRepository.save(existing.copy(activo = true, fechaActualizacion = Instant.now()))
        return true
    }
}

/** Meses que usa el mismo flujo de siempre. Si el catálogo no trae el campo nuevo, quedan 12, 6 y 6. */
internal data class PlazosModalidad(
    val mesesProyecto: Long,
    val mesesTitulacion: Long,
    val mesesResidencia: Long,
)

internal fun plazosDeModalidad(catalogo: Catalogo?): PlazosModalidad {
    fun enRango(valor: Int?): Long? = valor?.toLong()?.takeIf { it in 1..12 }
    return PlazosModalidad(
        mesesProyecto = enRango(catalogo?.mesesProyecto) ?: PlazoTitulacionCalculator.MESES_PLAZO_PROYECTO_NO_RES,
        mesesTitulacion = enRango(catalogo?.mesesTitulacion) ?: PlazoTitulacionCalculator.MESES_PLAZO_TITULACION_NO_RES,
        mesesResidencia = enRango(catalogo?.mesesTitulacion) ?: PlazoTitulacionCalculator.MESES_PLAZO_RESIDENCIA,
    )
}

private fun Catalogo.toResponse() = CatalogoResponse(
    id = id?.toHexString() ?: "",
    tipo = tipo,
    nombre = nombre,
    activo = activo,
    orden = orden,
    mesesVigencia = mesesVigencia,
    mesesProyecto = mesesProyecto,
    mesesTitulacion = mesesTitulacion,
    esResidencia = esResidencia,
    esCursoTitulacion = esCursoTitulacion,
    tipoMentores = tipoMentores,
    tipoUso = tipoUso,
    origenPlazo = origenPlazo,
    origenFlujo = origenFlujo,
    esCeneval = esCeneval,
    pasos = pasos,
    plazos = plazos,
    mentoresExtra = mentoresExtra,
    slug = slug,
    carreras = carreras,
)
