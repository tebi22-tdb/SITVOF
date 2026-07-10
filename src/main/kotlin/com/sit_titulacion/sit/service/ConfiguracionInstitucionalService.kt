package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.ConfiguracionInstitucional
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.ConfiguracionInstitucionalRepository
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoDto
import com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoResponseDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalDto
import com.sit_titulacion.sit.web.api.dto.ConfigGlobalResponseDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresDto
import com.sit_titulacion.sit.web.api.dto.ConfigServiciosEscolaresResponseDto
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.Base64

@Service
class ConfiguracionInstitucionalService(
    private val repo: ConfiguracionInstitucionalRepository,
    private val catalogoRepository: CatalogoRepository,
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
        return ConfigGlobalResponseDto(
            jefeDivisionNombre = nombre,
            jefeDivisionTitulo = titulo,
            jefeDivisionIniciales = iniciales,
            tieneImagenAnual = doc?.imagenAnualDataUri != null,
        )
    }

    fun actualizarGlobal(dto: ConfigGlobalDto): ConfigGlobalResponseDto {
        val existing = repo.findFirstByTipo("global")
        val updated = (existing ?: ConfiguracionInstitucional(tipo = "global")).copy(
            jefeDivisionNombre = dto.jefeDivisionNombre.trim().uppercase(),
            jefeDivisionTitulo = dto.jefeDivisionTitulo.trim().uppercase(),
            jefeDivisionIniciales = dto.jefeDivisionIniciales.trim(),
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return obtenerGlobal()
    }

    fun actualizarImagenAnual(archivo: MultipartFile): ConfigGlobalResponseDto {
        val bytes = archivo.bytes
        val mime = archivo.contentType?.takeIf { it.startsWith("image/") } ?: "image/png"
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val dataUri = "data:$mime;base64,$b64"

        val existing = repo.findFirstByTipo("global")
        val updated = (existing ?: ConfiguracionInstitucional(tipo = "global")).copy(
            imagenAnualDataUri = dataUri,
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
            fechaActualizacion = Instant.now(),
        )
        repo.save(updated)
        return ConfigDepartamentoResponseDto(
            slug = slug,
            departamentoNombreCompleto = updated.departamentoNombreCompleto ?: "",
            jefeNombre = updated.jefeNombre ?: "",
            jefeCargo = updated.jefeCargo ?: "",
            jefeIniciales = updated.jefeIniciales ?: "",
        )
    }

    /** Resuelve la config de departamento a partir de la carrera del egresado. */
    fun resolverPorCarrera(carrera: String): ConfigDepartamentoResponseDto? {
        val todos = catalogoRepository.findByTipoAndActivoTrue("departamento")
        log.info("resolverPorCarrera: carrera='{}' | deptos activos={} | carreras en BD={}",
            carrera, todos.size,
            todos.joinToString("; ") { "${it.slug}=[${it.carreras.joinToString()}]" })
        val cat = todos.firstOrNull { dept ->
            dept.carreras.any { it.trim().equals(carrera.trim(), ignoreCase = true) }
        } ?: run {
            log.warn("resolverPorCarrera: ningún depto tiene carrera='{}'. No se puede resolver jefe.", carrera)
            return null
        }
        val slug = cat.slug ?: run {
            log.warn("resolverPorCarrera: depto '{}' no tiene slug.", cat.nombre)
            return null
        }
        val doc = repo.findFirstByTipoAndDepartamentoSlug("departamento", slug)
        log.info("resolverPorCarrera: depto='{}' slug='{}' | jefeNombre='{}' (doc={})",
            cat.nombre, slug, doc?.jefeNombre, if (doc != null) "encontrado" else "NO EXISTE en configuracion_institucional")
        return ConfigDepartamentoResponseDto(
            slug = slug,
            nombre = cat.nombre,
            departamentoNombreCompleto = doc?.departamentoNombreCompleto ?: cat.nombre,
            jefeNombre = doc?.jefeNombre ?: "",
            jefeCargo = doc?.jefeCargo ?: "",
            jefeIniciales = doc?.jefeIniciales ?: "",
        )
    }

    /** Devuelve el data URI de la imagen anual, o null para usar la imagen por defecto del classpath. */
    fun obtenerImagenAnualDataUri(): String? =
        repo.findFirstByTipo("global")?.imagenAnualDataUri

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
    }
}
