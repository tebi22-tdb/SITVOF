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
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.domain.SinodalesTribunal
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DocumentacionEscaneadaRepository
import com.sit_titulacion.sit.repository.DocenteRepository
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

data class DocumentoBytes(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
)

data class DepartamentoCounts(
    val pendientes: Int,
    val en_correccion: Int,
    val aprobados: Int,
    val todos: Int,
    val sinodales_por_asignar: Int,
)

data class VerificacionDuplicadoAlta(
    val estado: String,
    val expedienteEstado: String?,
    val egresadoId: String? = null,
)

@Service
class EgresadoService(
    private val egresadoRepository: EgresadoRepository,
    private val documentacionEscaneadaRepository: DocumentacionEscaneadaRepository,
    private val catalogoRepository: CatalogoRepository,
    private val usuarioRepository: UsuarioRepository,
    private val docenteRepository: DocenteRepository,
    private val emailService: EmailService,
    private val residenciaPlazoNotificacionService: ResidenciaPlazoNotificacionService,
    private val noResidenciaPlazoNotificacionService: NoResidenciaPlazoNotificacionService,
    private val gridFsTemplate: GridFsTemplate,
    private val env: Environment,
    private val htmlAnexoPdfService: HtmlAnexoPdfService,
    private val revisionService: RevisionService,
    private val certService: CertificacionPdfService,
) {
    private val log = LoggerFactory.getLogger(EgresadoService::class.java)

    /**
     * Zona usada para interpretar la fecha/hora del acto 9.3 enviada sin offset (como en flatpickr)
     * y para validar ventana horaria / PDF del acto. Debe coincidir con la zona institucional, no con la del host (p. ej. UTC en contenedor).
     * Override: `sit.acto93.zona-horaria` (ej. `America/Mazatlan`).
     */
    private val zonaActo93: ZoneId by lazy {
        val key = env.getProperty("sit.acto93.zona-horaria", "America/Mexico_City")!!.trim()
        try {
            ZoneId.of(key)
        } catch (_: Exception) {
            log.warn("sit.acto93.zona-horaria inválida '{}', se usa America/Mexico_City", key)
            ZoneId.of("America/Mexico_City")
        }
    }

    fun crear(datos: EgresadoRequestDto, archivo: MultipartFile?): Egresado {
        val ahora = Instant.now()
        val documentoAdjunto = buildDocumentoAdjunto(archivo, ahora)

        val proceso = ProcesoTitulacion(
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
                anexo_xxxi = AnexoXxxi(fecha_registro = parseFecha(datos.fechaRegistroAnexo), estado = "pendiente"),
                constancia_no_inconveniencia = ConstanciaNoInconveniencia(fecha_expedicion = parseFecha(datos.fechaExpedicionConstancia), estado = "pendiente"),
            ),
            documento_adjunto = documentoAdjunto,
            estado_general = "registrado",
            historial_estados = listOf(HistorialEstado(estado = "registrado", fecha = ahora, observacion = "Registro inicial del alumno")),
            fechaCreacion = ahora,
            fecha_actualizacion = ahora,
        )

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
            fechaCreacion = ahora,
            fecha_actualizacion = ahora,
            procesos = listOf(proceso),
        )
        val guardado = egresadoRepository.save(egresado)
        log.info("Egresado guardado: id={}, numero_control={}", guardado.id, guardado.numero_control)
        notificarPlazoResidenciaSiAplica(guardado)
        return guardado
    }

    /**
     * Agrega un nuevo proceso de titulación a un egresado existente cuyo proceso anterior
     * ya está cerrado (vencido o titulado). El proceso anterior queda en el historial.
     */
    fun activarNuevoProceso(id: String, datos: EgresadoRequestDto, archivo: MultipartFile?): String? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return "ID inválido." }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return "Egresado no encontrado."
        val procesoActual = existente.procesoActivoOrNull()
        if (procesoActual != null) {
            val estado = procesoActual.estado_general.trim().lowercase()
            if (estado == "titulado") return "El egresado ya concluyó su proceso de titulación."
            if (estado != "vencido") return "El proceso actual aún no está cerrado (estado: $estado)."
        }
        val modalidadNueva = datos.modalidad.trim().lowercase()
        val repetida = existente.procesos.any { p ->
            p.datos_proyecto.modalidad.trim().lowercase() == modalidadNueva &&
                (p.estado_general.trim().lowercase() == "vencido" || p.estado_general.trim().lowercase() == "titulado")
        }
        if (repetida) return "Ya existe un proceso cerrado con la modalidad '${datos.modalidad}'. Elige una diferente."

        val ahora = Instant.now()
        val nuevoProceso = ProcesoTitulacion(
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
                anexo_xxxi = AnexoXxxi(fecha_registro = parseFecha(datos.fechaRegistroAnexo), estado = "pendiente"),
                constancia_no_inconveniencia = ConstanciaNoInconveniencia(fecha_expedicion = parseFecha(datos.fechaExpedicionConstancia), estado = "pendiente"),
            ),
            documento_adjunto = buildDocumentoAdjunto(archivo, ahora),
            estado_general = "registrado",
            historial_estados = listOf(HistorialEstado(estado = "registrado", fecha = ahora, observacion = "Nuevo proceso de titulación iniciado")),
            fechaCreacion = ahora,
            fecha_actualizacion = ahora,
        )
        val guardado = egresadoRepository.save(
            existente.copy(
                procesos = existente.procesos + nuevoProceso,
                fecha_actualizacion = ahora,
            )
        )
        log.info("Nuevo proceso activado para egresado id={}, modalidad={}", id, datos.modalidad)
        notificarPlazoResidenciaSiAplica(guardado)
        return null
    }

    fun actualizar(id: String, datos: EgresadoRequestDto, archivo: MultipartFile?): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return false
        val p = existente.procesoActivoOrNull() ?: return false
        val ahora = Instant.now()
        val documentoAdjunto = when {
            datos.quitarArchivo == true -> DocumentoAdjunto()
            archivo != null && !archivo.isEmpty -> buildDocumentoAdjunto(archivo, ahora)
            else -> p.documento_adjunto
        }
        val procesoActualizado = p.copy(
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
                anexo_xxxi = AnexoXxxi(fecha_registro = parseFecha(datos.fechaRegistroAnexo), estado = p.documentos.anexo_xxxi.estado),
                constancia_no_inconveniencia = ConstanciaNoInconveniencia(fecha_expedicion = parseFecha(datos.fechaExpedicionConstancia), estado = p.documentos.constancia_no_inconveniencia.estado),
            ),
            documento_adjunto = documentoAdjunto,
            fecha_actualizacion = ahora,
        )
        egresadoRepository.save(
            existente.actualizarProcesoActivo(procesoActualizado).copy(
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
            )
        )
        log.info("Egresado actualizado: id={}, numero_control={}", id, datos.numero_control)
        return true
    }

    fun eliminar(id: String): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        if (!egresadoRepository.existsById(objectId)) return false
        egresadoRepository.deleteById(objectId)
        log.info("Egresado eliminado: id={}", id)
        return true
    }

    fun listarTodos(): List<EgresadoResponseDto> =
        egresadoRepository.findAll().map { e ->
            EgresadoResponseDto(id = e.id?.toString() ?: "", numero_control = e.numero_control)
        }

    fun listarParaLista(numeroControlFilter: String?): List<EgresadoListItemDto> =
        listarParaLista(numeroControlFilter, null)

    fun listarParaLista(numeroControlFilter: String?, scopeUsername: String?): List<EgresadoListItemDto> {
        val porReciente =
            compareByDescending<Egresado> { it.fechaCreacion }.thenByDescending { it.id }
        val base = if (numeroControlFilter.isNullOrBlank()) {
            egresadoRepository.findAll().sortedWith(porReciente)
        } else {
            egresadoRepository.findByNumeroControlContaining(numeroControlFilter.trim()).sortedWith(porReciente)
        }
        val lista = if (scopeUsername.isNullOrBlank()) {
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
            val pr = e.procesoActivoOrNull()
            val nombreCompleto = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                .filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "—" }
            EgresadoListItemDto(
                id = e.id?.toString() ?: "",
                numero_control = e.numero_control,
                nombre = nombreCompleto,
                carrera = p.carrera.ifBlank { "—" },
                modalidad = pr?.datos_proyecto?.modalidad?.ifBlank { "—" } ?: "—",
                fecha_creacion = formatter.format(e.fechaCreacion),
                fecha_registro_anexo_xxxi = pr?.documentos?.anexo_xxxi?.fecha_registro?.let { formatter.format(it) },
                fecha_envio_solicitud_registro_anteproyecto_depto_academico =
                    pr?.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
                fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii =
                    pr?.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii?.let { formatter.format(it) },
                fecha_solicitud_registro_liberacion_depto_academico =
                    pr?.fechaSolicitudRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
                fecha_enviado_departamento_academico = pr?.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
                fecha_recibido_registro_liberacion = pr?.fechaRecibidoRegistroLiberacion?.let { formatter.format(it) },
                fecha_confirmacion_recibidos_anexo_xxxi_xxxii = pr?.fechaConfirmacionRecibidosAnexoXxxiXxxii?.let { formatter.format(it) },
                fecha_actualizacion = formatter.format(e.fecha_actualizacion),
                fecha_creacion_anexo_9_3 = pr?.fechaCreacionAnexo93?.let { formatter.format(it) },
                fecha_confirmacion_entrega_anexo_9_3 = pr?.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
                fecha_solicitud_documentacion_escaneada = pr?.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
                fecha_envio_documentacion_escaneada_egresado = pr?.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
                fecha_confirmacion_documentacion_escaneada_recibida = pr?.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
            )
        }
    }

    fun listarParaLista(
        numeroControlFilter: String?,
        @Suppress("UNUSED_PARAMETER") fechaDesde: Instant?,
        @Suppress("UNUSED_PARAMETER") fechaHasta: Instant?,
        @Suppress("UNUSED_PARAMETER") tipoFiltro: String?,
        scopeUsername: String? = null,
    ): List<EgresadoListItemDto> = listarParaLista(numeroControlFilter, scopeUsername)

    fun contarParaDepartamento(academicoUsername: String, segmentoSlug: String? = null): Map<String, Int> {
        val allBase = filtrarEgresadosPorCarreraSiAcademico(egresadoRepository.findAll(), academicoUsername)
        val excluirResidencia = bandejaDepartamentoExcluyeResidencia(academicoUsername) && segmentoSlug.isNullOrBlank()
        val afterModalidad = if (excluirResidencia) allBase.filter { !esResidenciaProfesional(it) } else allBase
        val porCarrera = aplicarFiltroSegmentoCoordinacion(afterModalidad, segmentoSlug, academicoUsername)
        // Solo residencia para pendientes/aprobados (flujo Liberar).
        // Sinodales y Todos incluyen todos los egresados del departamento.
        val allLiberar = filtrarBandejaSegmentoCoordinacion(porCarrera, segmentoSlug, academicoUsername)
        val pendientes = allLiberar.count {
            it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) && !enCorreccionAcademico(it)
        }
        val enCorreccion = allLiberar.count {
            it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) && enCorreccionAcademico(it)
        }
        val aprobados = allLiberar.count { liberacionRevisionCompletada(it) }
        val todos = porCarrera.count { it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null }
        val sinodales = porCarrera.count {
            val pr = it.procesoActivoOrNull()
            pr?.fechaSolicitudSinodales != null && pr.fechaAsignacionSinodales == null
        }
        val totalAnteproyecto = porCarrera.count {
            val pr = it.procesoActivoOrNull()
            pr != null && !esResidenciaProfesional(it) &&
                pr.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null &&
                pr.fechaEnviadoDepartamentoAcademico == null
        }
        val anteproyecto = porCarrera.count {
            val pr = it.procesoActivoOrNull()
            pr != null && !esResidenciaProfesional(it) &&
                pr.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null &&
                pr.fechaRegistradoDepartamento == null &&
                pr.fechaEnviadoDepartamentoAcademico == null
        }
        val totalLiberacionProducto = porCarrera.count {
            val pr = it.procesoActivoOrNull()
            pr != null && !esResidenciaProfesional(it) &&
                pr.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null &&
                pr.fechaEnviadoDepartamentoAcademico == null
        }
        val liberacionProducto = porCarrera.count {
            val pr = it.procesoActivoOrNull()
            pr != null && !esResidenciaProfesional(it) &&
                pr.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null &&
                pr.fechaSolicitudRegistroLiberacionDeptoAcademico == null &&
                pr.fechaEnviadoDepartamentoAcademico == null
        }
        val totalSinodales = porCarrera.count {
            it.procesoActivoOrNull()?.fechaSolicitudSinodales != null
        }
        return mapOf(
            "pendientes" to pendientes,
            "en_correccion" to enCorreccion,
            "aprobados" to aprobados,
            "todos" to todos,
            "sinodales_por_asignar" to sinodales,
            "anteproyecto" to anteproyecto,
            "total_anteproyecto" to totalAnteproyecto,
            "liberacion_producto" to liberacionProducto,
            "total_liberacion_producto" to totalLiberacionProducto,
            "total_sinodales" to totalSinodales,
        )
    }

    fun listarParaDepartamento(estado: String, academicoUsername: String, segmentoSlug: String? = null): List<DepartamentoListItemDto> {
        val allBase = filtrarEgresadosPorCarreraSiAcademico(egresadoRepository.findAll(), academicoUsername)
        val excluirResidencia = bandejaDepartamentoExcluyeResidencia(academicoUsername) && segmentoSlug.isNullOrBlank()
        val afterModalidad = if (excluirResidencia) allBase.filter { !esResidenciaProfesional(it) } else allBase
        val porCarrera = aplicarFiltroSegmentoCoordinacion(afterModalidad, segmentoSlug, academicoUsername)
        // Solo residencia para el flujo Liberar (pendientes/en_correccion/aprobados).
        // Sinodales y Todos muestran todos los egresados del departamento sin filtrar por modalidad.
        val norm = estado.trim().lowercase()
        val all = when (norm) {
            "sinodales", "todos", "anteproyecto", "liberacion_producto" -> porCarrera
            else -> filtrarBandejaSegmentoCoordinacion(porCarrera, segmentoSlug, academicoUsername)
        }
        val lista = when (norm) {
            "aprobados" -> all.filter { liberacionRevisionCompletada(it) }
            "sinodales" -> all
                .filter { it.procesoActivoOrNull()?.fechaSolicitudSinodales != null }
                .sortedWith(
                    compareBy<Egresado> { it.procesoActivoOrNull()?.fechaAsignacionSinodales != null }
                        .thenByDescending { it.procesoActivoOrNull()?.fechaSolicitudSinodales ?: Instant.EPOCH }
                        .thenByDescending { it.fecha_actualizacion },
                )
            "todos" -> all.filter { it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null }
            "en_correccion" -> all.filter {
                it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                    !liberacionRevisionCompletada(it) && enCorreccionAcademico(it)
            }
            "anteproyecto" -> all.filter {
                val pr = it.procesoActivoOrNull()
                pr != null && !esResidenciaProfesional(it) &&
                    pr.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null &&
                    pr.fechaEnviadoDepartamentoAcademico == null
            }
            "liberacion_producto" -> all.filter {
                val pr = it.procesoActivoOrNull()
                pr != null && !esResidenciaProfesional(it) &&
                    pr.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null &&
                    pr.fechaEnviadoDepartamentoAcademico == null
            }
            else -> if (!segmentoSlug.isNullOrBlank()) {
                // Vista departamento: muestra todos los de residencia (liberados y pendientes)
                all.filter { it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null }
            } else {
                // Vista global de coordinación: solo pendientes de liberación
                all.filter {
                    it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                        !liberacionRevisionCompletada(it) && !enCorreccionAcademico(it)
                }
            }
        }
        val formatter = DateTimeFormatter.ISO_INSTANT
        return lista.map { e ->
            val p = e.datos_personales
            val pr = e.procesoActivo()
            val nombre = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                .filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "—" }
            DepartamentoListItemDto(
                id = e.id?.toString() ?: "",
                nombre = nombre,
                numeroControl = e.numero_control,
                carrera = p.carrera.takeIf { it.isNotBlank() },
                modalidad = pr.datos_proyecto.modalidad,
                fechaActualizacion = formatter.format(e.fecha_actualizacion),
                fechaEnviadoDepartamento = pr.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
                estadoRevision = estadoRevisionDepartamento(e),
                fechaSolicitudSinodales = pr.fechaSolicitudSinodales?.let { formatter.format(it) },
                sinodalesAsignados = pr.fechaAsignacionSinodales != null,
                fechaEnvioAnteproyectoDepto = pr.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
                fechaRegistradoDepartamento = pr.fechaRegistradoDepartamento?.let { formatter.format(it) },
                fechaLiberacionProducto = pr.fechaSolicitudRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
            )
        }
    }

    fun academicoPuedeAccederAEgresado(academicoUsername: String, egresadoId: String): Boolean {
        val e = cargarEgresadoPorId(egresadoId) ?: return false
        val bloquearResidenciaComoVistaGlobal =
            bandejaDepartamentoExcluyeResidencia(academicoUsername) && !puedeUsarFiltroSegmentoCoordinacion(academicoUsername)
        if (bloquearResidenciaComoVistaGlobal && esResidenciaProfesional(e)) return false
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return true
        if (permitidas.isEmpty()) return true
        return carreraPermitidaParaAcademico(e.datos_personales.carrera, permitidas)
    }

    private fun filtrarEgresadosPorCarreraSiAcademico(egresados: List<Egresado>, academicoUsername: String): List<Egresado> {
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return egresados
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun filtrarBandejaSegmentoCoordinacion(egresados: List<Egresado>, segmentoSlug: String?, username: String): List<Egresado> {
        val slug = segmentoSlug?.trim()?.lowercase() ?: return egresados
        if (slug.isEmpty()) return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = catalogoRepository.findFirstByTipoAndSlug("departamento", slug) ?: return egresados
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { esResidenciaProfesional(it) }
    }

    private fun aplicarFiltroSegmentoCoordinacion(egresados: List<Egresado>, segmentoSlug: String?, username: String): List<Egresado> {
        val slug = segmentoSlug?.trim()?.lowercase() ?: return egresados
        if (slug.isEmpty()) return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = catalogoRepository.findFirstByTipoAndSlug("departamento", slug) ?: return egresados
        val nDup = catalogoRepository.countByTipoAndSlug("departamento", slug)
        if (nDup > 1) log.warn("Catálogo 'departamento' slug='{}' tiene {} duplicados.", slug, nDup)
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun puedeUsarFiltroSegmentoCoordinacion(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        return RolSoporte.tieneAlgunRol(u.rol, "coordinador", "apoyo_titulacion", "division_estudios_prof_admin", "administrador")
    }

    private fun carrerasFiltroAcademico(username: String): Set<String>? {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return null
        if (!u.rol.trim().equals("academico", ignoreCase = true)) return null
        val permitidas = u.carrerasAsignadas.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (permitidas.isEmpty()) null else permitidas
    }

    private fun esAcademicoSoloRevisiones(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (!u.rol.trim().equals("academico", ignoreCase = true)) return false
        return u.segmentoAcademico?.trim().isNullOrEmpty() && u.carrerasAsignadas.isEmpty()
    }

    private fun bandejaDepartamentoExcluyeResidencia(username: String): Boolean {
        if (esAcademicoSoloRevisiones(username)) return true
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (!u.segmentoAcademico.isNullOrBlank()) return false
        if (u.carrerasAsignadas.isNotEmpty()) return false
        return RolSoporte.tieneAlgunRol(u.rol, "coordinador", "apoyo_titulacion", "division_estudios_prof_admin", "administrador")
    }

    private fun normalizarCarreraKey(carrera: String): String {
        val sinAcentos = java.text.Normalizer.normalize(carrera.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return sinAcentos.replace(Regex("\\s+"), " ").lowercase()
    }

    /** Departamento académico (slug + nombre) al que pertenece la carrera del egresado, según catálogo. */
    private fun resolverDepartamentoPorCarrera(carrera: String): Pair<String, String>? {
        val key = normalizarCarreraKey(carrera)
        if (key.isEmpty()) return null
        for (cat in catalogoRepository.findByTipoAndActivoTrue("departamento")) {
            val slug = cat.slug?.trim()?.lowercase()?.ifBlank { null } ?: continue
            if (cat.carreras.any { normalizarCarreraKey(it) == key }) {
                return slug to cat.nombre
            }
        }
        return null
    }

    private fun carreraPermitidaParaAcademico(carrera: String, permitidas: Set<String>): Boolean {
        val key = normalizarCarreraKey(carrera)
        if (key.isEmpty()) return false
        return permitidas.any { normalizarCarreraKey(it) == key }
    }

    fun obtenerPorId(id: String): EgresadoDetailDto? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        return try {
            egresadoRepository.findByObjectIdConTimeout(objectId)
                ?.let { verificarYMarcarVencido(it) }
                ?.let { toDetailDto(it) }
        } catch (e: Exception) {
            log.warn("obtenerPorId: timeout/error consultando id={}: {}", id, e.message)
            null
        }
    }

    fun obtenerPorNumeroControl(numeroControl: String): EgresadoDetailDto? {
        if (numeroControl.isBlank()) return null
        return expedientePrioritarioParaNumeroControl(numeroControl.trim())
            ?.let { verificarYMarcarVencido(it) }
            ?.let { toDetailDto(it) }
    }

    private fun tieneUltimoPasoSeguimientoCompletado(p: ProcesoTitulacion): Boolean =
        p.fechaConfirmacionDocumentacionEscaneadaRecibida != null

    private fun esExpedienteCerrado(e: Egresado): Boolean {
        val p = e.procesoActivoOrNull() ?: return true
        val estado = p.estado_general.trim().lowercase()
        return estado == "titulado" || estado == "vencido" || tieneUltimoPasoSeguimientoCompletado(p)
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

    fun verificarDisponibilidadNumeroControlParaAlta(numero: String, excluirId: String?, modalidad: String? = null): VerificacionDuplicadoAlta {
        val n = numero.trim()
        if (n.isEmpty()) return VerificacionDuplicadoAlta("LIBRE", null)
        val expedientes = expedientesMismoControlRefrescados(n)
            .filter { excluirId == null || it.id?.toString() != excluirId }
        if (expedientes.isEmpty()) return VerificacionDuplicadoAlta("LIBRE", null)

        val abierto = expedientes.firstOrNull { !esExpedienteCerrado(it) }
        if (abierto != null) return VerificacionDuplicadoAlta("BLOQUEADO", "en_proceso")

        val modalidadNueva = normalizarModalidadAlta(modalidad)
        if (modalidadNueva.isBlank()) {
            val primero = expedientes.firstOrNull()
            val estadoCerrado = when (primero?.procesoActivoOrNull()?.estado_general?.trim()?.lowercase()) {
                "vencido" -> "vencido"
                else -> "titulado"
            }
            return VerificacionDuplicadoAlta("BLOQUEADO", estadoCerrado, primero?.id?.toString())
        }

        val repetida = expedientes.firstOrNull {
            normalizarModalidadAlta(it.procesoActivoOrNull()?.datos_proyecto?.modalidad) == modalidadNueva
        }
        if (repetida != null) {
            val estado = if (repetida.procesoActivoOrNull()?.estado_general?.trim().equals("vencido", ignoreCase = true)) "vencido" else "titulado"
            return VerificacionDuplicadoAlta("BLOQUEADO", estado, repetida.id?.toString())
        }
        return VerificacionDuplicadoAlta("LIBRE", null)
    }

    fun validarNuevaAltaNumeroControl(datos: EgresadoRequestDto): String? {
        val verificacion = verificarDisponibilidadNumeroControlParaAlta(numero = datos.numero_control, excluirId = null, modalidad = datos.modalidad)
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
        val adj = e.procesoActivoOrNull()?.documento_adjunto ?: return null
        return streamDesdeAdjunto(adj, "documento")
    }

    fun obtenerTesisLiberacionAdjunto(id: String): DocumentoStream? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        val e = egresadoRepository.findById(objectId).orElse(null) ?: return null
        val adj = e.procesoActivoOrNull()?.tesisLiberacionAdjunto ?: return null
        return streamDesdeAdjunto(adj, "tesis-liberacion.pdf")
    }

    private fun streamDesdeAdjunto(adj: DocumentoAdjunto, defaultName: String): DocumentoStream? {
        val gridId = adj.gridfs_id ?: return null
        val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(gridId))) ?: return null
        val resource = gridFsTemplate.getResource(file)
        return DocumentoStream(
            inputStream = resource.inputStream,
            contentType = adj.content_type.ifBlank { "application/octet-stream" },
            fileName = adj.nombre_original.ifBlank { defaultName },
        )
    }

    fun obtenerDocumentoEscaneadoProceso(id: String): DocumentoStream? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        egresadoRepository.findById(objectId).orElse(null) ?: return null
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

    fun reemplazarDocumentoAdjunto(id: String, archivo: MultipartFile): Boolean {
        if (archivo.isEmpty) return false
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return false
        val p = existente.procesoActivoOrNull() ?: return false
        val ahora = Instant.now()
        val gridFsIdAnterior = p.documento_adjunto.gridfs_id
        val gridFsIdNuevo = subirArchivo(archivo)
        val nuevoAdj = DocumentoAdjunto(
            gridfs_id = gridFsIdNuevo,
            nombre_original = archivo.originalFilename ?: "",
            content_type = archivo.contentType ?: "application/octet-stream",
            tamanio_bytes = archivo.size,
            fecha_subida = ahora,
        )
        egresadoRepository.save(
            existente.actualizarProcesoActivo(p.copy(documento_adjunto = nuevoAdj, fecha_actualizacion = ahora))
        )
        if (gridFsIdAnterior != null) {
            try { gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gridFsIdAnterior))) }
            catch (e: Exception) { log.warn("No se pudo eliminar el adjunto GridFS anterior {}: {}", gridFsIdAnterior, e.message) }
        }
        log.info("Documento adjunto reemplazado para egresado id={}", id)
        return true
    }

    /**
     * Egresado sustituye el PDF/Word del expediente ante Coordinación de Apoyo a la Titulación,
     * solo cuando la última revisión enviada pide observaciones y el documento aún no está liberado.
     * @return null si OK, mensaje de error en español si no aplica.
     */
    fun reemplazarDocumentoExpedienteEgresadoTrasRevision(egresadoId: String, archivo: MultipartFile): String? {
        if (archivo.isEmpty) return "Selecciona un archivo no vacío."
        val objectId = try { ObjectId(egresadoId) } catch (_: Exception) { return "Identificador no válido." }
        val existente = egresadoRepository.findById(objectId).orElse(null) ?: return "No se encontró tu registro."
        val eid = existente.id ?: return "Registro incompleto."
        val p = existente.procesoActivoOrNull() ?: return "No hay un proceso activo."
        if (p.documento_adjunto.gridfs_id == null) {
            return "No hay documento en el sistema que reemplazar. Contacta a tu división de estudios."
        }
        if (p.fechaEnviadoDepartamentoAcademico == null) {
            return "Tu expediente aún no está en revisión por Coordinación de Apoyo a la Titulación."
        }
        if (liberacionRevisionCompletada(existente)) {
            return "El documento ya fue liberado; no puedes enviar otro archivo por esta opción."
        }
        val ultima = revisionService.ultimaRevision(eid, p.id)
            ?: return "No hay revisiones registradas."
        if (!ultima.enviadoAlEgresado) {
            return "Aún no hay una revisión enviada a la que puedas responder con un documento corregido."
        }
        if (ultima.resultado != "observaciones") {
            return "Solo puedes subir corrección cuando la última revisión indica observaciones."
        }
        val ahora = Instant.now()
        val gridFsIdAnterior = p.documento_adjunto.gridfs_id
        return try {
            val gridFsIdNuevo = subirArchivo(archivo)
            val nuevoAdj = DocumentoAdjunto(
                gridfs_id = gridFsIdNuevo,
                nombre_original = archivo.originalFilename ?: "",
                content_type = archivo.contentType ?: "application/octet-stream",
                tamanio_bytes = archivo.size,
                fecha_subida = ahora,
            )
            egresadoRepository.save(
                existente.actualizarProcesoActivo(
                    p.copy(
                        documento_adjunto = nuevoAdj,
                        cert_uuid = null,
                        cert_hash = null,
                        fechaCertificacion = null,
                        fecha_actualizacion = ahora,
                    ),
                ),
            )
            if (gridFsIdAnterior != null) {
                try {
                    gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gridFsIdAnterior)))
                } catch (ex: Exception) {
                    log.warn("No se pudo eliminar el adjunto GridFS anterior {}: {}", gridFsIdAnterior, ex.message)
                }
            }
            log.info("Documento expediente reemplazado por egresado egresadoId={}", egresadoId)
            null
        } catch (ex: IllegalArgumentException) {
            ex.message ?: "Archivo no válido (solo PDF o Word .docx)."
        }
    }

    fun marcarEnviadoDepartamentoAcademico(id: String): Boolean {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return false }
        val e = egresadoRepository.findById(objectId).orElse(null) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnviadoDepartamentoAcademico != null) return false
        if (!esResidenciaProfesional(e) && p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) {
            if (p.fechaRecepcionRegistroLiberacionDeptoAcademico == null) return false
        }
        val ahora = Instant.now()
        val pEnviado = p.copy(fechaEnviadoDepartamentoAcademico = ahora, fecha_actualizacion = ahora)
        val enviado = egresadoRepository.save(e.actualizarProcesoActivo(pEnviado))

        if (esResidenciaProfesional(e)) {
            try {
                val resultado = certService.certificarDocumento(enviado)
                if (resultado != null) {
                    val pCert = pEnviado.copy(
                        documento_adjunto = pEnviado.documento_adjunto.copy(gridfs_id = resultado.nuevoGridFsId),
                        cert_uuid = resultado.certUuid,
                        cert_hash = resultado.certHash,
                        fechaCertificacion = ahora,
                        fecha_actualizacion = Instant.now(),
                    )
                    egresadoRepository.save(enviado.actualizarProcesoActivo(pCert))
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
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnviadoDepartamentoAcademico == null) return false
        if (p.fechaRecibidoRegistroLiberacion != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaRecibidoRegistroLiberacion = ahora, fecha_actualizacion = ahora)))
        return true
    }

    /** Marca el anteproyecto como registrado en la bandeja del departamento. Acción independiente del flujo de seguimiento. */
    fun marcarRegistradoDepartamento(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return false
        if (p.fechaRegistradoDepartamento != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaRegistradoDepartamento = ahora, fecha_actualizacion = ahora)))
        return true
    }

    /** Paso 1 (no residencia): DEP confirma entrega del egresado (anexo XXXI y anteproyecto). */
    fun confirmarEntregaEgresadoDeptoNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaConfirmacionEntregaEgresadoDepto != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(p.copy(fechaConfirmacionEntregaEgresadoDepto = ahora, fecha_actualizacion = ahora)),
        )
        return true
    }

    fun solicitarRegistroAnteproyectoNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) return false
        val ahora = Instant.now()
        val conEntrega = if (p.fechaConfirmacionEntregaEgresadoDepto == null) {
            p.copy(fechaConfirmacionEntregaEgresadoDepto = ahora)
        } else {
            p
        }
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                conEntrega.copy(
                    fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = ahora,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return true
    }

    /** No residencia (flujo 16): la DEP confirma recepción de anexo XXXI, anteproyecto y anexo XXXII (tras el envío al departamento). */
    fun confirmarRecepcionInicialAnexosXxxiXxxiiNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return false
        if (p.fechaRegistradoDepartamento == null) return false
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null) return false
        if (p.fechaRecepcionTrabajoDivisionEstudiosProf != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = ahora, fecha_actualizacion = ahora),
            ),
        )
        return true
    }

    fun confirmarRecepcionTrabajoNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return false
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii == null) return false
        if (p.fechaRecepcionTrabajoDivisionEstudiosProf != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaRecepcionTrabajoDivisionEstudiosProf = ahora,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return true
    }

    /**
     * Paso 5 (no residencia): departamento académico sube la tesis y libera producto (anexo XXXIII).
     * Registra [fechaSolicitudRegistroLiberacionDeptoAcademico] y el PDF en [tesisLiberacionAdjunto].
     */
    fun liberarProductoNoResidencia(id: String, archivo: MultipartFile): String? {
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        if (esResidenciaProfesional(e)) return "Solo aplica a modalidades distintas de residencia."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii == null) {
            return "La DEP debe confirmar la recepción del registro de la tesis (paso 3) antes de liberar."
        }
        if (p.fechaSolicitudRegistroLiberacionDeptoAcademico != null) return "La tesis ya fue liberada."
        if (archivo.isEmpty) return "Selecciona un archivo PDF de la tesis."
        if (!esPdfValido(archivo)) return "Solo se permiten archivos PDF."
        val ahora = Instant.now()
        val gridFsId = subirArchivo(archivo)
        val adjunto = DocumentoAdjunto(
            gridfs_id = gridFsId,
            nombre_original = archivo.originalFilename ?: "tesis.pdf",
            content_type = archivo.contentType ?: "application/pdf",
            tamanio_bytes = archivo.size,
            fecha_subida = ahora,
        )
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaSolicitudRegistroLiberacionDeptoAcademico = ahora,
                    fechaRecepcionTrabajoDivisionEstudiosProf = ahora,
                    tesisLiberacionAdjunto = adjunto,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return null
    }

    /** @deprecated Usar [liberarProductoNoResidencia]; conservado por compatibilidad de API. */
    fun solicitarRegistroLiberacionNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        return p.fechaSolicitudRegistroLiberacionDeptoAcademico != null
    }

    fun confirmarRecepcionRegistroLiberacionNoResidencia(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        if (esResidenciaProfesional(e)) return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudRegistroLiberacionDeptoAcademico == null) return false
        if (p.fechaRecepcionRegistroLiberacionDeptoAcademico != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaRecepcionRegistroLiberacionDeptoAcademico = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun confirmarRecibidosAnexoXxxiXxxii(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        val puede = if (!esResidenciaProfesional(e) && p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null) {
            p.fechaLiberacionDocumentoCoordinacionCat != null
        } else {
            p.fechaRecibidoRegistroLiberacion != null
        }
        if (!puede) return false
        if (p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaConfirmacionRecibidosAnexoXxxiXxxii = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun crearAnexo91(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        val prerequisito91 = if (esCeneval(e)) {
            p.fechaConfirmacionEntregaEgresadoDepto != null
        } else {
            p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null
        }
        if (!prerequisito91) return null
        val destinatarioServicios = env.getProperty("sit.anexo91.destinatario-servicios-escolares")?.trim().orEmpty()
        val destinatarioServiciosFinal = if (destinatarioServicios.isNotEmpty()) destinatarioServicios else "ROMEO ALBERTO ANGELES PEREZ"
        val ahora = Instant.now()
        val certId = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores = construirValoresPlantillaHtml(e, listOf(
            "MODALIDAD" to p.datos_proyecto.modalidad,
            "FECHA_CARTA" to fechaCartaEspanola(ahora),
            "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(p.datos_proyecto.modalidad),
            "DESTINATARIO_SERVICIOS_ESCOLARES" to destinatarioServiciosFinal,
            "QR_CODE" to qrDataUri,
        ))
        val pdf = htmlAnexoPdfService.generarDesdeClasspath("templates/html/anexo-9-1.html", valores)
        if (pdf == null) {
            log.warn("crearAnexo91: no se generó PDF para egresado id={}", id)
            return null
        }
        if (p.fechaCreacionAnexo91 == null) {
            egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaCreacionAnexo91 = ahora, fecha_actualizacion = ahora)))
        }
        return pdf
    }

    fun confirmarEntregaAnexo91(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaCreacionAnexo91 == null) return false
        if (p.fechaConfirmacionEntregaAnexo91 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaConfirmacionEntregaAnexo91 = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun solicitarConstancia92Division(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaConfirmacionEntregaAnexo91 == null) return false
        if (p.fechaSolicitudAnexo92 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaSolicitudAnexo92 = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun crearAnexo92(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        if (p.fechaConfirmacionEntregaAnexo91 == null) return null
        if (p.fechaCreacionAnexo92 == null && p.fechaSolicitudAnexo92 == null) return null
        val ahora = Instant.now()
        if (p.fechaCreacionAnexo92 == null) {
            egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaCreacionAnexo92 = ahora, fecha_actualizacion = ahora)))
        }
        return generarPdfAnexo("Anexo 9.2", "sit.anexo92.plantilla-docx", "templates/anexo-9-2.docx", e, emptyList())
    }

    fun confirmarRecibidoAnexo92(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudAnexo92 == null) return false
        if (p.fechaAceptacionServiciosEscolaresAnexo92 == null) return false
        if (p.fechaConfirmacionRecibidoAnexo92 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaConfirmacionRecibidoAnexo92 = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun solicitarSinodales(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaConfirmacionRecibidoAnexo92 == null) return false
        if (p.fechaSolicitudSinodales != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaSolicitudSinodales = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun obtenerSinodales(id: String): SinodalesTribunal? {
        val e = cargarEgresadoPorId(id) ?: return null
        return e.procesoActivoOrNull()?.sinodalesTribunal
    }

    fun asignarSinodales(id: String, presidente: String, secretario: String, vocal: String, vocalSuplente: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudSinodales == null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            sinodalesTribunal = SinodalesTribunal(
                presidente = presidente.trim(),
                secretario = secretario.trim(),
                vocal = vocal.trim(),
                vocal_suplente = vocalSuplente.trim(),
            ),
            fechaAsignacionSinodales = ahora,
            fecha_actualizacion = ahora,
        )))
        return true
    }

    fun confirmarSinodalesRecibidos(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudSinodales == null || p.fechaAsignacionSinodales == null) return false
        if (p.fechaConfirmacionSinodalesRecibidos != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaConfirmacionSinodalesRecibidos = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun crearAnexo93(id: String): Pair<ByteArray, Int>? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        if (p.fechaAgendaActo93 == null) return null
        val esNuevaGeneracion = p.fechaCreacionAnexo93 == null
        val ahora = Instant.now()
        if (esNuevaGeneracion) {
            egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaCreacionAnexo93 = ahora, fecha_actualizacion = ahora)))
        }
        val zona = zonaActo93
        val acto = p.fechaAgendaActo93!!
        val actoLegible = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zona).format(acto)
        val zActo = acto.atZone(zona)
        val diaActo = zActo.dayOfMonth.toString().padStart(2, '0')
        val mesActo = nombreMesEspanol(zActo.monthValue).uppercase(Locale.forLanguageTag("es-MX"))
        val anioActo = zActo.year.toString()
        val horaActo = String.format(Locale.ROOT, "%02d:%02d", zActo.hour, zActo.minute)
        val jefeDivisionNombre = env.getProperty("sit.anexo93.jefe-division-nombre", "MANUEL FABIAN ROJAS").trim()
        val certId = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores = construirValoresPlantillaHtml(e, listOf(
            "ACTO_93" to actoLegible,
            "FECHA_CARTA" to fechaCartaEspanolaAnexo93(Instant.now()),
            "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(p.datos_proyecto.modalidad),
            "ACTO_DIA" to diaActo, "ACTO_MES" to mesActo, "ACTO_ANIO" to anioActo, "ACTO_HORA" to horaActo,
            "PRESIDENTE" to (p.sinodalesTribunal?.presidente ?: ""),
            "SECRETARIO" to (p.sinodalesTribunal?.secretario ?: ""),
            "VOCAL" to (p.sinodalesTribunal?.vocal ?: ""),
            "VOCAL_SUPLENTE" to (p.sinodalesTribunal?.vocal_suplente ?: ""),
            "JEFE_DIVISION_NOMBRE" to jefeDivisionNombre,
            "QR_CODE" to qrDataUri,
        ))
        val bytes = htmlAnexoPdfService.generarDesdeClasspath("templates/html/anexo-9-3.html", valores)
            ?: return null

        var emailsEnviados = 0
        if (esNuevaGeneracion) {
            val nombres = listOfNotNull(
                p.sinodalesTribunal?.presidente,
                p.sinodalesTribunal?.secretario,
                p.sinodalesTribunal?.vocal,
                p.sinodalesTribunal?.vocal_suplente,
            ).map { it.trim() }.filter { it.isNotBlank() }

            if (nombres.isNotEmpty()) {
                val emailPorNombre = docenteRepository.findByActivoTrue()
                    .filter { it.correo.isNotBlank() }
                    .associateBy { it.nombreCompleto.trim() }
                val destinatarios = nombres.mapNotNull { emailPorNombre[it]?.correo }
                if (destinatarios.isNotEmpty()) {
                    val nombreEgresado = listOf(
                        e.datos_personales.nombre,
                        e.datos_personales.apellido_paterno,
                        e.datos_personales.apellido_materno,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    emailsEnviados = emailService.enviarAnexo93Sinodales(
                        destinatarios = destinatarios,
                        nombreEgresado = nombreEgresado,
                        numeroControl = e.numero_control,
                        fechaActo = actoLegible,
                        pdfBytes = bytes,
                        fileName = "Anexo-9.3-${e.numero_control}.pdf",
                    )
                } else {
                    log.warn("Anexo 9.3: ningún sinodal tiene correo registrado en la colección de docentes (egresado {})", e.numero_control)
                }
            }
        }

        return Pair(bytes, emailsEnviados)
    }

    fun confirmarEntregaAnexo93(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaCreacionAnexo93 == null || p.fechaConfirmacionEntregaAnexo93 != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaConfirmacionEntregaAnexo93 = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun solicitarDocumentacionEscaneada(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (!cumplePrerequisitoDocumentacionEscaneada(e, p)) return false
        if (p.fechaSolicitudDocumentacionEscaneada != null) return false
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(fechaSolicitudDocumentacionEscaneada = ahora, fecha_actualizacion = ahora)))
        return true
    }

    fun confirmarDocumentacionEscaneadaRecibida(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioDocumentacionEscaneadaEgresado == null) return false
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val ahora = Instant.now()
        val guardado = egresadoRepository.save(
            e.actualizarProcesoActivo(p.copy(fechaConfirmacionDocumentacionEscaneadaRecibida = ahora, fecha_actualizacion = ahora)),
        )
        notificarPlazoResidenciaSiAplica(guardado)
        return true
    }

    fun solicitarDocumentacionEscaneadaNuevamente(id: String, observaciones: String?): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudDocumentacionEscaneada == null) return false
        if (p.fechaEnvioDocumentacionEscaneadaEgresado == null) return false
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val obs = observaciones?.trim()?.takeIf { it.isNotEmpty() }
        val ahora = Instant.now()
        eliminarEntregaEscaneadaAnterior(e.id ?: return false)
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            fechaEnvioDocumentacionEscaneadaEgresado = null,
            fechaConfirmacionDocumentacionEscaneadaRecibida = null,
            fechaSolicitudReenvioDocumentacionEscaneada = ahora,
            observacionesReenvioDocumentacionEscaneada = obs,
            fecha_actualizacion = ahora,
        )))
        return true
    }

    fun subirDocumentacionEscaneadaEgresado(egresadoId: String, archivos: List<MultipartFile>?): String? {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return "Identificador inválido." }
        val e = egresadoRepository.findById(oid).orElse(null) ?: return "Registro no encontrado."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaSolicitudDocumentacionEscaneada == null) return "Aún no se ha solicitado la documentación escaneada."
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return "Ya se confirmó la recepción; no se pueden enviar más archivos."
        val lista = archivos?.filter { !it.isEmpty } ?: emptyList()
        if (lista.isEmpty()) return "Debes adjuntar al menos un PDF."
        for (a in lista) {
            if (!esPdfValido(a)) return "Solo se permiten archivos PDF (${a.originalFilename ?: "archivo"})."
        }
        eliminarEntregaEscaneadaAnterior(oid)
        val metas = mutableListOf<ArchivoEscaneadoMeta>()
        for (a in lista) {
            metas.add(ArchivoEscaneadoMeta(
                gridfsId = subirArchivo(a),
                nombreOriginal = a.originalFilename ?: "documento.pdf",
                contentType = a.contentType ?: "application/pdf",
                tamanioBytes = a.size,
            ))
        }
        val guardado = documentacionEscaneadaRepository.save(
            DocumentacionEscaneada(
                egresadoId = oid,
                procesoId = p.id,
                numeroControl = e.numero_control,
                nombreCompleto = nombreCompleto(e),
                carrera = e.datos_personales.carrera.takeIf { it.isNotBlank() },
                archivos = metas,
            )
        )
        val ahora = Instant.now()
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            fechaEnvioDocumentacionEscaneadaEgresado = ahora,
            fechaSolicitudReenvioDocumentacionEscaneada = null,
            observacionesReenvioDocumentacionEscaneada = null,
            fecha_actualizacion = ahora,
        )))
        log.info("Documentación escaneada: egresadoId={}, entregaId={}, archivos={}", egresadoId, guardado.id, metas.size)
        return null
    }

    private fun cumplePrerequisitoDocumentacionEscaneada(e: Egresado, p: ProcesoTitulacion): Boolean {
        if (p.fechaCreacionAnexo93 == null) return false
        if (esResidenciaProfesional(e) || esCeneval(e)) return p.fechaConfirmacionEntregaAnexo93 != null
        return true
    }

    private fun eliminarEntregaEscaneadaAnterior(egresadoId: ObjectId) {
        val ant = documentacionEscaneadaRepository.findByEgresadoId(egresadoId) ?: return
        for (f in ant.archivos) {
            try { gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(f.gridfsId))) }
            catch (ex: Exception) { log.warn("GridFS {} no eliminado: {}", f.gridfsId, ex.message) }
        }
        try { ant.id?.let { documentacionEscaneadaRepository.deleteById(it) } }
        catch (ex: Exception) { log.warn("No se pudo eliminar documentación escaneada anterior: {}", ex.message) }
    }

    private fun esPdfValido(archivo: MultipartFile): Boolean {
        val ct = (archivo.contentType ?: "").lowercase()
        val name = (archivo.originalFilename ?: "").lowercase()
        return ct.contains("pdf") || name.endsWith(".pdf")
    }

    fun agendarActo93(id: String, fechaHoraRaw: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaConfirmacionSinodalesRecibidos == null) return false

        val inicio = parseFechaHoraActo93(fechaHoraRaw) ?: return false
        val zona = zonaActo93
        val zInicio = inicio.atZone(zona)
        val diaSemana = zInicio.dayOfWeek.value
        if (diaSemana !in 1..5) return false
        val horaInicio = zInicio.toLocalTime()
        val horaFin = horaInicio.plusHours(1)
        val ventanaInicio = LocalTime.of(9, 0)
        val ventanaFin = LocalTime.of(14, 0)
        if (horaInicio < ventanaInicio || horaFin > ventanaFin) return false

        val fin = inicio.plus(1, ChronoUnit.HOURS)
        val candidates = egresadoRepository.findByFechaAgendaActo93Solapando(inicio.minus(1, ChronoUnit.HOURS), fin)
        if (candidates.any { it.id != e.id }) return false

        val ahora = Instant.now()
        val reagenda = p.fechaAgendaActo93 != null
        if (reagenda) e.id?.let { eliminarEntregaEscaneadaAnterior(it) }

        var gridFinal = p.gridfsIdDocFinal
        var fechaSubidaFinal = p.fechaSubidaDocFinal
        var fechaTitulacionVal = p.fechaTitulacion
        var estado = p.estado_general
        var historial = p.historial_estados
        if (reagenda && p.estado_general == "titulado") {
            gridFinal?.let { gid ->
                try { gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gid))) }
                catch (ex: Exception) { log.warn("GridFS doc final {} no eliminado al reagendar 9.3: {}", gid, ex.message) }
            }
            gridFinal = null; fechaSubidaFinal = null; fechaTitulacionVal = null; estado = "registrado"
            historial = historial + HistorialEstado(estado = "registrado", fecha = ahora, observacion = "Reagenda acto protocolario 9.3: se reinician pasos posteriores al agendamiento.")
        }

        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            fechaAgendaActo93 = inicio,
            fechaReagendaActo93 = if (reagenda) ahora else null,
            fechaCreacionAnexo93 = if (reagenda) null else p.fechaCreacionAnexo93,
            fechaConfirmacionEntregaAnexo93 = if (reagenda) null else p.fechaConfirmacionEntregaAnexo93,
            fechaSolicitudDocumentacionEscaneada = if (reagenda) null else p.fechaSolicitudDocumentacionEscaneada,
            fechaEnvioDocumentacionEscaneadaEgresado = if (reagenda) null else p.fechaEnvioDocumentacionEscaneadaEgresado,
            fechaConfirmacionDocumentacionEscaneadaRecibida = if (reagenda) null else p.fechaConfirmacionDocumentacionEscaneadaRecibida,
            fechaSolicitudReenvioDocumentacionEscaneada = if (reagenda) null else p.fechaSolicitudReenvioDocumentacionEscaneada,
            observacionesReenvioDocumentacionEscaneada = if (reagenda) null else p.observacionesReenvioDocumentacionEscaneada,
            gridfsIdDocFinal = if (reagenda) gridFinal else p.gridfsIdDocFinal,
            fechaSubidaDocFinal = if (reagenda) fechaSubidaFinal else p.fechaSubidaDocFinal,
            fechaTitulacion = if (reagenda) fechaTitulacionVal else p.fechaTitulacion,
            estado_general = if (reagenda) estado else p.estado_general,
            historial_estados = if (reagenda) historial else p.historial_estados,
            fecha_actualizacion = ahora,
        )))

        val correo = e.datos_personales.correo_electronico?.trim().orEmpty()
        if (correo.isNotBlank()) {
            val fechaHoraTexto = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
                .withZone(zonaActo93)
                .format(inicio)
            val enviado = emailService.enviarAvisoActoProtocolarioAgendado(
                correoDestino = correo,
                nombreEgresado = nombreCompleto(e),
                numeroControl = e.numero_control,
                fechaHoraActoTexto = fechaHoraTexto,
            )
            if (!enviado) {
                log.warn(
                    "Acto 9.3 agendado para {} pero no se envió correo al egresado ({})",
                    e.numero_control,
                    correo,
                )
            }
        } else {
            log.warn("Acto 9.3 agendado para {} sin correo del egresado; aviso por correo omitido", e.numero_control)
        }

        return true
    }

    fun listarActo93Ocupados(): List<Instant> {
        val zona = zonaActo93
        val inicio = LocalDate.now(zona).minusMonths(1).atStartOfDay(zona).toInstant()
        val fin = LocalDate.now(zona).plusYears(2).atStartOfDay(zona).toInstant()
        return try {
            egresadoRepository.findActo93AgendadoEnRango(inicio, fin)
                .mapNotNull { it.procesoActivoOrNull()?.fechaAgendaActo93 }
                .distinct().sorted().take(1500)
        } catch (e: Exception) {
            log.warn("listarActo93Ocupados: timeout/error: {}", e.message)
            emptyList()
        }
    }

    private fun toDetailDto(e: Egresado): EgresadoDetailDto {
        val p = e.datos_personales
        val pr = e.procesoActivoOrNull()
        val doc = pr?.documentos ?: Documentos()
        val formatter = DateTimeFormatter.ISO_INSTANT
        val deptoCarrera = resolverDepartamentoPorCarrera(p.carrera)
        return EgresadoDetailDto(
            id = e.id?.toString() ?: "",
            numero_control = e.numero_control,
            datos_personales = DatosPersonalesDto(
                nombre = p.nombre, apellido_paterno = p.apellido_paterno,
                apellido_materno = p.apellido_materno ?: "", carrera = p.carrera,
                nivel = p.nivel, direccion = p.direccion, telefono = p.telefono,
                correo_electronico = p.correo_electronico,
            ),
            datos_proyecto = pr?.let {
                DatosProyectoDto(
                    nombre_proyecto = it.datos_proyecto.nombre_proyecto, modalidad = it.datos_proyecto.modalidad,
                    curso_titulacion = it.datos_proyecto.curso_titulacion, asesor_interno = it.datos_proyecto.asesor_interno,
                    asesor_externo = it.datos_proyecto.asesor_externo, director = it.datos_proyecto.director,
                    asesor_1 = it.datos_proyecto.asesor_1, asesor_2 = it.datos_proyecto.asesor_2,
                )
            } ?: DatosProyectoDto("", "", "no", null, null, null, null, null),
            documentos = DocumentosDto(
                anexo_xxxi = doc.anexo_xxxi.let { AnexoDto(it.fecha_registro?.let { formatter.format(it) }, it.estado) },
                constancia_no_inconveniencia = doc.constancia_no_inconveniencia.let { ConstanciaDto(it.fecha_expedicion?.let { formatter.format(it) }, it.estado) },
            ),
            documento_adjunto = pr?.documento_adjunto?.let { adj ->
                if (adj.nombre_original.isNotBlank() || adj.tamanio_bytes > 0) {
                    DocumentoAdjuntoDto(nombre_original = adj.nombre_original, tamanio_bytes = adj.tamanio_bytes)
                } else null
            },
            estado_general = pr?.estado_general ?: "registrado",
            fecha_creacion = formatter.format(e.fechaCreacion),
            fecha_actualizacion = formatter.format(e.fecha_actualizacion),
            proceso_id = pr?.id?.toHexString(),
            total_procesos = e.procesos.size,
            procesos_anteriores = if (e.procesos.size > 1) {
                e.procesos.dropLast(1).map { p ->
                    val fechaCierre = (p.fechaTitulacion
                        ?: p.historial_estados.lastOrNull { it.estado == "vencido" || it.estado == "titulado" }?.fecha)
                        ?.let { formatter.format(it) }
                    com.sit_titulacion.sit.web.api.dto.ProcesoAnteriorDto(
                        procesoId = p.id.toHexString(),
                        modalidad = p.datos_proyecto.modalidad,
                        nombreProyecto = p.datos_proyecto.nombre_proyecto,
                        estado = p.estado_general,
                        fechaCreacion = formatter.format(p.fechaCreacion),
                        fechaCierre = fechaCierre,
                        fechaEnviadoDepartamentoAcademico = p.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
                        fechaRecibidoRegistroLiberacion = p.fechaRecibidoRegistroLiberacion?.let { formatter.format(it) },
                        fechaConfirmacionRecibidosAnexoXxxiXxxii = p.fechaConfirmacionRecibidosAnexoXxxiXxxii?.let { formatter.format(it) },
                        fechaLiberacionDocumentoCoordinacionCat = p.fechaLiberacionDocumentoCoordinacionCat?.let { formatter.format(it) },
                        fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
                        fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii?.let { formatter.format(it) },
                        fechaCreacionAnexo91 = p.fechaCreacionAnexo91?.let { formatter.format(it) },
                        fechaConfirmacionEntregaAnexo91 = p.fechaConfirmacionEntregaAnexo91?.let { formatter.format(it) },
                        fechaSolicitudAnexo92 = p.fechaSolicitudAnexo92?.let { formatter.format(it) },
                        fechaAceptacionServiciosEscolaresAnexo92 =
                            p.fechaAceptacionServiciosEscolaresAnexo92?.let { formatter.format(it) },
                        fechaConfirmacionRecibidoAnexo92 = p.fechaConfirmacionRecibidoAnexo92?.let { formatter.format(it) },
                        fechaSolicitudSinodales = p.fechaSolicitudSinodales?.let { formatter.format(it) },
                        fechaConfirmacionSinodalesRecibidos = p.fechaConfirmacionSinodalesRecibidos?.let { formatter.format(it) },
                        fechaAgendaActo93 = p.fechaAgendaActo93?.let { formatter.format(it) },
                        fechaCreacionAnexo93 = p.fechaCreacionAnexo93?.let { formatter.format(it) },
                        fechaConfirmacionEntregaAnexo93 = p.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
                        fechaSolicitudDocumentacionEscaneada = p.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
                        fechaEnvioDocumentacionEscaneadaEgresado = p.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
                        fechaConfirmacionDocumentacionEscaneadaRecibida = p.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
                        fechaTitulacion = p.fechaTitulacion?.let { formatter.format(it) },
                    )
                }
            } else emptyList(),
            fecha_confirmacion_entrega_egresado_depto = pr?.fechaConfirmacionEntregaEgresadoDepto?.let { formatter.format(it) },
            fecha_envio_solicitud_registro_anteproyecto_depto_academico = pr?.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
            fecha_registrado_departamento = pr?.fechaRegistradoDepartamento?.let { formatter.format(it) },
            segmento_departamento_academico = deptoCarrera?.first,
            nombre_departamento_academico = deptoCarrera?.second,
            tiene_tesis_liberacion = pr?.tesisLiberacionAdjunto?.gridfs_id != null,
            fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii = pr?.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii?.let { formatter.format(it) },
            fecha_recepcion_trabajo_division_estudios_prof = pr?.fechaRecepcionTrabajoDivisionEstudiosProf?.let { formatter.format(it) },
            fecha_solicitud_registro_liberacion_depto_academico = pr?.fechaSolicitudRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
            fecha_recepcion_registro_liberacion_depto_academico = pr?.fechaRecepcionRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
            fecha_liberacion_documento_coordinacion_cat = pr?.fechaLiberacionDocumentoCoordinacionCat?.let { formatter.format(it) },
            fecha_enviado_departamento_academico = pr?.fechaEnviadoDepartamentoAcademico?.let { formatter.format(it) },
            fecha_recibido_registro_liberacion = pr?.fechaRecibidoRegistroLiberacion?.let { formatter.format(it) },
            fecha_confirmacion_recibidos_anexo_xxxi_xxxii = pr?.fechaConfirmacionRecibidosAnexoXxxiXxxii?.let { formatter.format(it) },
            fecha_creacion_anexo_9_1 = pr?.fechaCreacionAnexo91?.let { formatter.format(it) },
            fecha_confirmacion_entrega_anexo_9_1 = pr?.fechaConfirmacionEntregaAnexo91?.let { formatter.format(it) },
            fecha_solicitud_anexo_9_2 = pr?.fechaSolicitudAnexo92?.let { formatter.format(it) },
            fecha_creacion_anexo_9_2 = pr?.fechaCreacionAnexo92?.let { formatter.format(it) },
            fecha_aceptacion_servicios_escolares_anexo_9_2 =
                pr?.fechaAceptacionServiciosEscolaresAnexo92?.let { formatter.format(it) },
            fecha_confirmacion_recibido_anexo_9_2 = pr?.fechaConfirmacionRecibidoAnexo92?.let { formatter.format(it) },
            fecha_solicitud_sinodales = pr?.fechaSolicitudSinodales?.let { formatter.format(it) },
            fecha_asignacion_sinodales = pr?.fechaAsignacionSinodales?.let { formatter.format(it) },
            fecha_confirmacion_sinodales_recibidos = pr?.fechaConfirmacionSinodalesRecibidos?.let { formatter.format(it) },
            fecha_agenda_acto_9_3 = pr?.fechaAgendaActo93?.let { formatter.format(it) },
            fecha_reagenda_acto_9_3 = pr?.fechaReagendaActo93?.let { formatter.format(it) },
            fecha_creacion_anexo_9_3 = pr?.fechaCreacionAnexo93?.let { formatter.format(it) },
            fecha_confirmacion_entrega_anexo_9_3 = pr?.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
            fecha_titulacion = pr?.fechaTitulacion?.let { formatter.format(it) },
            tiene_doc_final = pr?.gridfsIdDocFinal != null,
            fecha_solicitud_documentacion_escaneada = pr?.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
            fecha_envio_documentacion_escaneada_egresado = pr?.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
            fecha_confirmacion_documentacion_escaneada_recibida = pr?.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
            fecha_solicitud_reenvio_documentacion_escaneada = pr?.fechaSolicitudReenvioDocumentacionEscaneada?.let { formatter.format(it) },
            observaciones_reenvio_documentacion_escaneada = pr?.observacionesReenvioDocumentacionEscaneada,
        )
    }

    fun subirDocumentoFinal(numeroControl: String, archivo: MultipartFile): Boolean {
        val e = egresadoRepository.findByNumeroControl(numeroControl.trim()) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaCreacionAnexo93 == null) return false
        if (p.estado_general == "titulado") return false
        val ahora = Instant.now()
        val gridFsId = subirArchivo(archivo)
        val guardado = egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            gridfsIdDocFinal = gridFsId,
            fechaSubidaDocFinal = ahora,
            fechaTitulacion = ahora,
            estado_general = "titulado",
            fecha_actualizacion = ahora,
            historial_estados = p.historial_estados + HistorialEstado(
                estado = "titulado", fecha = ahora, observacion = "Documentos finales entregados por el egresado",
            ),
        )))
        log.info("Egresado numero_control={} marcado como titulado", numeroControl)
        notificarPlazoResidenciaSiAplica(guardado)
        return true
    }

    private fun esResidenciaProfesional(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
    }

    private fun esCeneval(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        if (nombre.contains("ceneval", ignoreCase = true)) return true
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.nombre?.contains("ceneval", ignoreCase = true) == true
    }

    private fun liberacionRevisionCompletada(e: Egresado): Boolean {
        val p = e.procesoActivoOrNull() ?: return false
        return p.fechaRecibidoRegistroLiberacion != null || p.fechaLiberacionDocumentoCoordinacionCat != null
    }

    private fun ultimaRevisionResultado(e: Egresado): String? {
        val oid = e.id ?: return null
        val pid = e.procesoActivoOrNull()?.id ?: return null
        return revisionService.ultimaRevision(oid, pid)?.resultado
    }

    private fun enCorreccionAcademico(e: Egresado): Boolean =
        !esResidenciaProfesional(e) && ultimaRevisionResultado(e) == "observaciones"

    private fun estadoRevisionDepartamento(e: Egresado): String {
        if (liberacionRevisionCompletada(e)) return "aprobado"
        if (enCorreccionAcademico(e)) return "con_observaciones"
        return "pendiente"
    }

    /** Límite del plazo activo (residencia 6 m desde Anexo XXXI; tesis 12/18 m paso 3 o 6 m tras liberación). */
    private fun limitePlazoProcesoActivoLocal(e: Egresado): LocalDate? {
        val p = e.procesoActivoOrNull() ?: return null
        if (p.estado_general.equals("titulado", ignoreCase = true) ||
            p.estado_general.equals("vencido", ignoreCase = true)
        ) {
            return null
        }
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return null
        val zone = ZoneId.systemDefault()
        if (esCeneval(e)) return null
        if (esResidenciaProfesional(e)) {
            val inicio = p.documentos.anexo_xxxi.fecha_registro ?: p.fechaCreacion
            return inicio.atZone(zone).toLocalDate().plusMonths(6)
        }
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null ||
            p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
        ) {
            if (p.fechaEnviadoDepartamentoAcademico != null) return null
            val inicio = p.fechaSolicitudRegistroLiberacionDeptoAcademico
                ?: p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii
                ?: return null
            val meses = if (p.fechaSolicitudRegistroLiberacionDeptoAcademico != null) {
                6L
            } else {
                mesesDesarrolloProyectoNoRes(p.datos_proyecto.modalidad)
            }
            return inicio.atZone(zone).toLocalDate().plusMonths(meses)
        }
        val modalidad = p.datos_proyecto.modalidad
        val meses = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(modalidad.trim(), ignoreCase = true) }
            ?.mesesVigencia?.toLong() ?: mesesPorModalidadFallback(modalidad)
            ?: return null
        val inicio = p.fechaEnviadoDepartamentoAcademico ?: p.fechaCreacion
        return inicio.atZone(zone).toLocalDate().plusMonths(meses)
    }

    private fun mesesDesarrolloProyectoNoRes(modalidad: String): Long {
        val m = modalidad.trim().lowercase()
        return when {
            m.contains("monograf") -> 18L
            else -> 12L
        }
    }

    private fun verificarYMarcarVencido(e: Egresado): Egresado {
        val p = e.procesoActivoOrNull() ?: return e
        if (p.estado_general == "titulado" || p.estado_general == "vencido") return e
        val limiteLocal = limitePlazoProcesoActivoLocal(e) ?: return e
        if (LocalDate.now(ZoneId.systemDefault()) > limiteLocal) {
            val meses = when {
                esResidenciaProfesional(e) -> 6L
                p.fechaSolicitudRegistroLiberacionDeptoAcademico != null -> 6L
                p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null ->
                    mesesDesarrolloProyectoNoRes(p.datos_proyecto.modalidad)
                else ->
                    catalogoRepository.findByTipoAndActivoTrue("modalidad")
                        .find { it.nombre.trim().equals(p.datos_proyecto.modalidad.trim(), ignoreCase = true) }
                        ?.mesesVigencia?.toLong()
                        ?: mesesPorModalidadFallback(p.datos_proyecto.modalidad)
                        ?: 12L
            }
            val ahora = Instant.now()
            val pVencido = p.copy(
                estado_general = "vencido",
                fecha_actualizacion = ahora,
                historial_estados = p.historial_estados + HistorialEstado(
                    estado = "vencido", fecha = ahora, observacion = "Plazo de $meses mes(es) expirado",
                ),
            )
            val vencido = e.actualizarProcesoActivo(pVencido)
            val guardado = egresadoRepository.save(vencido)
            log.info("Egresado id={} marcado como vencido (plazo {} meses expirado)", e.id, meses)
            notificarPlazoResidenciaSiAplica(guardado)
            return guardado
        }
        return e
    }

    private fun notificarPlazoResidenciaSiAplica(egresado: Egresado) {
        try {
            residenciaPlazoNotificacionService.procesarPorId(egresado.id)
        } catch (ex: Exception) {
            log.warn("No se pudo procesar notificación de plazo residencia id={}: {}", egresado.id, ex.message)
        }
        try {
            noResidenciaPlazoNotificacionService.procesarPorId(egresado.id)
        } catch (ex: Exception) {
            log.warn("No se pudo procesar notificación de plazo no residencia id={}: {}", egresado.id, ex.message)
        }
    }

    private fun mesesPorModalidadFallback(modalidad: String): Long? {
        val m = modalidad.trim().lowercase()
        return when {
            m.contains("residencia") -> 6L
            m.contains("monograf") -> 18L
            m.contains("tesina") -> 12L
            m.contains("tesis") -> 12L
            m.contains("curso") -> 12L
            m.contains("investigaci") -> 12L
            m.contains("ceneval") -> null
            else -> 12L
        }
    }

    private fun nombreCompleto(e: Egresado): String =
        listOf(e.datos_personales.nombre, e.datos_personales.apellido_paterno, e.datos_personales.apellido_materno)
            .filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { e.numero_control }

    private fun construirValoresPlantillaHtml(e: Egresado, extras: List<Pair<String, String>>): Map<String, String> {
        val pr = e.procesoActivoOrNull()
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val valores = mutableMapOf(
            "NOMBRE" to nombreCompleto(e),
            "NUMERO_CONTROL" to e.numero_control,
            "CARRERA" to e.datos_personales.carrera,
            "NIVEL" to e.datos_personales.nivel.trim().ifBlank { "—" },
            "PROYECTO" to (pr?.datos_proyecto?.nombre_proyecto ?: ""),
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

    private fun fechaCartaEspanolaAnexo93(instant: Instant): String {
        val z = instant.atZone(ZoneId.systemDefault())
        val mes = nombreMesEspanol(z.monthValue)
        val dia = z.dayOfMonth.toString().padStart(2, '0')
        return "$dia de $mes del ${z.year}"
    }

    private fun nombreMesEspanol(monthValue1to12: Int): String {
        val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
        return meses.getOrElse(monthValue1to12 - 1) { "—" }.replaceFirstChar { it.uppercaseChar() }
    }

    private fun textoOpcionTitulacionIntegral(modalidad: String): String {
        val m = modalidad.trim().lowercase(Locale.ROOT)
        return when {
            m.contains("residencia") -> "REPORTE FINAL DE RESIDENCIA PROFESIONAL"
            m.contains("tesina") -> "TESINA"
            m.contains("ceneval") -> "EXAMEN CENEVAL"
            else -> modalidad.uppercase(Locale.forLanguageTag("es-MX"))
        }
    }

    private fun generarPdfAnexo(titulo: String, templateProperty: String, defaultTemplateClasspath: String, e: Egresado, extras: List<Pair<String, String>>): ByteArray? {
        val pr = e.procesoActivoOrNull()
        val valores = mutableMapOf(
            "NOMBRE" to nombreCompleto(e),
            "NUMERO_CONTROL" to e.numero_control,
            "CARRERA" to e.datos_personales.carrera,
            "PROYECTO" to (pr?.datos_proyecto?.nombre_proyecto ?: ""),
            "FECHA_GENERACION" to DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        )
        for ((k, v) in extras) valores[k] = v
        expandirAliasPlantilla(valores)
        val docxTemplate = cargarPlantillaDocx(templateProperty, defaultTemplateClasspath)
        if (docxTemplate == null) {
            log.warn("No se pudo cargar la plantilla DOCX para {}: revisa {} o classpath {}", titulo, templateProperty, defaultTemplateClasspath)
            return null
        }
        val pdfPlantilla = convertirDocxTemplateAPdf(docxTemplate, valores)
        if (pdfPlantilla != null) return pdfPlantilla
        log.warn("LibreOffice no generó PDF para {}.", titulo)
        return null
    }

    private fun cargarPlantillaDocx(templateProperty: String, defaultTemplateClasspath: String): ByteArray? {
        val rutaConfig = env.getProperty(templateProperty)?.trim().orEmpty()
        if (rutaConfig.isNotEmpty()) {
            try {
                val path = Paths.get(rutaConfig)
                if (Files.isRegularFile(path)) return Files.readAllBytes(path)
            } catch (_: Exception) { }
            val f = File(rutaConfig)
            if (f.exists() && f.isFile) return Files.readAllBytes(f.toPath())
            log.warn("Plantilla configurada no existe o no es legible: {}={}", templateProperty, rutaConfig)
        }
        return try { ClassPathResource(defaultTemplateClasspath).inputStream.use { it.readBytes() } }
        catch (_: Exception) { null }
    }

    private fun ejecutarLibreOfficeConvert(soffice: Array<String>, tmpDir: File, docxFile: File, pdfFile: File, conPerfilAislado: Boolean): Boolean {
        val args = mutableListOf(*soffice, "--headless")
        if (conPerfilAislado) args.add("-env:UserInstallation=file:///${tmpDir.absolutePath.replace('\\', '/')}/lo-profile")
        args.addAll(listOf("--convert-to", "pdf:writer_pdf_Export", "--outdir", tmpDir.absolutePath, docxFile.absolutePath))
        return try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val salida = proc.inputStream.use { stream -> String(stream.readAllBytes(), StandardCharsets.UTF_8) }
            val termino = proc.waitFor(90, TimeUnit.SECONDS)
            val exit = if (termino) proc.exitValue() else -1
            val ok = termino && exit == 0 && pdfFile.exists()
            if (!ok) log.warn("LibreOffice (perfilAislado={}): exit={}, termino={}", conPerfilAislado, exit, termino)
            ok
        } catch (ex: Exception) { log.warn("LibreOffice error: {}", ex.message); false }
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
            pdfFile.delete(); docxFile.delete()
            File(tmpDir, "lo-profile").deleteRecursively(); tmpDir.delete()
        }
    }

    private fun comandoSoffice(): Array<String> {
        val prop = env.getProperty("sit.soffice.path")?.trim().orEmpty()
        if (prop.isNotEmpty() && File(prop).isFile) return arrayOf(prop)
        System.getenv("SIT_SOFFICE")?.trim()?.takeIf { it.isNotEmpty() && File(it).isFile }?.let { return arrayOf(it) }
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            listOf("""C:\Program Files\LibreOffice\program\soffice.exe""", """C:\Program Files (x86)\LibreOffice\program\soffice.exe""")
                .firstOrNull { File(it).isFile }?.let { return arrayOf(it) }
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
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
        }
    }

    private fun subirArchivo(archivo: MultipartFile): ObjectId {
        val bytes = archivo.bytes
        val isPdf = bytes.size > 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        val isDocx = bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        require(isPdf || isDocx) { "Solo se aceptan archivos PDF o Word (.docx)" }
        val contentType = if (isPdf) "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        return gridFsTemplate.store(bytes.inputStream(), archivo.originalFilename ?: "documento", contentType, null) as ObjectId
    }

    private fun buildDocumentoAdjunto(archivo: MultipartFile?, ahora: Instant): DocumentoAdjunto {
        if (archivo == null || archivo.isEmpty) return DocumentoAdjunto()
        val gridFsId = subirArchivo(archivo)
        return DocumentoAdjunto(
            gridfs_id = gridFsId,
            nombre_original = archivo.originalFilename ?: "",
            content_type = archivo.contentType ?: "application/octet-stream",
            tamanio_bytes = archivo.size,
            fecha_subida = ahora,
        )
    }

    private fun parseFecha(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try { LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() }
        catch (_: DateTimeParseException) { null }
    }

    private fun parseFechaHoraActo93(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        val raw = s.trim()
        val normalized = if (!raw.contains('T') && raw.contains(' ')) raw.replaceFirst(" ", "T") else raw
        return try {
            LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zonaActo93).toInstant()
        } catch (_: DateTimeParseException) {
            try { LocalDateTime.parse(normalized).atZone(zonaActo93).toInstant() }
            catch (_: Exception) { null }
        }
    }

    private fun cargarEgresadoPorId(id: String): Egresado? {
        val objectId = try { ObjectId(id) } catch (_: Exception) { return null }
        return try { egresadoRepository.findByObjectIdConTimeout(objectId) }
        catch (e: Exception) { log.warn("cargarEgresadoPorId: timeout/error id={}: {}", id, e.message); null }
    }

    private fun expandirAliasPlantilla(valores: MutableMap<String, String>) {
        val nombre = valores["NOMBRE"].orEmpty()
        val control = valores["NUMERO_CONTROL"].orEmpty()
        val carrera = valores["CARRERA"].orEmpty()
        val proyecto = valores["PROYECTO"].orEmpty()
        valores.putIfAbsent("NOMBRE_COMPLETO", nombre); valores.putIfAbsent("NOMBRE_ALUMNO", nombre); valores.putIfAbsent("ALUMNO", nombre)
        valores.putIfAbsent("CONTROL", control); valores.putIfAbsent("NO_CONTROL", control); valores.putIfAbsent("NUMERO_DE_CONTROL", control)
        valores.putIfAbsent("CARRERA_COMPLETA", carrera); valores.putIfAbsent("NOMBRE_PROYECTO", proyecto)
    }

    private fun reemplazarMarcadoresDocx(docxBytes: ByteArray, valores: Map<String, String>): ByteArray {
        return try { reemplazarMarcadoresEnZipDocx(docxBytes, valores) }
        catch (ex: Exception) { log.warn("Reemplazo por ZIP falló ({}), se intenta Apache POI.", ex.message); reemplazarMarcadoresDocxPoi(docxBytes, valores) }
    }

    private fun esXmlWordParaMarcadores(entryName: String): Boolean {
        if (!entryName.startsWith("word/") || !entryName.endsWith(".xml")) return false
        val base = entryName.removePrefix("word/").substringBefore(".xml")
        return base == "document" || base.startsWith("header") || base.startsWith("footer") || base == "footnotes" || base == "endnotes"
    }

    private fun escapeXmlTextoContenido(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
                    val processed = if (!entry.isDirectory && esXmlWordParaMarcadores(name)) {
                        aplicarMarcadoresEnTextoXml(String(raw, StandardCharsets.UTF_8), valores).toByteArray(StandardCharsets.UTF_8)
                    } else raw
                    val ze = ZipEntry(name)
                    ze.time = entry.time
                    zOut.putNextEntry(ze); zOut.write(processed); zOut.closeEntry()
                    entry = zIn.nextEntry
                }
            }
        }
        return outBytes.toByteArray()
    }

    private fun reemplazarMarcadoresDocxPoi(docxBytes: ByteArray, valores: Map<String, String>): ByteArray {
        val doc = XWPFDocument(ByteArrayInputStream(docxBytes))
        fun reemplazarEnTexto(texto: String): String {
            var result = texto
            valores.forEach { (k, v) ->
                result = result.replace("{{$k}}", v.ifBlank { "—" }, ignoreCase = true)
                result = result.replace("[$k]", v.ifBlank { "—" }, ignoreCase = true)
            }
            return result
        }
        doc.paragraphs.forEach { para ->
            para.runs.forEach { run -> run.setText(reemplazarEnTexto(run.text() ?: ""), 0) }
        }
        doc.tables.forEach { table ->
            table.rows.forEach { row ->
                row.tableCells.forEach { cell ->
                    cell.paragraphs.forEach { para ->
                        para.runs.forEach { run -> run.setText(reemplazarEnTexto(run.text() ?: ""), 0) }
                    }
                }
            }
        }
        val out = ByteArrayOutputStream()
        doc.write(out)
        return out.toByteArray()
    }
}
