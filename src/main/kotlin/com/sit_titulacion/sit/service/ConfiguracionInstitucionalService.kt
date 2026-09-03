package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.ConfiguracionInstitucional
import com.sit_titulacion.sit.domain.InsigniaAnexoEntry
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.repository.CarreraRepository
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.ConfiguracionInstitucionalRepository
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoDto
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoResponseDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalResponseDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresResponseDto
import com.sit_titulacion.sit.web.api.dto.ImagenAnexoSlotDto
import com.sit_titulacion.sit.web.api.dto.InsigniaAnexoDto
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

@Service
class ConfiguracionInstitucionalService(
    private val repo: ConfiguracionInstitucionalRepository,
    private val catalogoRepository: CatalogoRepository,
    private val carreraRepository: CarreraRepository,
    private val departamentoRepository: DepartamentoRepository,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun obtenerGlobal(): ConfigGlobalResponseDto {
        val doc = repo.findFirstByTipo("global")
        val nombre = doc?.jefeDivisionNombre
            ?: env.getProperty("sit.config.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
        val titulo = doc?.jefeDivisionTitulo
            ?: env.getProperty("sit.config.jefe-division-titulo", "JEFE").trim()
        val iniciales = doc?.jefeDivisionIniciales?.trim().orEmpty()
        val correo = doc?.jefeDivisionCorreo?.trim().orEmpty()
        return ConfigGlobalResponseDto(
            jefeDivisionNombre = nombre,
            jefeDivisionTitulo = titulo,
            jefeDivisionIniciales = iniciales,
            jefeDivisionCorreo = correo,
        )
    }

    fun actualizarGlobal(dto: ConfigGlobalDto): ConfigGlobalResponseDto {
        val existing = repo.findFirstByTipo("global")
        val updated = (existing ?: ConfiguracionInstitucional(tipo = "global")).copy(
            jefeDivisionNombre = dto.jefeDivisionNombre.trim().uppercase(),
            jefeDivisionTitulo = dto.jefeDivisionTitulo.trim().uppercase(),
            jefeDivisionIniciales = dto.jefeDivisionIniciales.trim(),
            jefeDivisionCorreo = dto.jefeDivisionCorreo.trim(),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return obtenerGlobal()
    }

    fun obtenerTodosDepartamentos(): List<ConfigDepartamentoResponseDto> {
        val deptos = catalogoRepository.findByTipoAndActivoTrue("departamento").filter { it.slug != null }
        return deptos.map { cat ->
            val slug = cat.slug!!
            val doc = repo.findFirstByTipoAndDepartamentoSlug("departamento", slug)
            ConfigDepartamentoResponseDto(
                slug = slug,
                nombre = cat.nombre,
                departamentoNombreCompleto = doc?.departamentoNombreCompleto ?: "",
                jefeNombre = doc?.jefeNombre ?: "",
                jefeCargo = doc?.jefeCargo ?: "",
                jefeIniciales = doc?.jefeIniciales ?: "",
                correo = doc?.correo ?: "",
            )
        }
    }

    fun actualizarDepartamento(slug: String, dto: ConfigDepartamentoDto): ConfigDepartamentoResponseDto {
        val existing = repo.findFirstByTipoAndDepartamentoSlug("departamento", slug)
        val updated = (existing ?: ConfiguracionInstitucional(tipo = "departamento", departamentoSlug = slug)).copy(
            departamentoSlug = slug,
            departamentoNombreCompleto = dto.departamentoNombreCompleto.trim(),
            jefeNombre = dto.jefeNombre.trim().uppercase(),
            jefeCargo = dto.jefeCargo.trim().uppercase(),
            jefeIniciales = dto.jefeIniciales.trim(),
            correo = dto.correo.trim(),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return ConfigDepartamentoResponseDto(
            slug = slug,
            departamentoNombreCompleto = updated.departamentoNombreCompleto ?: "",
            jefeNombre = updated.jefeNombre ?: "",
            jefeCargo = updated.jefeCargo ?: "",
            jefeIniciales = updated.jefeIniciales ?: "",
            correo = updated.correo ?: "",
        )
    }

    /**
     * Resuelve la config de departamento a partir de la carrera del egresado.
     * Antes iteraba `Catalogo.carreras` con `.trim().equals(ignoreCase=true)` (sin
     * quitar acentos) — una de las 5 estrategias de normalización divergentes del
     * proyecto. Ahora resuelve contra `carreras`/`departamentos` (id estable,
     * normalización única), ver `NormalizacionTexto` y `CarreraDepartamentoSyncRunner`.
     */
    fun resolverPorCarrera(carrera: String): ConfigDepartamentoResponseDto? {
        val slugCarrera = NormalizacionTexto.normalizarClave(carrera)
        val carreraDoc = carreraRepository.findBySlug(slugCarrera)
        val depto = carreraDoc?.let { departamentoRepository.findByCarreraIdsContains(it.id).firstOrNull() }
        if (depto == null) {
            log.warn("resolverPorCarrera: ningún departamento tiene la carrera='{}'. No se puede resolver jefe.", carrera)
            return null
        }
        val doc = repo.findFirstByTipoAndDepartamentoSlug("departamento", depto.slug)
        log.info("resolverPorCarrera: depto='{}' slug='{}' | jefeNombre='{}' (doc={})",
            depto.nombre, depto.slug, doc?.jefeNombre, if (doc != null) "encontrado" else "NO EXISTE en configuracion_institucional")
        return ConfigDepartamentoResponseDto(
            slug = depto.slug,
            nombre = depto.nombre,
            departamentoNombreCompleto = doc?.departamentoNombreCompleto ?: depto.nombre,
            jefeNombre = doc?.jefeNombre ?: "",
            jefeCargo = doc?.jefeCargo ?: "",
            jefeIniciales = doc?.jefeIniciales ?: "",
            correo = doc?.correo ?: "",
        )
    }

    /** Casilleros de imágenes reemplazables de los anexos 32/33 (encabezado, imagen anual). */
    fun obtenerImagenesAnexos(): List<ImagenAnexoSlotDto> {
        val doc = repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)
        val actuales = doc?.imagenesAnexos.orEmpty()
        return SLOTS_IMAGENES_ANEXOS.map { (clave, etiqueta) ->
            ImagenAnexoSlotDto(clave = clave, etiqueta = etiqueta, tieneImagenPersonalizada = actuales.containsKey(sanitizarClaveMapa(clave)))
        }
    }

    fun actualizarImagenAnexo(clave: String, archivo: MultipartFile): List<ImagenAnexoSlotDto> {
        require(SLOTS_IMAGENES_ANEXOS.any { it.first == clave }) { "Clave de imagen desconocida: $clave" }
        val mime = archivo.contentType?.takeIf { it.startsWith("image/") } ?: "image/png"
        val b64 = Base64.getEncoder().encodeToString(archivo.bytes)
        val dataUri = "data:$mime;base64,$b64"

        val existing = repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)
        val actuales = existing?.imagenesAnexos.orEmpty()
        val updated = (existing ?: ConfiguracionInstitucional(tipo = TIPO_IMAGENES_ANEXOS)).copy(
            imagenesAnexos = actuales + (sanitizarClaveMapa(clave) to dataUri),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return obtenerImagenesAnexos()
    }

    /** Mapa clave (nombre de archivo) → data URI, solo para las imágenes con override subido. */
    fun obtenerOverridesImagenesAnexos(): Map<String, String> =
        repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)?.imagenesAnexos.orEmpty()
            .mapKeys { desanitizarClaveMapa(it.key) }

    /** Mongo no permite puntos en las llaves de un mapa (los usa para rutas anidadas),
     *  y nuestras claves son nombres de archivo como "logo-sep-tecnm.png". Los sustituimos
     *  solo para guardar/leer de la base; el resto del código sigue usando el nombre real. */
    private fun sanitizarClaveMapa(clave: String): String = clave.replace(".", "__DOT__")

    private fun desanitizarClaveMapa(clave: String): String = clave.replace("__DOT__", ".")

    /** Lista efectiva de insignias: la guardada en Mongo, o las 6 por defecto del classpath si nadie la ha tocado. */
    private fun obtenerInsigniasEfectivas(): List<InsigniaAnexoEntry> {
        val guardadas = repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)?.insigniasAnexos
        if (guardadas != null) return guardadas
        return INSIGNIAS_DEFAULT.map { (id, archivo) -> InsigniaAnexoEntry(id = id, dataUri = cargarImagenClasspathComoDataUri(archivo)) }
    }

    fun obtenerInsigniasAnexos(): List<InsigniaAnexoDto> =
        obtenerInsigniasEfectivas().map { InsigniaAnexoDto(id = it.id, dataUri = it.dataUri) }

    fun agregarInsigniaAnexo(archivo: MultipartFile): List<InsigniaAnexoDto> {
        val mime = archivo.contentType?.takeIf { it.startsWith("image/") } ?: "image/png"
        val dataUri = "data:$mime;base64,${Base64.getEncoder().encodeToString(archivo.bytes)}"
        val nueva = InsigniaAnexoEntry(id = UUID.randomUUID().toString(), dataUri = dataUri)
        return guardarInsignias(obtenerInsigniasEfectivas() + nueva)
    }

    fun eliminarInsigniaAnexo(id: String): List<InsigniaAnexoDto> =
        guardarInsignias(obtenerInsigniasEfectivas().filterNot { it.id == id })

    fun reordenarInsigniasAnexos(idsEnOrden: List<String>): List<InsigniaAnexoDto> {
        val actuales = obtenerInsigniasEfectivas().associateBy { it.id }
        val ordenadas = idsEnOrden.mapNotNull { actuales[it] }
        val faltantes = actuales.values.filter { it.id !in idsEnOrden }
        return guardarInsignias(ordenadas + faltantes)
    }

    private fun guardarInsignias(lista: List<InsigniaAnexoEntry>): List<InsigniaAnexoDto> {
        val existing = repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)
        val updated = (existing ?: ConfiguracionInstitucional(tipo = TIPO_IMAGENES_ANEXOS)).copy(
            insigniasAnexos = lista,
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return lista.map { InsigniaAnexoDto(id = it.id, dataUri = it.dataUri) }
    }

    /**
     * Arma el HTML de las insignias (una fila de <img>, ya con su tamaño calculado)
     * de forma que TODAS quepan en [anchoDisponibleCm], sin importar cuántas haya:
     * a más insignias, cada una se hace más chica (dentro de [altoMinCm]..[altoMaxCm]).
     */
    fun construirHtmlInsignias(
        anchoDisponibleCm: Double = 12.5,
        altoMinCm: Double = 0.4,
        altoMaxCm: Double = 1.3,
        gapCm: Double = 0.35,
    ): String {
        val insignias = obtenerInsigniasEfectivas()
        if (insignias.isEmpty()) return ""
        val proporciones = insignias.map { proporcionAnchoAlto(it.dataUri) }
        val sumaProporciones = proporciones.sum().takeIf { it > 0 } ?: insignias.size.toDouble()
        val gapsTotal = gapCm * (insignias.size - 1)
        val altoCalculado = ((anchoDisponibleCm - gapsTotal) / sumaProporciones).coerceIn(altoMinCm, altoMaxCm)
        return insignias.joinToString("") { insignia ->
            """<img src="${insignia.dataUri}" style="display:inline-block;vertical-align:middle;height:${"%.3f".format(Locale.ROOT, altoCalculado)}cm;width:auto;margin-right:${gapCm}cm;" alt=""/>"""
        }
    }

    /** Ancho / alto real de la imagen (para calcular cuánto ocupará a una altura dada). Si no se puede leer, usa 2:1. */
    private fun proporcionAnchoAlto(dataUri: String): Double {
        return try {
            val bytes = Base64.getDecoder().decode(dataUri.substringAfter("base64,"))
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            img.width.toDouble() / img.height.toDouble()
        } catch (_: Exception) {
            2.0
        }
    }

    private fun cargarImagenClasspathComoDataUri(nombreArchivo: String): String {
        val bytes = ClassPathResource("templates/html/$nombreArchivo").inputStream.use { it.readAllBytes() }
        val mime = when (nombreArchivo.substringAfterLast(".").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> "image/png"
        }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    fun obtenerJefeDivisionNombre(): String {
        val doc = repo.findFirstByTipo("global")
        return doc?.jefeDivisionNombre?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.config.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
    }

    fun obtenerJefeDivisionTitulo(): String {
        val doc = repo.findFirstByTipo("global")
        return doc?.jefeDivisionTitulo?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.config.jefe-division-titulo", "JEFE").trim()
    }

    fun obtenerJefeDivisionCargo(): String =
        "${obtenerJefeDivisionTitulo()} DE LA DIVISIÓN DE ESTUDIOS PROFESIONALES"

    fun obtenerJefeDivisionIniciales(): String {
        val doc = repo.findFirstByTipo("global")
        return doc?.jefeDivisionIniciales?.trim().orEmpty()
    }

    fun obtenerJefeDivisionCorreo(): String {
        val doc = repo.findFirstByTipo("global")
        return doc?.jefeDivisionCorreo?.trim().orEmpty()
    }

    fun obtenerServiciosEscolares(): ConfigServiciosEscolaresResponseDto {
        val doc = repo.findFirstByTipo(TIPO_SERVICIOS_ESCOLARES)
        val nombre = doc?.jefeNombre?.trim()?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.anexo91.destinatario-servicios-escolares", "ROMEO ALBERTO ANGELES PEREZ").trim()
                .ifBlank { "ROMEO ALBERTO ANGELES PEREZ" }
        val cargo = doc?.jefeCargo?.trim()?.takeIf { it.isNotBlank() }
            ?: "JEFE DEL DEPARTAMENTO DE SERVICIOS ESCOLARES"
        return ConfigServiciosEscolaresResponseDto(jefeNombre = nombre, jefeCargo = cargo)
    }

    fun actualizarServiciosEscolares(dto: ConfigServiciosEscolaresDto): ConfigServiciosEscolaresResponseDto {
        val existing = repo.findFirstByTipo(TIPO_SERVICIOS_ESCOLARES)
        val updated = (existing ?: ConfiguracionInstitucional(tipo = TIPO_SERVICIOS_ESCOLARES)).copy(
            jefeNombre = dto.jefeNombre.trim().uppercase(),
            jefeCargo = dto.jefeCargo.trim().uppercase(),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return obtenerServiciosEscolares()
    }

    fun obtenerJefeServiciosEscolaresNombre(): String = obtenerServiciosEscolares().jefeNombre

    fun obtenerJefeServiciosEscolaresCargo(): String = obtenerServiciosEscolares().jefeCargo

    companion object {
        const val TIPO_SERVICIOS_ESCOLARES = "servicios_escolares"
        const val TIPO_IMAGENES_ANEXOS = "imagenes_anexos"

        /** Clave (nombre de archivo en el classpath) a etiqueta legible, en el orden que se muestran en la UI.
         *  Solo casilleros de reemplazo fijo (1 imagen ↔ 1 lugar). Las insignias del pie son una
         *  lista aparte y dinámica: ver [insigniasAnexos]. */
        val SLOTS_IMAGENES_ANEXOS: List<Pair<String, String>> = listOf(
            "logo-sep-tecnm.png" to "SEP / TECNM (encabezado, izquierda)",
            "logo-itvo-mujer.jpg" to "ITVO (encabezado, derecha)",
            "imagenAnualCambiante.png" to "Imagen anual (pie de página)",
        )

        /** Insignias por defecto (classpath) cuando nadie ha personalizado la lista todavía,
         *  en el mismo orden en que aparecían originalmente en el pie de página. */
        private val INSIGNIAS_DEFAULT: List<Pair<String, String>> = listOf(
            "default-itvo-escudo" to "logo-itvo.png",
            "default-sin-plastico" to "logo-sin-plastico.png",
            "default-igualdad" to "logo-igualdad.jpg",
            "default-caceb" to "logo-caceb.jpeg",
            "default-comeaa" to "logo-comeaa.png",
            "default-conaic" to "logo-conaic.jpeg",
        )
    }
}
