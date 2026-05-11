package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.config.RolSoporte
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sit_titulacion.sit.domain.AnexoXxxi
import com.sit_titulacion.sit.domain.ConstanciaNoInconveniencia
import com.sit_titulacion.sit.domain.DatosPersonales
import com.sit_titulacion.sit.domain.DatosProyecto
import com.sit_titulacion.sit.domain.DocumentoAdjunto
import com.sit_titulacion.sit.domain.Documentos
import com.sit_titulacion.sit.domain.ArchivoEscaneadoMeta
import com.sit_titulacion.sit.domain.DocumentacionEscaneada
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.HistorialEstado
import com.sit_titulacion.sit.domain.SinodalesTribunal
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DocumentacionEscaneadaRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.UsuarioRepository
import com.sit_titulacion.sit.web.api.dto.AnexoDto
import com.sit_titulacion.sit.web.api.dto.DepartamentoListItemDto
import com.sit_titulacion.sit.web.api.dto.ConstanciaDto
import com.sit_titulacion.sit.web.api.dto.DatosPersonalesDto
import com.sit_titulacion.sit.web.api.dto.DatosProyectoDto
import com.sit_titulacion.sit.web.api.dto.DocumentoAdjuntoDto
import com.sit_titulacion.sit.web.api.dto.DocumentosDto
import com.sit_titulacion.sit.web.api.dto.EgresadoDetailDto
import com.sit_titulacion.sit.web.api.dto.EgresadoListItemDto
import com.sit_titulacion.sit.web.api.dto.EgresadoRequestDto
import com.sit_titulacion.sit.web.api.dto.EgresadoResponseDto
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.ZoneOffset
import java.util.Locale
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Base64

data class DocumentoStream(
    val inputStream: InputStream,
    val contentType: String,
    val fileName: String,
)

/** PDF ya leído a memoria (mejor detrás de Nginx/proxy que streamear GridFS sin Content-Length). */
data class DocumentoBytes(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
)

/** Respuesta de verificación de número de control en formulario de alta. */
data class VerificacionDuplicadoAlta(
    val estado: String,
    val expedienteEstado: String? = null,
)

