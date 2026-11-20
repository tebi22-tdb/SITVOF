package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.ConfiguracionInstitucional
import com.sit_titulacion.sit.domain.InsigniaAnexoEntry
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.repository.CarreraRepository
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.ConfiguracionInstitucionalRepository
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.web.api.dto.CodigosAnexosDto
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
    private val htmlAnexoPdfService: HtmlAnexoPdfService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun obtenerGlobal(): ConfigGlobalResponseDto {
        val doc = repo.findFirstByTipo("global")
        val nombre = doc?.jefeDivisionNombre
            ?: env.getProperty("sit.config.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
        val titulo = doc?.jefeDivisionTitulo
            ?: env.getProperty("sit.config.jefe-division-titulo", "JEFE").trim()
        // Prefijo siempre del nombre actual (ACAC…); se conserva el sufijo /elaboró si existe.
        val iniciales = inicialesFirmaDivision(nombre, doc?.jefeDivisionIniciales)
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
        val nombre = dto.jefeDivisionNombre.trim().uppercase()
        val updated = (existing ?: ConfiguracionInstitucional(tipo = "global")).copy(
            jefeDivisionNombre = nombre,
            jefeDivisionTitulo = dto.jefeDivisionTitulo.trim().uppercase(),
            jefeDivisionIniciales = inicialesFirmaDivision(nombre, dto.jefeDivisionIniciales),
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
        val carreraDoc = if (slugCarrera.isEmpty()) null else carreraRepository.findBySlug(slugCarrera)
        val depto = carreraDoc?.let { departamentoDeCarrera(it.id) }
        val catalogo = catalogoRepository.findByTipoAndActivoTrue("departamento").firstOrNull { cat ->
            cat.carreras.any { NormalizacionTexto.normalizarClave(it) == slugCarrera }
        }
        val slug = depto?.slug?.trim()?.lowercase()?.ifBlank { null }
            ?: catalogo?.slug?.trim()?.lowercase()?.ifBlank { null }
        if (slug == null) {
            log.warn("resolverPorCarrera: ningún departamento tiene la carrera='{}'. No se puede resolver jefe.", carrera)
            return null
        }
        val doc = configDeDepartamento(slug)
        val nombre = depto?.nombre ?: catalogo?.nombre ?: slug
        log.info(
            "resolverPorCarrera: depto='{}' slug='{}' | jefeNombre='{}' (doc={})",
            nombre,
            slug,
            doc?.jefeNombre,
            if (doc != null) "encontrado" else "NO EXISTE en configuracion_institucional",
        )
        return ConfigDepartamentoResponseDto(
            slug = slug,
            nombre = nombre,
            departamentoNombreCompleto = doc?.departamentoNombreCompleto?.takeIf { it.isNotBlank() } ?: nombre,
            jefeNombre = doc?.jefeNombre ?: "",
            jefeCargo = doc?.jefeCargo ?: "",
            jefeIniciales = doc?.jefeIniciales ?: "",
            correo = doc?.correo ?: "",
        )
    }

    /** Departamento de quien genera el anexo XXXII o XXXIII. */
    fun resolverPorSlug(slug: String): ConfigDepartamentoResponseDto? {
        val limpio = slug.trim().lowercase().replace('-', '_')
        if (limpio.isEmpty()) return null
        val depto = departamentoRepository.findBySlug(limpio)
            ?: departamentoRepository.findByActivoTrue().firstOrNull {
                it.slug.trim().lowercase().replace('-', '_') == limpio
            }
        val catalogo = catalogoRepository.findByTipoAndActivoTrue("departamento").firstOrNull {
            it.slug?.trim()?.lowercase()?.replace('-', '_') == limpio
        }
        val slugReal = depto?.slug?.trim()?.lowercase()?.ifBlank { null }
            ?: catalogo?.slug?.trim()?.lowercase()?.ifBlank { null }
            ?: return null
        val doc = configDeDepartamento(slugReal)
        val nombre = depto?.nombre ?: catalogo?.nombre ?: slugReal
        return ConfigDepartamentoResponseDto(
            slug = slugReal,
            nombre = nombre,
            departamentoNombreCompleto = doc?.departamentoNombreCompleto?.takeIf { it.isNotBlank() } ?: nombre,
            jefeNombre = doc?.jefeNombre ?: "",
            jefeCargo = doc?.jefeCargo ?: "",
            jefeIniciales = doc?.jefeIniciales ?: "",
            correo = doc?.correo ?: "",
        )
    }

    /** Si la carrera está en varios departamentos, usa el de mayor orden, no el primero de la lista. */
    private fun departamentoDeCarrera(carreraId: org.bson.types.ObjectId): com.sit_titulacion.sit.domain.Departamento? =
        departamentoRepository.findByCarreraIdsContains(carreraId)
            .filter { it.activo }
            .maxByOrNull { it.orden }

    /** La config se guarda con el slug del catálogo. Tolera guion bajo, guion o mayúsculas. */
    private fun configDeDepartamento(slug: String): ConfiguracionInstitucional? {
        repo.findFirstByTipoAndDepartamentoSlug("departamento", slug)?.let { return it }
        val clave = NormalizacionTexto.normalizarClave(slug.replace('_', ' ').replace('-', ' '))
        return repo.findByTipo("departamento").firstOrNull { doc ->
            val guardado = doc.departamentoSlug?.trim().orEmpty()
            guardado.equals(slug, ignoreCase = true) ||
                NormalizacionTexto.normalizarClave(guardado.replace('_', ' ').replace('-', ' ')) == clave
        }
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
        val dataUri = dataUriImagenRaster(archivo)

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

    /** JPG, PNG, GIF o WEBP según los bytes. Rechaza SVG y cualquier tipo declarado por el navegador. */
    private fun dataUriImagenRaster(archivo: MultipartFile): String {
        require(archivo.size in 1..(8L * 1024 * 1024)) { "La imagen debe pesar menos de 8 MB." }
        val bytes = archivo.bytes
        val mime = when {
            bytes.size > 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size > 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size > 6 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
            bytes.size > 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
            else -> null
        } ?: throw IllegalArgumentException("Solo se aceptan imágenes JPG, PNG, GIF o WEBP.")
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    /** Lista efectiva de insignias: la guardada en Mongo, o las 6 por defecto del classpath si nadie la ha tocado. */
    private fun obtenerInsigniasEfectivas(): List<InsigniaAnexoEntry> {
        val guardadas = repo.findFirstByTipo(TIPO_IMAGENES_ANEXOS)?.insigniasAnexos
        if (guardadas != null) return guardadas
        return INSIGNIAS_DEFAULT.map { (id, archivo) -> InsigniaAnexoEntry(id = id, dataUri = cargarImagenClasspathComoDataUri(archivo)) }
    }

    fun obtenerInsigniasAnexos(): List<InsigniaAnexoDto> =
        obtenerInsigniasEfectivas().map { InsigniaAnexoDto(id = it.id, dataUri = it.dataUri) }

    fun agregarInsigniaAnexo(archivo: MultipartFile): List<InsigniaAnexoDto> {
        val dataUri = dataUriImagenRaster(archivo)
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
        val nombre = doc?.jefeDivisionNombre?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.config.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
        return inicialesFirmaDivision(nombre, doc?.jefeDivisionIniciales)
    }

    /**
     * Pie de oficios/anexos: iniciales del Jefe(a) de División + sufijo de quien elaboró.
     * Ej. nombre «ALIDA COREY ARANGO CRUZ» + guardado «MFR/lmr» → «ACAC/lmr».
     * Si cambia el jefe/jefa, el prefijo se recalcula solo; el «/lmr» se conserva.
     */
    fun inicialesFirmaDivision(nombreJefe: String, inicialesGuardadas: String?): String {
        val prefijo = inicialesDesdeNombreCompleto(nombreJefe)
        val guardado = inicialesGuardadas?.trim().orEmpty()
        val slash = guardado.indexOf('/')
        val sufijo = if (slash >= 0) guardado.substring(slash) else ""
        return when {
            prefijo.isNotBlank() && sufijo.isNotEmpty() -> "$prefijo$sufijo"
            prefijo.isNotBlank() -> prefijo
            else -> guardado
        }
    }

    /** Primera letra de cada palabra significativa (omite de/del/la/los/las/y). */
    fun inicialesDesdeNombreCompleto(nombre: String): String {
        val omitir = setOf("DE", "DEL", "LA", "LAS", "LOS", "Y", "E")
        return nombre.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.uppercase(java.util.Locale.ROOT) !in omitir }
            .mapNotNull { w ->
                w.firstOrNull { it.isLetter() }?.uppercaseChar()
            }
            .joinToString("")
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

    fun obtenerCodigosAnexos(): CodigosAnexosDto {
        val doc = repo.findFirstByTipo(TIPO_CODIGOS_ANEXOS)
        return CodigosAnexosDto(
            codigoAnexo91 = textoCodigo(doc?.codigoAnexo91, "ITVO-AC-PR-05-02"),
            revisionAnexo91 = textoRevision(doc?.revisionAnexo91, "2"),
            codigoAnexo92 = textoCodigo(doc?.codigoAnexo92, "ITVO-AC-PR-05-03"),
            revisionAnexo92 = textoRevision(doc?.revisionAnexo92, "2"),
            codigoAnexo93 = textoCodigo(doc?.codigoAnexo93, "ITVO-AC-PR-05-04"),
            revisionAnexo93 = textoRevision(doc?.revisionAnexo93, "2"),
        )
    }

    fun actualizarCodigosAnexos(dto: CodigosAnexosDto): CodigosAnexosDto {
        val existing = repo.findFirstByTipo(TIPO_CODIGOS_ANEXOS)
        val updated = (existing ?: ConfiguracionInstitucional(tipo = TIPO_CODIGOS_ANEXOS)).copy(
            codigoAnexo91 = textoCodigo(dto.codigoAnexo91, "ITVO-AC-PR-05-02"),
            revisionAnexo91 = textoRevision(dto.revisionAnexo91, "2"),
            codigoAnexo92 = textoCodigo(dto.codigoAnexo92, "ITVO-AC-PR-05-03"),
            revisionAnexo92 = textoRevision(dto.revisionAnexo92, "2"),
            codigoAnexo93 = textoCodigo(dto.codigoAnexo93, "ITVO-AC-PR-05-04"),
            revisionAnexo93 = textoRevision(dto.revisionAnexo93, "2"),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return obtenerCodigosAnexos()
    }

    /** Código y número de revisión que se imprimen en el anexo indicado ("9.1", "9.2" o "9.3"). */
    fun codigoYRevisionAnexo(anexo: String): Pair<String, String> {
        val c = obtenerCodigosAnexos()
        return when (anexo) {
            "9.2" -> c.codigoAnexo92 to c.revisionAnexo92
            "9.3" -> c.codigoAnexo93 to c.revisionAnexo93
            else -> c.codigoAnexo91 to c.revisionAnexo91
        }
    }

    private fun textoCodigo(valor: String?, defecto: String): String {
        val limpio = valor?.trim()?.replace(Regex("[\\r\\n]"), " ")?.replace(Regex("\\s+"), " ")?.take(80).orEmpty()
        return limpio.uppercase(Locale.forLanguageTag("es-MX")).ifBlank { defecto }
    }

    private fun textoRevision(valor: String?, defecto: String): String {
        val limpio = valor?.trim()?.replace(Regex("[\\r\\n]"), "")?.take(20).orEmpty()
        return limpio.ifBlank { defecto }
    }

    /** Textos guardados del cuerpo, para imprimir el PDF. Vacío si esa hoja no se edita aquí. */
    fun textosDePlantilla(classpath: String): Map<String, String> {
        val anexo = ANEXO_POR_PLANTILLA[classpath] ?: return emptyMap()
        return textosGuardados(anexo)
    }

    fun htmlVistaTextos(anexo: String): String {
        val path = PLANTILLA_POR_ANEXO[anexo] ?: throw IllegalArgumentException("Este anexo no tiene hoja editable.")
        val raw = ClassPathResource(path).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        var html = TextosPlantilla.aplicar(raw, textosGuardados(anexo))
        if (anexo.startsWith("9.")) {
            val (codigo, revision) = codigoYRevisionAnexo(anexo)
            html = html.replace("{{CODIGO_DOCUMENTO}}", escaparVista(codigo))
            html = html.replace("{{REVISION_DOCUMENTO}}", escaparVista(revision))
        }
        if (anexo == "32" || anexo == "33") {
            html = html.replace("{{INSIGNIAS_HTML}}", construirHtmlInsignias())
        }
        html = html.replace("{{QR_CODE}}", QR_VISTA)
        html = TextosPlantilla.prepararEditor(html)
        val imagenes = if (anexo == "32" || anexo == "33") obtenerOverridesImagenesAnexos() else emptyMap()
        return htmlAnexoPdfService.embeberImagenes(html, path, imagenes)
    }

    fun guardarTextosAnexo(anexo: String, bloques: Map<String, String>): Map<String, String> {
        val path = PLANTILLA_POR_ANEXO[anexo] ?: throw IllegalArgumentException("Este anexo no tiene hoja editable.")
        val raw = ClassPathResource(path).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val originales = TextosPlantilla.extraer(raw)
        val prefijo = PREFIJO_ANEXO[anexo] ?: throw IllegalArgumentException("Este anexo no tiene hoja editable.")
        val marca = "$prefijo|"
        val actuales = repo.findFirstByTipo(TIPO_TEXTOS_ANEXOS)?.textosAnexos.orEmpty().toMutableMap()
        actuales.keys.removeAll { it.startsWith(marca) }
        for ((id, valor) in bloques) {
            val original = originales[id] ?: continue
            val limpio = TextosPlantilla.sanitizar(valor)
            val faltan = TextosPlantilla.tokens(original) - TextosPlantilla.tokens(limpio)
            if (faltan.isNotEmpty()) {
                throw IllegalArgumentException(
                    "No quites los datos del expediente: " + faltan.joinToString { "{{$it}}" },
                )
            }
            if (limpio != TextosPlantilla.sanitizar(original)) {
                actuales[marca + id] = limpio
            }
        }
        val existing = repo.findFirstByTipo(TIPO_TEXTOS_ANEXOS)
        repo.save(
            (existing ?: ConfiguracionInstitucional(tipo = TIPO_TEXTOS_ANEXOS)).copy(
                textosAnexos = actuales,
                fechaActualizacion = Instant.now(),
            ),
        )
        return textosGuardados(anexo)
    }

    private fun textosGuardados(anexo: String): Map<String, String> {
        val prefijo = PREFIJO_ANEXO[anexo] ?: return emptyMap()
        val marca = "$prefijo|"
        return repo.findFirstByTipo(TIPO_TEXTOS_ANEXOS)?.textosAnexos.orEmpty()
            .filterKeys { it.startsWith(marca) }
            .mapKeys { it.key.removePrefix(marca) }
    }

    private fun escaparVista(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun obtenerJefeServiciosEscolaresNombre(): String = obtenerServiciosEscolares().jefeNombre

    fun obtenerJefeServiciosEscolaresCargo(): String = obtenerServiciosEscolares().jefeCargo

    companion object {
        const val TIPO_SERVICIOS_ESCOLARES = "servicios_escolares"
        const val TIPO_IMAGENES_ANEXOS = "imagenes_anexos"
        const val TIPO_CODIGOS_ANEXOS = "codigos_anexos"
        const val TIPO_TEXTOS_ANEXOS = "textos_anexos"

        val PLANTILLA_POR_ANEXO = mapOf(
            "9.1" to "templates/html/anexo-9-1.html",
            "9.2" to "templates/html/anexo-9-2.html",
            "9.3" to "templates/html/anexo-9-3.html",
            "32" to "templates/html/hoja-32-registro-proyecto.html",
            "33" to "templates/html/hoja-33-liberacion-proyecto.html",
        )
        val ANEXO_POR_PLANTILLA = PLANTILLA_POR_ANEXO.entries.associate { it.value to it.key }
        val PREFIJO_ANEXO = mapOf("9.1" to "a91", "9.2" to "a92", "9.3" to "a93", "32" to "a32", "33" to "a33")

        private const val QR_VISTA =
            "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='80' height='80'%3E%3Crect width='80' height='80' fill='%23ffffff' stroke='%23000'/%3E%3Ctext x='26' y='46' font-size='16' font-family='Arial'%3EQR%3C/text%3E%3C/svg%3E"

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