@Service
class EgresadoService(
    private val egresadoRepository: EgresadoRepository,
    private val documentacionEscaneadaRepository: DocumentacionEscaneadaRepository,
    private val catalogoRepository: CatalogoRepository,
    private val usuarioRepository: UsuarioRepository,
    private val gridFsTemplate: GridFsTemplate,
    private val env: Environment,
    private val htmlAnexoPdfService: HtmlAnexoPdfService,
    private val revisionService: RevisionService,
    private val certService: CertificacionPdfService,
) {
    private val log = LoggerFactory.getLogger(EgresadoService::class.java)

    fun crear(datos: EgresadoRequestDto, archivo: MultipartFile?): Egresado {
        val ahora = Instant.now()
        val documentoAdjunto = if (archivo != null && !archivo.isEmpty) {
            val gridFsId = subirArchivo(archivo)
            DocumentoAdjunto(
                gridfs_id = gridFsId,
                nombre_original = archivo.originalFilename ?: "",
                content_type = archivo.contentType ?: "application/octet-stream",
                tamanio_bytes = archivo.size,
                fecha_subida = ahora,
            )
        } else {
            DocumentoAdjunto()
        }

        val egresado = Egresado(
            numero_control = datos.numero_control,
            datos_personales = DatosPersonales(
                nombre = datos.nombre,
                apellido_paterno = datos.apellidoPaterno,
                apellido_materno = datos.apellidoMaterno,
                carrera = datos.carrera,
                nivel = datos.nivel,
                direccion = datos.direccion,
                telefono = datos.telefono,
                correo_electronico = datos.correo_electronico,
            ),
            datos_proyecto = DatosProyecto(
                nombre_proyecto = datos.nombreProyecto ?: "",
                modalidad = datos.modalidad,
                curso_titulacion = datos.cursoTitulacion?.trim()?.lowercase()?.takeIf { it == "si" } ?: "no",
                asesor_interno = datos.asesorInterno?.takeIf { it.isNotBlank() },
                asesor_externo = datos.asesorExterno?.takeIf { it.isNotBlank() },
                director = datos.director?.takeIf { it.isNotBlank() },
                asesor_1 = datos.asesor1?.takeIf { it.isNotBlank() },
                asesor_2 = datos.asesor2?.takeIf { it.isNotBlank() },
            ),
            documentos = Documentos(
                anexo_xxxi = AnexoXxxi(
                    fecha_registro = parseFecha(datos.fechaRegistroAnexo),
                    estado = "pendiente",
                ),
                constancia_no_inconveniencia = ConstanciaNoInconveniencia(
                    fecha_expedicion = parseFecha(datos.fechaExpedicionConstancia),
                    estado = "pendiente",
                ),
            ),
            documento_adjunto = documentoAdjunto,
            estado_general = "registrado",
            historial_estados = listOf(
                HistorialEstado(estado = "registrado", fecha = ahora, observacion = "Registro inicial del alumno"),
            ),
            fechaCreacion = ahora,
            fecha_actualizacion = ahora,
        )
        val guardado = egresadoRepository.save(egresado)
        log.info("Egresado guardado en sit_titulacion.registro: id={}, numero_control={}", guardado.id, guardado.numero_control)
        return guardado
    }

    fun actualizar(id: String, datos: EgresadoRequestDto, archivo: MultipartFile?): Boolean {
        val objectId = try {
            ObjectId(id)
        } catch (_: Exception) {
            return false
        }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return false
        val ahora = Instant.now()
        val documentoAdjunto = when {
            datos.quitarArchivo == true -> DocumentoAdjunto()
            archivo != null && !archivo.isEmpty -> {
                val gridFsId = subirArchivo(archivo)
                DocumentoAdjunto(
                    gridfs_id = gridFsId,
                    nombre_original = archivo.originalFilename ?: "",
                    content_type = archivo.contentType ?: "application/octet-stream",
                    tamanio_bytes = archivo.size,
                    fecha_subida = ahora,
                )
            }
            else -> existente.documento_adjunto
        }
        val actualizado = existente.copy(
            numero_control = datos.numero_control,
            datos_personales = DatosPersonales(
                nombre = datos.nombre,
                apellido_paterno = datos.apellidoPaterno,
                apellido_materno = datos.apellidoMaterno,
                carrera = datos.carrera,
                nivel = datos.nivel,
                direccion = datos.direccion,
                telefono = datos.telefono,
                correo_electronico = datos.correo_electronico,
            ),
            datos_proyecto = DatosProyecto(
                nombre_proyecto = datos.nombreProyecto ?: "",
                modalidad = datos.modalidad,
                curso_titulacion = datos.cursoTitulacion?.trim()?.lowercase()?.takeIf { it == "si" } ?: "no",
                asesor_interno = datos.asesorInterno?.takeIf { it.isNotBlank() },
                asesor_externo = datos.asesorExterno?.takeIf { it.isNotBlank() },
                director = datos.director?.takeIf { it.isNotBlank() },
                asesor_1 = datos.asesor1?.takeIf { it.isNotBlank() },
                asesor_2 = datos.asesor2?.takeIf { it.isNotBlank() },
            ),
            documentos = Documentos(
                anexo_xxxi = AnexoXxxi(
                    fecha_registro = parseFecha(datos.fechaRegistroAnexo),
                    estado = existente.documentos.anexo_xxxi.estado,
                ),
                constancia_no_inconveniencia = ConstanciaNoInconveniencia(
                    fecha_expedicion = parseFecha(datos.fechaExpedicionConstancia),
                    estado = existente.documentos.constancia_no_inconveniencia.estado,
                ),
            ),
            documento_adjunto = documentoAdjunto,
            fecha_actualizacion = ahora,
        )
        egresadoRepository.save(actualizado)
        log.info("Egresado actualizado: id={}, numero_control={}", id, datos.numero_control)
        return true
    }

    fun eliminar(id: String): Boolean {
        val objectId = try {
            ObjectId(id)
        } catch (_: Exception) {
            return false
        }
        if (!egresadoRepository.existsById(objectId)) return false
        egresadoRepository.deleteById(objectId)
        log.info("Egresado eliminado: id={}", id)
        return true
    }

    fun listarTodos(): List<EgresadoResponseDto> =
        egresadoRepository.findAll().map { e ->
            EgresadoResponseDto(
                id = e.id?.toString() ?: "",
                numero_control = e.numero_control,
            )
        }

    /** Lista para el panel izquierdo: recientes primero, opcional filtro por número de control. */
    fun listarParaLista(numeroControlFilter: String?): List<EgresadoListItemDto> =
        listarParaLista(numeroControlFilter, null)

    /**
     * Lista para panel izquierdo aplicando, opcionalmente, el mismo alcance de la bandeja de departamento.
     * Si `scopeUsername` llega, se filtra por carreras del académico y exclusión de Residencia cuando aplique.
     */
    fun listarParaLista(numeroControlFilter: String?, scopeUsername: String?): List<EgresadoListItemDto> {
        val base = if (numeroControlFilter.isNullOrBlank()) {
            egresadoRepository.findAll().sortedByDescending { it.id }
        } else {
            egresadoRepository.findByNumeroControlContaining(numeroControlFilter.trim())
                .sortedByDescending { it.id }
        }
        val lista =
            if (scopeUsername.isNullOrBlank()) {
                base
            } else {
                val porCarrera = filtrarEgresadosPorCarreraSiAcademico(base, scopeUsername)
                if (bandejaDepartamentoExcluyeResidencia(scopeUsername)) {
                    porCarrera.filter { !esResidenciaProfesional(it) }
                } else {
                    porCarrera
                }
            }
        log.info("listarParaLista: encontrados {} egresados en DB", lista.size)
        val formatter = DateTimeFormatter.ISO_INSTANT
        return lista.map { e ->
            val p = e.datos_personales
            val nombreCompleto = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .ifBlank { "—" }
            val carrera = p.carrera.ifBlank { "—" }
            EgresadoListItemDto(
                id = e.id?.toString() ?: "",
                numero_control = e.numero_control,
                nombre = nombreCompleto,
                carrera = carrera,
                modalidad = e.datos_proyecto.modalidad.ifBlank { "—" },
                fecha_creacion = formatter.format(e.fechaCreacion),
                fecha_enviado_departamento_academico = e.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
                fecha_recibido_registro_liberacion = e.fechaRecibidoRegistroLiberacion?.let { formatter.format(it) },
                fecha_confirmacion_recibidos_anexo_xxxi_xxxii = e.fechaConfirmacionRecibidosAnexoXxxiXxxii?.let { formatter.format(it) },
                fecha_actualizacion = formatter.format(e.fecha_actualizacion),
                fecha_creacion_anexo_9_3 = e.fechaCreacionAnexo93?.let { formatter.format(it) },
                fecha_confirmacion_entrega_anexo_9_3 = e.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
                fecha_solicitud_documentacion_escaneada = e.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
                fecha_envio_documentacion_escaneada_egresado = e.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
                fecha_confirmacion_documentacion_escaneada_recibida = e.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
            )
        }
    }

    /**
     * Compatibilidad con controlador actual: por ahora se ignoran filtros por fecha/tipo.
     */
    fun listarParaLista(
        numeroControlFilter: String?,
        @Suppress("UNUSED_PARAMETER") fechaDesde: Instant?,
        @Suppress("UNUSED_PARAMETER") fechaHasta: Instant?,
        @Suppress("UNUSED_PARAMETER") tipoFiltro: String?,
        scopeUsername: String? = null,
    ): List<EgresadoListItemDto> = listarParaLista(numeroControlFilter, scopeUsername)

    fun contarParaDepartamento(academicoUsername: String, segmentoSlug: String? = null): Map<String, Int> {
        val allBase = filtrarEgresadosPorCarreraSiAcademico(egresadoRepository.findAll(), academicoUsername)
        // Coordinador con ?segmento= (menú departamentos): solo Residencia Profesional (Liberar).
        val excluirResidencia =
            bandejaDepartamentoExcluyeResidencia(academicoUsername) && segmentoSlug.isNullOrBlank()
        val afterModalidad =
            if (excluirResidencia) {
                allBase.filter { !esResidenciaProfesional(it) }
            } else {
                allBase
            }
        val all = filtrarBandejaSegmentoCoordinacion(
            aplicarFiltroSegmentoCoordinacion(afterModalidad, segmentoSlug, academicoUsername),
            segmentoSlug,
            academicoUsername,
        )
        val pendientes = all.count {
            it.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) &&
                !enCorreccionAcademico(it)
        }
        val enCorreccion = all.count {
            it.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) &&
                enCorreccionAcademico(it)
        }
        val aprobados = all.count { liberacionRevisionCompletada(it) }
        val todos = all.count { it.fechaEnviadoDepartamentoAcademico != null }
        val sinodales = all.count { it.fechaSolicitudSinodales != null && it.fechaConfirmacionSinodalesRecibidos == null }
        return mapOf(
            "pendientes" to pendientes,
            "en_correccion" to enCorreccion,
            "aprobados" to aprobados,
            "todos" to todos,
            "sinodales_por_asignar" to sinodales,
        )
    }

    fun listarParaDepartamento(estado: String, academicoUsername: String, segmentoSlug: String? = null): List<DepartamentoListItemDto> {
        val allBase = filtrarEgresadosPorCarreraSiAcademico(egresadoRepository.findAll(), academicoUsername)
        val excluirResidencia =
            bandejaDepartamentoExcluyeResidencia(academicoUsername) && segmentoSlug.isNullOrBlank()
        val afterModalidad =
            if (excluirResidencia) {
                allBase.filter { !esResidenciaProfesional(it) }
            } else {
                allBase
            }
        val all = filtrarBandejaSegmentoCoordinacion(
            aplicarFiltroSegmentoCoordinacion(afterModalidad, segmentoSlug, academicoUsername),
            segmentoSlug,
            academicoUsername,
        )
        val norm = estado.trim().lowercase()
        val lista = when (norm) {
            "aprobados" -> all.filter { liberacionRevisionCompletada(it) }
            "sinodales" ->
                all
                    .filter { it.fechaSolicitudSinodales != null }
                    // Primero historial pendiente (sin asignar), luego los ya asignados.
                    .sortedWith(
                        compareBy<Egresado> { it.fechaAsignacionSinodales != null }
                            .thenByDescending { it.fechaSolicitudSinodales ?: Instant.EPOCH }
                            .thenByDescending { it.fecha_actualizacion },
                    )
            "todos" -> all.filter { it.fechaEnviadoDepartamentoAcademico != null }
            "en_correccion" -> all.filter {
                it.fechaEnviadoDepartamentoAcademico != null &&
                    !liberacionRevisionCompletada(it) &&
                    enCorreccionAcademico(it)
            }
            else -> all.filter {
                it.fechaEnviadoDepartamentoAcademico != null &&
                    !liberacionRevisionCompletada(it) &&
                    !enCorreccionAcademico(it)
            }
        }
        val formatter = DateTimeFormatter.ISO_INSTANT
        return lista.map { e ->
            val p = e.datos_personales
            val nombre = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .ifBlank { "—" }
            DepartamentoListItemDto(
                id = e.id?.toString() ?: "",
                nombre = nombre,
                numeroControl = e.numero_control,
                modalidad = e.datos_proyecto.modalidad,
                fechaActualizacion = e.fecha_actualizacion.let { formatter.format(it) },
                fechaEnviadoDepartamento = e.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
                estadoRevision = estadoRevisionDepartamento(e),
                fechaSolicitudSinodales = e.fechaSolicitudSinodales?.let { formatter.format(it) },
                sinodalesAsignados = e.fechaAsignacionSinodales != null,
            )
        }
    }

    /**
     * Usuario con rol `academico` y lista `carreras_asignadas` no vacía solo ve egresados de esas carreras.
     * Si la lista está vacía (usuarios antiguos), no se filtra.
     */
    fun academicoPuedeAccederAEgresado(academicoUsername: String, egresadoId: String): Boolean {
        val e = cargarEgresadoPorId(egresadoId) ?: return false
        // La bandeja “global” de coordinación no lista residencia; con ?segmento= sí (p. ej. ciencias básicas).
        // Sin el matiz siguiente, el coordinador ve filas de residencia pero obtiene 403 al abrir /documento.
        val bloquearResidenciaComoVistaGlobal =
            bandejaDepartamentoExcluyeResidencia(academicoUsername) &&
                !puedeUsarFiltroSegmentoCoordinacion(academicoUsername)
        if (bloquearResidenciaComoVistaGlobal && esResidenciaProfesional(e)) {
            return false
        }
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return true
        if (permitidas.isEmpty()) return true
        return carreraPermitidaParaAcademico(e.datos_personales.carrera, permitidas)
    }

    private fun filtrarEgresadosPorCarreraSiAcademico(egresados: List<Egresado>, academicoUsername: String): List<Egresado> {
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return egresados
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    /**
     * Tras filtrar por carreras del departamento (`?segmento=`): solo **Residencia Profesional** (flujo Liberar).
     * Otras modalidades (p. ej. revisión CAT) no deben aparecer en esas interfaces.
     * Si el slug no existe en catálogo o no tiene carreras, no se aplica (mismo criterio que [aplicarFiltroSegmentoCoordinacion]).
     */
    private fun filtrarBandejaSegmentoCoordinacion(
        egresados: List<Egresado>,
        segmentoSlug: String?,
        username: String,
    ): List<Egresado> {
        val slug = segmentoSlug?.trim()?.lowercase() ?: return egresados
        if (slug.isEmpty()) return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = catalogoRepository.findFirstByTipoAndSlug("departamento", slug) ?: return egresados
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { esResidenciaProfesional(it) }
    }

    /**
     * Coordinador / apoyo / división: filtra la bandeja por slug de departamento académico (`catalogos` tipo departamento).
     * No aplica a usuarios con rol `academico` (usan su asignación en BD).
     */

    private fun aplicarFiltroSegmentoCoordinacion(
        egresados: List<Egresado>,
        segmentoSlug: String?,
        username: String,
    ): List<Egresado> {
        val slug = segmentoSlug?.trim()?.lowercase() ?: return egresados
        if (slug.isEmpty()) return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = catalogoRepository.findFirstByTipoAndSlug("departamento", slug) ?: return egresados
        val nDup = catalogoRepository.countByTipoAndSlug("departamento", slug)
        if (nDup > 1) {
            log.warn(
                "Catálogo 'departamento' slug='{}' tiene {} documentos en MongoDB (duplicados). Se usa uno para filtrar. Limpia duplicados en la colección catalogos.",
                slug,
                nDup,
            )
        }
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun puedeUsarFiltroSegmentoCoordinacion(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        return RolSoporte.tieneAlgunRol(
            u.rol,
            "coordinador",
            "apoyo_titulacion",
            "division_estudios_prof_admin",
            "administrador",
        )
    }

    /** Conjunto de carreras asignadas al académico, o null si no aplica filtro. */
    private fun carrerasFiltroAcademico(username: String): Set<String>? {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return null
        if (!u.rol.trim().equals("academico", ignoreCase = true)) return null
        val permitidas = u.carrerasAsignadas.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (permitidas.isEmpty()) null else permitidas
    }

    /**
     * Académico general (sin segmento/carreras): solo trabaja expedientes de revisión académica
     * de modalidades distintas a Residencia Profesional.
     */
    private fun esAcademicoSoloRevisiones(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (!u.rol.trim().equals("academico", ignoreCase = true)) return false
        val sinSegmento = u.segmentoAcademico?.trim().isNullOrEmpty()
        val sinCarreras = u.carrerasAsignadas.isEmpty()
        return sinSegmento && sinCarreras
    }

    /**
     * Vista global “Coordinación de apoyo a la titulación” (misma idea que el front `esModoRevision`):
     * sin segmento académico ni carreras asignadas — aquí **no** deben listarse expedientes de Residencia Profesional
     * (Liberar / registro-liberación siguen en otros flujos, p. ej. seguimiento).
     *
     * Los académicos con segmento o con carreras asignadas, y cualquier otro alcance distinto, siguen viendo
     * todas las modalidades que correspondan a su filtro.
     */
    private fun bandejaDepartamentoExcluyeResidencia(username: String): Boolean {
        if (esAcademicoSoloRevisiones(username)) return true
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (!u.segmentoAcademico.isNullOrBlank()) return false
        if (u.carrerasAsignadas.isNotEmpty()) return false
        return RolSoporte.tieneAlgunRol(
            u.rol,
            "coordinador",
            "apoyo_titulacion",
            "division_estudios_prof_admin",
            "administrador",
        )
    }

    private fun carreraPermitidaParaAcademico(carrera: String, permitidas: Set<String>): Boolean {
        val c = carrera.trim()
        if (c.isEmpty()) return false
        return permitidas.any { perm -> perm.equals(c, ignoreCase = true) }
    }

    fun obtenerPorId(id: String): EgresadoDetailDto? {
        val objectId = try {
            ObjectId(id)
        } catch (_: Exception) {
            return null
        }
        return try {
            egresadoRepository.findByObjectIdConTimeout(objectId)
                ?.let { verificarYMarcarVencido(it) }
                ?.let { toDetailDto(it) }
        } catch (e: Exception) {
            log.warn("obtenerPorId: timeout/error consultando id={}: {}", id, e.message)
            null
        }
    }

    /** Obtener por número de control (respaldo cuando el id de la lista no coincide). */
    fun obtenerPorNumeroControl(numeroControl: String): EgresadoDetailDto? {
        if (numeroControl.isBlank()) return null
        return expedientePrioritarioParaNumeroControl(numeroControl.trim())
            ?.let { verificarYMarcarVencido(it) }
            ?.let { toDetailDto(it) }
    }

    private fun tieneUltimoPasoSeguimientoCompletado(e: Egresado): Boolean =
        e.fechaConfirmacionDocumentacionEscaneadaRecibida != null

    private fun esExpedienteCerrado(e: Egresado): Boolean {
        val estado = e.estado_general.trim().lowercase()
        return estado == "titulado" || estado == "vencido" || tieneUltimoPasoSeguimientoCompletado(e)
    }

    private fun normalizarModalidadAlta(modalidad: String?): String =
        modalidad?.trim()?.lowercase()?.replace("\\s+".toRegex(), " ").orEmpty()

    private fun expedientesMismoControlRefrescados(numeroControl: String): List<Egresado> {
        if (numeroControl.isBlank()) return emptyList()
        val anchored = "^${Regex.escape(numeroControl.trim())}$"
        return egresadoRepository.listAllByNumeroControlExactCaseInsensitive(anchored)
            .map { verificarYMarcarVencido(it) }
    }

    private fun expedientePrioritarioParaNumeroControl(numeroControl: String): Egresado? {
        val todos = expedientesMismoControlRefrescados(numeroControl)
        if (todos.isEmpty()) return null
        return todos.firstOrNull { !esExpedienteCerrado(it) } ?: todos.first()
    }

    /**
     * Evita duplicar número de control en alta; al editar, [excluirId] es el expediente actual.
     * Si hay expedientes cerrados (titulado/vencido), solo permite nueva alta con modalidad distinta.
     */
    fun verificarDisponibilidadNumeroControlParaAlta(
        numero: String,
        excluirId: String?,
        modalidad: String? = null,
    ): VerificacionDuplicadoAlta {
        val n = numero.trim()
        if (n.isEmpty()) return VerificacionDuplicadoAlta("LIBRE", null)
        val expedientes = expedientesMismoControlRefrescados(n)
            .filter { excluirId == null || it.id?.toString() != excluirId }
        if (expedientes.isEmpty()) return VerificacionDuplicadoAlta("LIBRE", null)

        val abierto = expedientes.firstOrNull { !esExpedienteCerrado(it) }
        if (abierto != null) {
            return VerificacionDuplicadoAlta("BLOQUEADO", "en_proceso")
        }

        val modalidadNueva = normalizarModalidadAlta(modalidad)
        if (modalidadNueva.isBlank()) {
            val estadoCerrado = when (expedientes.firstOrNull()?.estado_general?.trim()?.lowercase()) {
                "vencido" -> "vencido"
                else -> "titulado"
            }
            return VerificacionDuplicadoAlta("BLOQUEADO", estadoCerrado)
        }

        val repetida = expedientes.firstOrNull {
            normalizarModalidadAlta(it.datos_proyecto.modalidad) == modalidadNueva
        }
        if (repetida != null) {
            val estado = if (repetida.estado_general.trim().equals("vencido", ignoreCase = true)) "vencido" else "titulado"
            return VerificacionDuplicadoAlta("BLOQUEADO", estado)
        }

        return VerificacionDuplicadoAlta("LIBRE", null)
    }

    fun validarNuevaAltaNumeroControl(datos: EgresadoRequestDto): String? {
        val verificacion = verificarDisponibilidadNumeroControlParaAlta(
            numero = datos.numero_control,
            excluirId = null,
            modalidad = datos.modalidad,
        )
        if (verificacion.estado != "BLOQUEADO") return null
        return when (verificacion.expedienteEstado?.trim()) {
            "en_proceso" -> "Este número de control ya tiene un expediente en proceso."
            "vencido", "titulado" ->
                "Este número de control ya tuvo un expediente ${verificacion.expedienteEstado}; para darlo de alta nuevamente debes elegir una modalidad distinta."
            else -> "No se puede registrar este número de control."
        }
    }

    fun obtenerPorEgresadoId(egresadoId: ObjectId): EgresadoDetailDto? =
        egresadoRepository.findById(egresadoId).orElse(null)
            ?.let { verificarYMarcarVencido(it) }
            ?.let { toDetailDto(it) }

    fun obtenerPorNumeroControlParaSeguimiento(numeroControl: String): EgresadoDetailDto? =
        obtenerPorNumeroControl(numeroControl)

    fun obtenerDocumentoAdjunto(id: String): DocumentoStream? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        val e = egresadoRepository.findById(objectId).orElse(null) ?: return null
        val adj = e.documento_adjunto
        val gridId = adj.gridfs_id ?: return null
        val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(gridId))) ?: return null
        val resource = gridFsTemplate.getResource(file)
        val input = resource.inputStream
        return DocumentoStream(
            inputStream = input,
            contentType = adj.content_type.ifBlank { "application/octet-stream" },
            fileName = adj.nombre_original.ifBlank { "documento" },
        )
    }

    /** Obtiene el PDF de documentación escaneada enviado por el egresado (paso final). */
    fun obtenerDocumentoEscaneadoProceso(id: String): DocumentoStream? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        val eg = egresadoRepository.findById(objectId).orElse(null) ?: return null
        val entrega = documentacionEscaneadaRepository.findByEgresadoId(objectId) ?: return null
        val archivo = entrega.archivos.firstOrNull() ?: return null
        val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(archivo.gridfsId))) ?: return null
        val resource = gridFsTemplate.getResource(file)
        return DocumentoStream(
            inputStream = resource.inputStream,
            contentType = archivo.contentType.ifBlank { "application/pdf" },
            fileName = archivo.nombreOriginal.ifBlank { "documentacion-escaneada.pdf" },
        )
    }

    fun obtenerDocumentacionEscaneadaBytes(id: String): DocumentoBytes? {
        val doc = obtenerDocumentoEscaneadoProceso(id) ?: return null
        val bytes = doc.inputStream.use { it.readAllBytes() }
        if (bytes.isEmpty()) return null
        return DocumentoBytes(bytes = bytes, contentType = doc.contentType, fileName = doc.fileName)
    }

    /**
     * Sustituye el documento adjunto del egresado (p. ej. desde revisión académica / coordinación).
     * Sube el nuevo archivo a GridFS, actualiza el registro y elimina el binario anterior si existía.
     */
    fun reemplazarDocumentoAdjunto(id: String, archivo: MultipartFile): Boolean {
        if (archivo.isEmpty) return false
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return false
        val ahora = Instant.now()
        val gridFsIdAnterior = existente.documento_adjunto.gridfs_id
        val gridFsIdNuevo = subirArchivo(archivo)
        val nuevoAdj = DocumentoAdjunto(
            gridfs_id = gridFsIdNuevo,
            nombre_original = archivo.originalFilename ?: "",
            content_type = archivo.contentType ?: "application/octet-stream",
            tamanio_bytes = archivo.size,
            fecha_subida = ahora,
        )
        egresadoRepository.save(
            existente.copy(
                documento_adjunto = nuevoAdj,
                fecha_actualizacion = ahora,
            ),
        )
        if (gridFsIdAnterior != null) {
            try {
                gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gridFsIdAnterior)))
            } catch (e: Exception) {
                log.warn("No se pudo eliminar el adjunto GridFS anterior {}: {}", gridFsIdAnterior, e.message)
            }
        }
        log.info("Documento adjunto reemplazado para egresado id={}", id)
        return true
    }

    fun marcarEnviadoDepartamentoAcademico(id: String): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val e = egresadoRepository.findById(objectId).orElse(null) ?: return false
        if (e.fechaEnviadoDepartamentoAcademico != null) return false
        if (!esResidenciaProfesional(e) && e.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) {
            if (e.fechaRecepcionRegistroLiberacionDeptoAcademico == null) return false
        }
        val ahora = Instant.now()

        // Guardar timestamp primero: debe persistir aunque la certificación falle
        val enviado = e.copy(
            fechaEnviadoDepartamentoAcademico = ahora,
            fecha_actualizacion = ahora,
        )
        egresadoRepository.save(enviado)

        // Certificar solo para Residencia Profesional
        if (esResidenciaProfesional(e)) {
            try {
                val resultado = certService.certificarDocumento(e)
                if (resultado != null) {
                    egresadoRepository.save(
                        enviado.copy(
                            documento_adjunto = enviado.documento_adjunto.copy(gridfs_id = resultado.nuevoGridFsId),
                            cert_uuid = resultado.certUuid,
                            cert_hash = resultado.certHash,
                            fechaCertificacion = ahora,
                            fecha_actualizacion = Instant.now(),
                        ),
                    )
                } else {
                    log.warn("Certificacion no completada para egresado id={}: documento faltante o no PDF", id)
                }
            } catch (ex: Exception) {
                log.error("Error al certificar documento para egresado id={}: {}", id, ex.message, ex)
            }
        }

        return true
    }

    fun liberar(id: String): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val e = egresadoRepository.findById(objectId).orElse(null) ?: return false
        if (!esResidenciaProfesional(e)) return false
        if (e.fechaEnviadoDepartamentoAcademico == null) return false
        if (e.fechaRecibidoRegistroLiberacion != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaRecibidoRegistroLiberacion = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    /** Flujo 16 pasos (no residencia): envío de solicitud de registro y anteproyecto al departamento académico. */
    fun solicitarRegistroAnteproyectoNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        if (e.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    /** Confirmación de recepción del trabajo en División de estudios profesionales. */
    fun confirmarRecepcionTrabajoNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        if (e.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return false
        if (e.fechaRecepcionTrabajoDivisionEstudiosProf != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaRecepcionTrabajoDivisionEstudiosProf = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    /** Solicitud de registro y liberación al departamento académico. */
    fun solicitarRegistroLiberacionNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        if (e.fechaRecepcionTrabajoDivisionEstudiosProf == null) return false
        if (e.fechaSolicitudRegistroLiberacionDeptoAcademico != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaSolicitudRegistroLiberacionDeptoAcademico = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    /** Recepción de registro y liberación por el departamento académico. */
    fun confirmarRecepcionRegistroLiberacionNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        if (e.fechaSolicitudRegistroLiberacionDeptoAcademico == null) return false
        if (e.fechaRecepcionRegistroLiberacionDeptoAcademico != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaRecepcionRegistroLiberacionDeptoAcademico = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    fun confirmarRecibidosAnexoXxxiXxxii(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val puede =
            if (!esResidenciaProfesional(e) && e.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) {
                e.fechaLiberacionDocumentoCoordinacionCat != null
            } else {
                e.fechaRecibidoRegistroLiberacion != null
            }
        if (!puede) return false
        if (e.fechaConfirmacionRecibidosAnexoXxxiXxxii != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaConfirmacionRecibidosAnexoXxxiXxxii = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    fun crearAnexo91(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        if (e.fechaConfirmacionRecibidosAnexoXxxiXxxii == null) return null
        val destinatarioServicios =
            env.getProperty("sit.anexo91.destinatario-servicios-escolares")?.trim().orEmpty()
        val destinatarioServiciosFinal =
            if (destinatarioServicios.isNotEmpty()) destinatarioServicios else "ROMEO ALBERTO ANGELES PEREZ"
        val ahora = Instant.now()
        val certId = e.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores =
            construirValoresPlantillaHtml(
                e,
                listOf(
                    "MODALIDAD" to e.datos_proyecto.modalidad,
                    "FECHA_CARTA" to fechaCartaEspanola(ahora),
                    "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(e.datos_proyecto.modalidad),
                    "DESTINATARIO_SERVICIOS_ESCOLARES" to destinatarioServiciosFinal,
                    "QR_CODE" to qrDataUri,
                ),
            )
        val pdf = htmlAnexoPdfService.generarDesdeClasspath("templates/html/anexo-9-1.html", valores)
        if (pdf == null) {
            log.warn("crearAnexo91: no se generó PDF (plantilla/HTML o motor OpenHTMLtoPDF) para egresado id={}", id)
            return null
        }
        // Solo marcar creación en BD si el PDF salió bien (evita expediente “con 9.1” sin archivo).
        if (e.fechaCreacionAnexo91 == null) {
            egresadoRepository.save(e.copy(fechaCreacionAnexo91 = ahora, fecha_actualizacion = ahora))
        }
        return pdf
    }

    fun confirmarEntregaAnexo91(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaCreacionAnexo91 == null) return false
        if (e.fechaConfirmacionEntregaAnexo91 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaConfirmacionEntregaAnexo91 = ahora, fecha_actualizacion = ahora))
        return true
    }

    /** División de estudios registra la solicitud de constancia 9.2 al egresado (sin generar PDF aquí). */
    fun solicitarConstancia92Division(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaConfirmacionEntregaAnexo91 == null) return false
        if (e.fechaSolicitudAnexo92 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaSolicitudAnexo92 = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    fun crearAnexo92(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        if (e.fechaConfirmacionEntregaAnexo91 == null) return null
        // Primera generación: exige solicitud previa de división. Re-descarga: si ya existe fecha de creación.
        if (e.fechaCreacionAnexo92 == null && e.fechaSolicitudAnexo92 == null) return null
        val ahora = Instant.now()
        if (e.fechaCreacionAnexo92 == null) {
            egresadoRepository.save(e.copy(fechaCreacionAnexo92 = ahora, fecha_actualizacion = ahora))
        }
        return generarPdfAnexo(
            titulo = "Anexo 9.2",
            templateProperty = "sit.anexo92.plantilla-docx",
            defaultTemplateClasspath = "templates/anexo-9-2.docx",
            e = e,
            extras = emptyList(),
        )
    }

    fun confirmarRecibidoAnexo92(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaSolicitudAnexo92 == null) return false
        if (e.fechaConfirmacionRecibidoAnexo92 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaConfirmacionRecibidoAnexo92 = ahora, fecha_actualizacion = ahora))
        return true
    }

    fun solicitarSinodales(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaConfirmacionRecibidoAnexo92 == null) return false
        if (e.fechaSolicitudSinodales != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaSolicitudSinodales = ahora, fecha_actualizacion = ahora))
        return true
    }

    fun obtenerSinodales(id: String): SinodalesTribunal? {
        val e = cargarEgresadoPorId(id) ?: return null
        return e.sinodalesTribunal
    }

    fun asignarSinodales(id: String, presidente: String, secretario: String, vocal: String, vocalSuplente: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaSolicitudSinodales == null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                sinodalesTribunal = SinodalesTribunal(
                    presidente = presidente.trim(),
                    secretario = secretario.trim(),
                    vocal = vocal.trim(),
                    vocal_suplente = vocalSuplente.trim(),
                ),
                fechaAsignacionSinodales = ahora,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    fun confirmarSinodalesRecibidos(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaSolicitudSinodales == null || e.fechaAsignacionSinodales == null) return false
        if (e.fechaConfirmacionSinodalesRecibidos != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaConfirmacionSinodalesRecibidos = ahora, fecha_actualizacion = ahora))
        return true
    }

    fun crearAnexo93(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        if (e.fechaAgendaActo93 == null) return null
        val ahora = Instant.now()
        if (e.fechaCreacionAnexo93 == null) {
            egresadoRepository.save(e.copy(fechaCreacionAnexo93 = ahora, fecha_actualizacion = ahora))
        }
        val zona = ZoneId.systemDefault()
        val acto = e.fechaAgendaActo93!!
        val actoLegible = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zona).format(acto)
        val zActo = acto.atZone(zona)
        val diaActo = zActo.dayOfMonth.toString().padStart(2, '0')
        val mesActo =
            nombreMesEspanol(zActo.monthValue).uppercase(Locale.forLanguageTag("es-MX"))
        val anioActo = zActo.year.toString()
        val horaActo = String.format(Locale.ROOT, "%02d:%02d", zActo.hour, zActo.minute)
        val jefeDivisionNombre =
            env.getProperty("sit.anexo93.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
        val certId = e.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores =
            construirValoresPlantillaHtml(
                e,
                listOf(
                    "ACTO_93" to actoLegible,
                    "FECHA_CARTA" to fechaCartaEspanolaAnexo93(Instant.now()),
                    "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(e.datos_proyecto.modalidad),
                    "ACTO_DIA" to diaActo,
                    "ACTO_MES" to mesActo,
                    "ACTO_ANIO" to anioActo,
                    "ACTO_HORA" to horaActo,
                    "PRESIDENTE" to (e.sinodalesTribunal?.presidente ?: ""),
                    "SECRETARIO" to (e.sinodalesTribunal?.secretario ?: ""),
                    "VOCAL" to (e.sinodalesTribunal?.vocal ?: ""),
                    "VOCAL_SUPLENTE" to (e.sinodalesTribunal?.vocal_suplente ?: ""),
                    "JEFE_DIVISION_NOMBRE" to jefeDivisionNombre,
                    "QR_CODE" to qrDataUri,
                ),
            )
        return htmlAnexoPdfService.generarDesdeClasspath("templates/html/anexo-9-3.html", valores)
    }

    /** Marca entrega del anexo 9.3 a sinodales y sustentante (solo tras generar el PDF). */
    fun confirmarEntregaAnexo93(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaCreacionAnexo93 == null || e.fechaConfirmacionEntregaAnexo93 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaConfirmacionEntregaAnexo93 = ahora, fecha_actualizacion = ahora))
        return true
    }


    /** Tras 9.3 (y entrega 9.3 en residencia): la DEP puede solicitar documentación escaneada al egresado. */
    fun solicitarDocumentacionEscaneada(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (!cumplePrerequisitoDocumentacionEscaneada(e)) return false
        if (e.fechaSolicitudDocumentacionEscaneada != null) return false
        if (e.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaSolicitudDocumentacionEscaneada = ahora, fecha_actualizacion = ahora))
        return true
    }

    /** La DEP confirma que recibió la documentación escaneada enviada por el egresado. */
    fun confirmarDocumentacionEscaneadaRecibida(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaEnvioDocumentacionEscaneadaEgresado == null) return false
        if (e.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.copy(fechaConfirmacionDocumentacionEscaneadaRecibida = ahora, fecha_actualizacion = ahora))
        return true
    }

    /**
     * La DEP solicita corregir y reenviar la documentación escaneada.
     * Reinicia el envío del egresado para habilitar una nueva carga y guarda observaciones.
     */
    fun solicitarDocumentacionEscaneadaNuevamente(id: String, observaciones: String?): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaSolicitudDocumentacionEscaneada == null) return false
        if (e.fechaEnvioDocumentacionEscaneadaEgresado == null) return false
        if (e.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val obs = observaciones?.trim()?.takeIf { it.isNotEmpty() }
        val ahora = Instant.now()
        eliminarEntregaEscaneadaAnterior(e.id ?: return false)
        egresadoRepository.save(
            e.copy(
                fechaEnvioDocumentacionEscaneadaEgresado = null,
                fechaConfirmacionDocumentacionEscaneadaRecibida = null,
                fechaSolicitudReenvioDocumentacionEscaneada = ahora,
                observacionesReenvioDocumentacionEscaneada = obs,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    /**
     * Egresado sube uno o más PDF a GridFS y se registra en [documentacion_escaneada].
     * @return mensaje de error o null si todo salió bien.
     */
    fun subirDocumentacionEscaneadaEgresado(egresadoId: String, archivos: List<MultipartFile>?): String? {
        val oid = try {
            ObjectId(egresadoId)
        } catch (_: Exception) {
            return "Identificador inválido."
        }
        val e = egresadoRepository.findById(oid).orElse(null) ?: return "Registro no encontrado."
        if (e.fechaSolicitudDocumentacionEscaneada == null) {
            return "Aún no se ha solicitado la documentación escaneada."
        }
        if (e.fechaConfirmacionDocumentacionEscaneadaRecibida != null) {
            return "Ya se confirmó la recepción; no se pueden enviar más archivos."
        }
        val lista = archivos?.filter { !it.isEmpty } ?: emptyList()
        if (lista.isEmpty()) return "Debes adjuntar al menos un PDF."
        for (a in lista) {
            if (!esPdfValido(a)) {
                return "Solo se permiten archivos PDF (${a.originalFilename ?: "archivo"})."
            }
        }
        eliminarEntregaEscaneadaAnterior(oid)
        val metas = mutableListOf<ArchivoEscaneadoMeta>()
        for (a in lista) {
            val gid = subirArchivo(a)
            metas.add(
                ArchivoEscaneadoMeta(
                    gridfsId = gid,
                    nombreOriginal = a.originalFilename ?: "documento.pdf",
                    contentType = a.contentType ?: "application/pdf",
                    tamanioBytes = a.size,
                ),
            )
        }
        val guardado = documentacionEscaneadaRepository.save(
            DocumentacionEscaneada(
                egresadoId = oid,
                numeroControl = e.numero_control,
                nombreCompleto = nombreCompleto(e),
                carrera = e.datos_personales.carrera.takeIf { it.isNotBlank() },
                archivos = metas,
            ),
        )
        val ahora = Instant.now()
        egresadoRepository.save(
            e.copy(
                fechaEnvioDocumentacionEscaneadaEgresado = ahora,
                fechaSolicitudReenvioDocumentacionEscaneada = null,
                observacionesReenvioDocumentacionEscaneada = null,
                fecha_actualizacion = ahora,
            ),
        )
        log.info(
            "Documentación escaneada: egresadoId={}, entregaId={}, archivos={}",
            egresadoId,
            guardado.id,
            metas.size,
        )
        return null
    }

    private fun cumplePrerequisitoDocumentacionEscaneada(e: Egresado): Boolean {
        if (e.fechaCreacionAnexo93 == null) return false
        if (esResidenciaProfesional(e)) {
            return e.fechaConfirmacionEntregaAnexo93 != null
        }
        return true
    }

    private fun eliminarEntregaEscaneadaAnterior(egresadoId: ObjectId) {
        val ant = documentacionEscaneadaRepository.findByEgresadoId(egresadoId) ?: return
        for (f in ant.archivos) {
            try {
                gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(f.gridfsId)))
            } catch (ex: Exception) {
                log.warn("GridFS {} no eliminado al reemplazar documentación escaneada: {}", f.gridfsId, ex.message)
            }
        }
        try {
            val idAnt = ant.id ?: return
            documentacionEscaneadaRepository.deleteById(idAnt)
        } catch (ex: Exception) {
            log.warn("No se pudo eliminar documentación escaneada anterior: {}", ex.message)
        }
    }

    private fun esPdfValido(archivo: MultipartFile): Boolean {
        val ct = (archivo.contentType ?: "").lowercase()
        val name = (archivo.originalFilename ?: "").lowercase()
        return ct.contains("pdf") || name.endsWith(".pdf")
    }

    fun agendarActo93(id: String, fechaHoraRaw: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (e.fechaConfirmacionSinodalesRecibidos == null) return false

        val inicio = parseFechaHoraLocal(fechaHoraRaw) ?: return false
        val zona = ZoneId.systemDefault()
        val zInicio = inicio.atZone(zona)
        val diaSemana = zInicio.dayOfWeek.value
        if (diaSemana !in 1..5) return false

        val horaInicio = zInicio.toLocalTime()
        val horaFin = horaInicio.plusHours(1)
        val ventanaInicio = LocalTime.of(10, 0)
        val ventanaFin = LocalTime.of(14, 0)
        if (horaInicio < ventanaInicio || horaFin > ventanaFin) return false

        val fin = inicio.plus(1, ChronoUnit.HOURS)
        val candidates = egresadoRepository.findByFechaAgendaActo93Solapando(
            inicio.minus(1, ChronoUnit.HOURS),
            fin,
        )
        if (candidates.any { it.id != e.id }) return false

        val ahora = Instant.now()
        val reagenda = e.fechaAgendaActo93 != null
        if (reagenda) {
            e.id?.let { eliminarEntregaEscaneadaAnterior(it) }
        }

        var gridFinal = e.gridfsIdDocFinal
        var fechaSubidaFinal = e.fechaSubidaDocFinal
        var fechaTitulacionVal = e.fechaTitulacion
        var estado = e.estado_general
        var historial = e.historial_estados
        if (reagenda && e.estado_general == "titulado") {
            gridFinal?.let { gid ->
                try {
                    gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gid)))
                } catch (ex: Exception) {
                    log.warn("GridFS doc final {} no eliminado al reagendar 9.3: {}", gid, ex.message)
                }
            }
            gridFinal = null
            fechaSubidaFinal = null
            fechaTitulacionVal = null
            estado = "registrado"
            historial = historial + HistorialEstado(
                estado = "registrado",
                fecha = ahora,
                observacion = "Reagenda acto protocolario 9.3: se reinician pasos posteriores al agendamiento.",
            )
        }

        egresadoRepository.save(
            e.copy(
                fechaAgendaActo93 = inicio,
                fechaReagendaActo93 = if (reagenda) ahora else null,
                // Reagenda: repetir generación 9.3, entrega, solicitud/envío/confirmación de documentación escaneada.
                fechaCreacionAnexo93 = if (reagenda) null else e.fechaCreacionAnexo93,
                fechaConfirmacionEntregaAnexo93 = if (reagenda) null else e.fechaConfirmacionEntregaAnexo93,
                fechaSolicitudDocumentacionEscaneada = if (reagenda) null else e.fechaSolicitudDocumentacionEscaneada,
                fechaEnvioDocumentacionEscaneadaEgresado = if (reagenda) null else e.fechaEnvioDocumentacionEscaneadaEgresado,
                fechaConfirmacionDocumentacionEscaneadaRecibida = if (reagenda) null else e.fechaConfirmacionDocumentacionEscaneadaRecibida,
                fechaSolicitudReenvioDocumentacionEscaneada = if (reagenda) null else e.fechaSolicitudReenvioDocumentacionEscaneada,
                observacionesReenvioDocumentacionEscaneada = if (reagenda) null else e.observacionesReenvioDocumentacionEscaneada,
                gridfsIdDocFinal = if (reagenda) gridFinal else e.gridfsIdDocFinal,
                fechaSubidaDocFinal = if (reagenda) fechaSubidaFinal else e.fechaSubidaDocFinal,
                fechaTitulacion = if (reagenda) fechaTitulacionVal else e.fechaTitulacion,
                estado_general = if (reagenda) estado else e.estado_general,
                historial_estados = if (reagenda) historial else e.historial_estados,
                fecha_actualizacion = ahora,
            ),
        )
        return true
    }

    fun listarActo93Ocupados(): List<Instant> {
        val zona = ZoneId.systemDefault()
        val inicio = LocalDate.now(zona).minusMonths(1).atStartOfDay(zona).toInstant()
        val fin = LocalDate.now(zona).plusYears(2).atStartOfDay(zona).toInstant()
        return try {
            egresadoRepository.findActo93AgendadoEnRango(inicio, fin)
                .mapNotNull { it.fechaAgendaActo93 }
                .distinct()
                .sorted()
                .take(1500)
        } catch (e: Exception) {
            log.warn("listarActo93Ocupados: timeout/error consultando agenda 9.3: {}", e.message)
            emptyList()
        }
    }

    private fun toDetailDto(e: Egresado): EgresadoDetailDto {
        val p = e.datos_personales
        val doc = e.documentos
        val formatter = DateTimeFormatter.ISO_INSTANT
        return EgresadoDetailDto(
            id = e.id?.toString() ?: "",
            numero_control = e.numero_control,
            datos_personales = DatosPersonalesDto(
                nombre = p.nombre,
                apellido_paterno = p.apellido_paterno,
                apellido_materno = p.apellido_materno ?: "",
                carrera = p.carrera,
                nivel = p.nivel,
                direccion = p.direccion,
                telefono = p.telefono,
                correo_electronico = p.correo_electronico,
            ),
            datos_proyecto = DatosProyectoDto(
                nombre_proyecto = e.datos_proyecto.nombre_proyecto,
                modalidad = e.datos_proyecto.modalidad,
                curso_titulacion = e.datos_proyecto.curso_titulacion,
                asesor_interno = e.datos_proyecto.asesor_interno,
                asesor_externo = e.datos_proyecto.asesor_externo,
                director = e.datos_proyecto.director,
                asesor_1 = e.datos_proyecto.asesor_1,
                asesor_2 = e.datos_proyecto.asesor_2,
            ),
            documentos = DocumentosDto(
                anexo_xxxi = doc.anexo_xxxi.let { AnexoDto(it.fecha_registro?.let { formatter.format(it) }, it.estado) },
                constancia_no_inconveniencia = doc.constancia_no_inconveniencia.let { ConstanciaDto(it.fecha_expedicion?.let { formatter.format(it) }, it.estado) },
            ),
            documento_adjunto = e.documento_adjunto.let { adj ->
                if (adj.nombre_original.isNotBlank() || adj.tamanio_bytes > 0) {
                    DocumentoAdjuntoDto(nombre_original = adj.nombre_original, tamanio_bytes = adj.tamanio_bytes)
                } else null
            },
            estado_general = e.estado_general,
            fecha_creacion = e.fechaCreacion.let { formatter.format(it) },
            fecha_actualizacion = e.fecha_actualizacion.let { formatter.format(it) },
            fecha_envio_solicitud_registro_anteproyecto_depto_academico =
                e.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
            fecha_recepcion_trabajo_division_estudios_prof =
                e.fechaRecepcionTrabajoDivisionEstudiosProf?.let { formatter.format(it) },
            fecha_solicitud_registro_liberacion_depto_academico =
                e.fechaSolicitudRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
            fecha_recepcion_registro_liberacion_depto_academico =
                e.fechaRecepcionRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
            fecha_liberacion_documento_coordinacion_cat =
                e.fechaLiberacionDocumentoCoordinacionCat?.let { formatter.format(it) },
            fecha_enviado_departamento_academico = e.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
            fecha_recibido_registro_liberacion = e.fechaRecibidoRegistroLiberacion?.let { formatter.format(it) },
            fecha_confirmacion_recibidos_anexo_xxxi_xxxii = e.fechaConfirmacionRecibidosAnexoXxxiXxxii?.let { formatter.format(it) },
            fecha_creacion_anexo_9_1 = e.fechaCreacionAnexo91?.let { formatter.format(it) },
            fecha_confirmacion_entrega_anexo_9_1 = e.fechaConfirmacionEntregaAnexo91?.let { formatter.format(it) },
            fecha_solicitud_anexo_9_2 = e.fechaSolicitudAnexo92?.let { formatter.format(it) },
            fecha_creacion_anexo_9_2 = e.fechaCreacionAnexo92?.let { formatter.format(it) },
            fecha_confirmacion_recibido_anexo_9_2 = e.fechaConfirmacionRecibidoAnexo92?.let { formatter.format(it) },
            fecha_solicitud_sinodales = e.fechaSolicitudSinodales?.let { formatter.format(it) },
            fecha_asignacion_sinodales = e.fechaAsignacionSinodales?.let { formatter.format(it) },
            fecha_confirmacion_sinodales_recibidos = e.fechaConfirmacionSinodalesRecibidos?.let { formatter.format(it) },
            fecha_agenda_acto_9_3 = e.fechaAgendaActo93?.let { formatter.format(it) },
            fecha_reagenda_acto_9_3 = e.fechaReagendaActo93?.let { formatter.format(it) },
            fecha_creacion_anexo_9_3 = e.fechaCreacionAnexo93?.let { formatter.format(it) },
            fecha_confirmacion_entrega_anexo_9_3 = e.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
            fecha_titulacion = e.fechaTitulacion?.let { formatter.format(it) },
            tiene_doc_final = e.gridfsIdDocFinal != null,
            fecha_solicitud_documentacion_escaneada = e.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
            fecha_envio_documentacion_escaneada_egresado = e.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
            fecha_confirmacion_documentacion_escaneada_recibida = e.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
            fecha_solicitud_reenvio_documentacion_escaneada =
                e.fechaSolicitudReenvioDocumentacionEscaneada?.let { formatter.format(it) },
            observaciones_reenvio_documentacion_escaneada = e.observacionesReenvioDocumentacionEscaneada,
        )
    }

    private fun subirArchivo(archivo: MultipartFile): ObjectId {
        val bytes = archivo.bytes
        val isPdf  = bytes.size > 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        val isDocx = bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        require(isPdf || isDocx) { "Solo se aceptan archivos PDF o Word (.docx)" }
        val contentType = if (isPdf) "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val id = gridFsTemplate.store(
            bytes.inputStream(),
            archivo.originalFilename ?: "documento",
            contentType,
            null,
        )
        return id as ObjectId
    }

    private fun parseFecha(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseFechaHoraLocal(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        val raw = s.trim()
        val normalized = if (!raw.contains('T') && raw.contains(' ')) raw.replaceFirst(" ", "T") else raw
        return try {
            LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun cargarEgresadoPorId(id: String): Egresado? {
        val objectId = try {
            ObjectId(id)
        } catch (_: Exception) {
            return null
        }
        return try {
            egresadoRepository.findByObjectIdConTimeout(objectId)
        } catch (e: Exception) {
            log.warn("cargarEgresadoPorId: timeout/error consultando id={}: {}", id, e.message)
            null
        }
    }

    private fun esResidenciaProfesional(e: Egresado): Boolean {
        val nombre = e.datos_proyecto.modalidad.trim()
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
    }

    /** Revisión académica/CAT: liberación vía campo clásico o flujo extendido no residencia. */
    private fun liberacionRevisionCompletada(e: Egresado): Boolean =
        e.fechaRecibidoRegistroLiberacion != null || e.fechaLiberacionDocumentoCoordinacionCat != null

    private fun ultimaRevisionResultado(e: Egresado): String? {
        val oid = e.id ?: return null
        return revisionService.ultimaRevision(oid)?.resultado
    }

    /** No residencia: última revisión académica con observaciones y aún sin “liberación/aprobación” en expediente. */
    private fun enCorreccionAcademico(e: Egresado): Boolean =
        !esResidenciaProfesional(e) && ultimaRevisionResultado(e) == "observaciones"

    private fun estadoRevisionDepartamento(e: Egresado): String {
        if (liberacionRevisionCompletada(e)) return "aprobado"
        if (enCorreccionAcademico(e)) return "con_observaciones"
        return "pendiente"
    }

    private fun nombreCompleto(e: Egresado): String =
        listOf(e.datos_personales.nombre, e.datos_personales.apellido_paterno, e.datos_personales.apellido_materno)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { e.numero_control }

    /** Valores para plantillas HTML (9.1 y 9.3): fecha legible en zona local. */
    private fun construirValoresPlantillaHtml(e: Egresado, extras: List<Pair<String, String>>): Map<String, String> {
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val valores =
            mutableMapOf(
                "NOMBRE" to nombreCompleto(e),
                "NUMERO_CONTROL" to e.numero_control,
                "CARRERA" to e.datos_personales.carrera,
                "NIVEL" to e.datos_personales.nivel.trim().ifBlank { "—" },
                "PROYECTO" to e.datos_proyecto.nombre_proyecto,
                "FECHA_GENERACION" to fmt.format(Instant.now()),
            )
        for ((k, v) in extras) valores[k] = v
        expandirAliasPlantilla(valores)
        return valores
    }

    private fun fechaCartaEspanola(instant: Instant): String {
        val z = instant.atZone(ZoneId.systemDefault())
        val mes = nombreMesEspanol(z.monthValue).uppercase(Locale.forLanguageTag("es-MX"))
        return "${z.dayOfMonth} de $mes de ${z.year}"
    }

    /** Fecha en encabezado del Anexo 9.3: “01 de Julio del 2021” (mes en formato título, “del” antes del año). */
    private fun fechaCartaEspanolaAnexo93(instant: Instant): String {
        val z = instant.atZone(ZoneId.systemDefault())
        val mes = nombreMesEspanol(z.monthValue)
        val dia = z.dayOfMonth.toString().padStart(2, '0')
        return "$dia de $mes del ${z.year}"
    }


    private fun nombreMesEspanol(monthValue1to12: Int): String {
        val meses =
            listOf(
                "enero", "febrero", "marzo", "abril", "mayo", "junio",
                "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
            )
        val m = meses.getOrElse(monthValue1to12 - 1) { "—" }
        return m.replaceFirstChar { it.uppercaseChar() }
    }

    /** Texto entre paréntesis tras “Titulación integral” en formatos ITVO. */
    private fun textoOpcionTitulacionIntegral(modalidad: String): String {
        val m = modalidad.trim().lowercase(Locale.ROOT)
        return when {
            m.contains("residencia") -> "REPORTE FINAL DE RESIDENCIA PROFESIONAL"
            m.contains("tesina") -> "TESINA"
            m.contains("ceneval") -> "EXAMEN CENEVAL"
            else -> modalidad.uppercase(Locale.forLanguageTag("es-MX"))
        }
    }

    private fun generarPdfAnexo(
        titulo: String,
        templateProperty: String,
        defaultTemplateClasspath: String,
        e: Egresado,
        extras: List<Pair<String, String>>,
    ): ByteArray? {
        val valores = mutableMapOf(
            "NOMBRE" to nombreCompleto(e),
            "NUMERO_CONTROL" to e.numero_control,
            "CARRERA" to e.datos_personales.carrera,
            "PROYECTO" to e.datos_proyecto.nombre_proyecto,
            "FECHA_GENERACION" to DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        )
        for ((k, v) in extras) valores[k] = v
        expandirAliasPlantilla(valores)

        val docxTemplate = cargarPlantillaDocx(templateProperty, defaultTemplateClasspath)
        if (docxTemplate == null) {
            log.warn(
                "No se pudo cargar la plantilla DOCX para {}: revisa la propiedad {} (ruta absoluta al .docx) o el recurso en classpath {}.",
                titulo,
                templateProperty,
                defaultTemplateClasspath,
            )
            return null
        }
        val pdfPlantilla = convertirDocxTemplateAPdf(docxTemplate, valores)
        if (pdfPlantilla != null) return pdfPlantilla
        log.warn(
            "LibreOffice no generó PDF para {}. Revisa que LibreOffice esté instalado y sit.soffice.path apunte a soffice/soffice.exe.",
            titulo,
        )
        return null
    }

    private fun cargarPlantillaDocx(templateProperty: String, defaultTemplateClasspath: String): ByteArray? {
        val rutaConfig = env.getProperty(templateProperty)?.trim().orEmpty()
        if (rutaConfig.isNotEmpty()) {
            try {
                val path = Paths.get(rutaConfig)
                if (Files.isRegularFile(path)) return Files.readAllBytes(path)
            } catch (_: Exception) { /* continuar */ }
            val f = File(rutaConfig)
            if (f.exists() && f.isFile) return Files.readAllBytes(f.toPath())
            log.warn("Plantilla configurada no existe o no es legible: {}={}", templateProperty, rutaConfig)
        }
        return try {
            ClassPathResource(defaultTemplateClasspath).inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun ejecutarLibreOfficeConvert(soffice: Array<String>, tmpDir: File, docxFile: File, pdfFile: File, conPerfilAislado: Boolean): Boolean {
        val args = mutableListOf(*soffice, "--headless")
        if (conPerfilAislado) {
            args.add("-env:UserInstallation=file:///${tmpDir.absolutePath.replace('\\', '/')}/lo-profile")
        }
        args.addAll(listOf("--convert-to", "pdf:writer_pdf_Export", "--outdir", tmpDir.absolutePath, docxFile.absolutePath))
        return try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val salida = proc.inputStream.use { stream ->
                String(stream.readAllBytes(), StandardCharsets.UTF_8)
            }
            val termino = proc.waitFor(90, TimeUnit.SECONDS)
            val exit = if (termino) proc.exitValue() else -1
            val ok = termino && exit == 0 && pdfFile.exists()
            if (!ok) {
                log.warn(
                    "LibreOffice (perfilAislado={}): exit={}, termino={}, exe={}, salida={}",
                    conPerfilAislado,
                    exit,
                    termino,
                    soffice.joinToString(),
                    salida.take(2000),
                )
            }
            ok
        } catch (ex: Exception) {
            log.warn("LibreOffice error: {}", ex.message)
            false
        }
    }

    private fun convertirDocxTemplateAPdf(docxBytes: ByteArray, valores: Map<String, String>): ByteArray? {
        val docxRellenado = reemplazarMarcadoresDocx(docxBytes, valores)
        val tmpDir = Files.createTempDirectory("sit-anexos-").toFile()
        val docxFile = File(tmpDir, "anexo.docx")
        val pdfFile = File(tmpDir, "anexo.pdf")
        val soffice = comandoSoffice()
        return try {
            Files.write(docxFile.toPath(), docxRellenado)
            when {
                ejecutarLibreOfficeConvert(soffice, tmpDir, docxFile, pdfFile, conPerfilAislado = true) -> Files.readAllBytes(pdfFile.toPath())
                ejecutarLibreOfficeConvert(soffice, tmpDir, docxFile, pdfFile, conPerfilAislado = false) -> Files.readAllBytes(pdfFile.toPath())
                else -> null
            }
        } catch (ex: Exception) {
            log.error("Error al convertir DOCX a PDF: {}", ex.message, ex)
            null
        } finally {
            pdfFile.delete()
            docxFile.delete()
            File(tmpDir, "lo-profile").deleteRecursively()
            tmpDir.delete()
        }
    }

    /** Ruta a soffice: propiedad, variable de entorno, rutas típicas en Windows, o PATH. */
    private fun comandoSoffice(): Array<String> {
        val prop = env.getProperty("sit.soffice.path")?.trim().orEmpty()
        if (prop.isNotEmpty() && File(prop).isFile) return arrayOf(prop)
        System.getenv("SIT_SOFFICE")?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile() }?.let { return arrayOf(it) }
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            listOf(
                """C:\Program Files\LibreOffice\program\soffice.exe""",
                """C:\Program Files (x86)\LibreOffice\program\soffice.exe""",
            ).firstOrNull { File(it).isFile }?.let { return arrayOf(it) }
        }
        return arrayOf("soffice")
    }

    private fun baseUrlCert(): String =
        env.getProperty("sit.cert.base-url")?.trim().takeUnless { it.isNullOrBlank() } ?: "http://localhost:8080"

    private fun generarQrDataUri(contenido: String): String {
        return try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, 220, 220, hints)
            val bos = ByteArrayOutputStream()
            MatrixToImageWriter.writeToStream(matrix, "PNG", bos)
            val b64 = Base64.getEncoder().encodeToString(bos.toByteArray())
            "data:image/png;base64,$b64"
        } catch (_: Exception) {
            // Evita romper anexos por fallo del generador QR.
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
        }
    }

    fun subirDocumentoFinal(numeroControl: String, archivo: MultipartFile): Boolean {
        val e = egresadoRepository.findByNumeroControl(numeroControl.trim()) ?: return false
        if (e.fechaCreacionAnexo93 == null) return false
        if (e.estado_general == "titulado") return false
        val ahora = Instant.now()
        val gridFsId = subirArchivo(archivo)
        egresadoRepository.save(
            e.copy(
                gridfsIdDocFinal = gridFsId,
                fechaSubidaDocFinal = ahora,
                fechaTitulacion = ahora,
                estado_general = "titulado",
                fecha_actualizacion = ahora,
                historial_estados = e.historial_estados + HistorialEstado(
                    estado = "titulado",
                    fecha = ahora,
                    observacion = "Documentos finales entregados por el egresado",
                ),
            ),
        )
        log.info("Egresado numero_control={} marcado como titulado", numeroControl)
        return true
    }

    private fun mesesPorModalidad(modalidad: String): Long? {
        val m = modalidad.trim().lowercase()
        return when {
            m.contains("residencia")   -> 6L
            m.contains("monograf")     -> 18L
            m.contains("tesina")       -> 12L
            m.contains("tesis")        -> 12L
            m.contains("curso")        -> 12L
            m.contains("investigaci")  -> 12L
            m.contains("ceneval")      -> null
            else                       -> 12L
        }
    }

    private fun verificarYMarcarVencido(e: Egresado): Egresado {
        if (e.estado_general == "titulado" || e.estado_general == "vencido") return e
        val meses = mesesPorModalidad(e.datos_proyecto.modalidad) ?: return e
        val fechaInicio = e.fechaEnviadoDepartamentoAcademico ?: e.fechaCreacion
        val limiteLocal = fechaInicio.atZone(ZoneId.systemDefault()).toLocalDate().plusMonths(meses)
        if (LocalDate.now(ZoneId.systemDefault()) > limiteLocal) {
            val ahora = Instant.now()
            val vencido = e.copy(
                estado_general = "vencido",
                fecha_actualizacion = ahora,
                historial_estados = e.historial_estados + HistorialEstado(
                    estado = "vencido",
                    fecha = ahora,
                    observacion = "Plazo de $meses mes(es) expirado",
                ),
            )
            egresadoRepository.save(vencido)
            log.info("Egresado id={} marcado como vencido (plazo {} meses expirado)", e.id, meses)
            return vencido
        }
        return e
    }

    /** Igual nombre de campo en plantillas ITVO / variaciones. */
    private fun expandirAliasPlantilla(valores: MutableMap<String, String>) {
        val nombre = valores["NOMBRE"].orEmpty()
        val control = valores["NUMERO_CONTROL"].orEmpty()
        val carrera = valores["CARRERA"].orEmpty()
        val proyecto = valores["PROYECTO"].orEmpty()
        valores.putIfAbsent("NOMBRE_COMPLETO", nombre)
        valores.putIfAbsent("NOMBRE_ALUMNO", nombre)
        valores.putIfAbsent("ALUMNO", nombre)
        valores.putIfAbsent("CONTROL", control)
        valores.putIfAbsent("NO_CONTROL", control)
        valores.putIfAbsent("NUMERO_DE_CONTROL", control)
        valores.putIfAbsent("CARRERA_COMPLETA", carrera)
        valores.putIfAbsent("NOMBRE_PROYECTO", proyecto)
    }

    /**
     * Reemplaza marcadores sin reescribir el DOCX con POI (evita romper el formato de plantillas complejas).
     * Procesa los XML del paquete Office Open XML.
     */
    private fun reemplazarMarcadoresDocx(docxBytes: ByteArray, valores: Map<String, String>): ByteArray {
        return try {
            reemplazarMarcadoresEnZipDocx(docxBytes, valores)
        } catch (ex: Exception) {
            log.warn("Reemplazo por ZIP falló ({}), se intenta Apache POI.", ex.message)
            reemplazarMarcadoresDocxPoi(docxBytes, valores)
        }
    }

    private fun esXmlWordParaMarcadores(entryName: String): Boolean {
        if (!entryName.startsWith("word/") || !entryName.endsWith(".xml")) return false
        val base = entryName.removePrefix("word/").substringBefore(".xml")
        return base == "document" ||
            base.startsWith("header") ||
            base.startsWith("footer") ||
            base == "footnotes" ||
            base == "endnotes"
    }

    private fun escapeXmlTextoContenido(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun aplicarMarcadoresEnTextoXml(original: String, valores: Map<String, String>): String {
        var texto = original
        valores.forEach { (k, v) ->
            val safe = escapeXmlTextoContenido(v.ifBlank { "—" })
            texto = texto.replace("{{$k}}", safe, ignoreCase = true)
            texto = texto.replace("$" + "{" + k + "}", safe, ignoreCase = true)
            texto = texto.replace("<<$k>>", safe, ignoreCase = true)
            texto = texto.replace("[$k]", safe, ignoreCase = true)
        }
        return texto
    }

    private fun reemplazarMarcadoresEnZipDocx(docxBytes: ByteArray, valores: Map<String, String>): ByteArray {
        val outBytes = ByteArrayOutputStream()
        ZipOutputStream(outBytes).use { zOut ->
            ZipInputStream(ByteArrayInputStream(docxBytes)).use { zIn ->
                var entry = zIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val raw = zIn.readAllBytes()
                    val processed =
                        if (!entry.isDirectory && esXmlWordParaMarcadores(name)) {
                            val xml = String(raw, StandardCharsets.UTF_8)
                            aplicarMarcadoresEnTextoXml(xml, valores).toByteArray(StandardCharsets.UTF_8)
                        } else {
                            raw
                        }
                    val ze = ZipEntry(name)
                    ze.time = entry.time
                    zOut.putNextEntry(ze)
                    zOut.write(processed)
                    zOut.closeEntry()
                    entry = zIn.nextEntry
                }
            }
        }
        return outBytes.toByteArray()
    }

    private fun reemplazarMarcadoresDocxPoi(docxBytes: ByteArray, valores: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument(ByteArrayInputStream(docxBytes)).use { doc ->
            fun textoParrafo(p: org.apache.poi.xwpf.usermodel.XWPFParagraph): String =
                buildString { p.runs.forEach { r -> append(r.getText(0) ?: "") } }

            fun reemplazarParrafo(p: org.apache.poi.xwpf.usermodel.XWPFParagraph) {
                val original = textoParrafo(p)
                if (original.isBlank()) return
                var texto = original
                valores.forEach { (k, v) ->
                    val valSafe = v.ifBlank { "—" }
                    texto = texto
                        .replace("{{$k}}", valSafe, ignoreCase = true)
                        .replace("$" + "{" + k + "}", valSafe, ignoreCase = true)
                        .replace("<<$k>>", valSafe, ignoreCase = true)
                }
                if (texto == original) return
                while (p.runs.isNotEmpty()) p.removeRun(0)
                p.createRun().setText(texto, 0)
            }

            doc.paragraphs.forEach(::reemplazarParrafo)
            doc.tables.forEach { t ->
                t.rows.forEach { r ->
                    r.tableCells.forEach { c ->
                        c.paragraphs.forEach(::reemplazarParrafo)
                    }
                }
            }
            doc.write(out)
        }
        return out.toByteArray()
    }

}
