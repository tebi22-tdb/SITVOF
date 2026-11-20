package com.sit_titulacion.sit.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sit_titulacion.sit.domain.AnexoXxxi
import com.sit_titulacion.sit.domain.Catalogo
import com.sit_titulacion.sit.domain.ConstanciaNoInconveniencia
import com.sit_titulacion.sit.domain.DatosPersonales
import com.sit_titulacion.sit.domain.DatosProyecto
import com.sit_titulacion.sit.domain.DocumentoAdjunto
import com.sit_titulacion.sit.domain.Documentos
import com.sit_titulacion.sit.domain.ArchivoEscaneadoMeta
import com.sit_titulacion.sit.domain.DocumentacionEscaneada
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.HistorialEstado
import com.sit_titulacion.sit.domain.PasoFlujoCatalogo
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.domain.SinodalesTribunal
import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.CarreraRepository
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DepartamentoRepository
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
import com.sit_titulacion.sit.web.api.dto.PlazoDesarrolloDto
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

data class Anexo93Generado(
    val pdf: ByteArray,
    val sinodalesConCorreo: Int,
    val notificados: Int,
    val enProceso: Int,
)

data class DepartamentoCounts(
    val pendientes: Int,
    val en_correccion: Int,
    val aprobados: Int,
    val todos: Int,
    val sinodales_por_asignar: Int,
)

data class PestanaDepartamentoDto(
    val nombre: String,
    val total: Int,
)

data class BandejaDepartamentoDto(
    val counts: Map<String, Int>,
    val items: List<DepartamentoListItemDto>,
    val pestanas: List<PestanaDepartamentoDto> = emptyList(),
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
    private val carreraRepository: CarreraRepository,
    private val departamentoRepository: DepartamentoRepository,
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
    private val configInstitucionalService: ConfiguracionInstitucionalService,
) {
    private val log = LoggerFactory.getLogger(EgresadoService::class.java)
    private val citatoriosEnCurso = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val registroCitatorio = Any()

    /**
     * Zona usada para interpretar la fecha/hora del acto 9.3 enviada sin offset (como en flatpickr)
     * y para validar ventana horaria / PDF del acto. Debe coincidir con la zona institucional, no con la del host (p. ej. UTC en contenedor).
     * Override: `sit.acto93.zona-horaria` (ej. `America/Mazatlan`).
     */
    private val zonaMexico: ZoneId = ZoneId.of("America/Mexico_City")

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
                tipo_titulacion = normalizarTipoTitulacion(datos.tipoTitulacion, datos.modalidad),
                curso_titulacion = normalizarCursoTitulacion(datos.cursoTitulacion, datos.modalidad),
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
                genero = GeneroPorNombre.normalizarCodigo(datos.genero),
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
                tipo_titulacion = normalizarTipoTitulacion(datos.tipoTitulacion, datos.modalidad),
                curso_titulacion = normalizarCursoTitulacion(datos.cursoTitulacion, datos.modalidad),
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
                tipo_titulacion = normalizarTipoTitulacion(datos.tipoTitulacion, datos.modalidad),
                curso_titulacion = normalizarCursoTitulacion(datos.cursoTitulacion, datos.modalidad),
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
                    genero = GeneroPorNombre.normalizarCodigo(datos.genero),
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
            egresadoRepository.findByNumeroControlContaining(Regex.escape(numeroControlFilter.trim())).sortedWith(porReciente)
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
            val plazo = clasificarPlazo(e, pr)
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
                fecha_confirmacion_entrega_egresado_depto = pr?.fechaConfirmacionEntregaEgresadoDepto?.let { formatter.format(it) },
                fecha_recepcion_trabajo_division_estudios_prof = pr?.fechaRecepcionTrabajoDivisionEstudiosProf?.let { formatter.format(it) },
                fecha_recepcion_registro_liberacion_depto_academico = pr?.fechaRecepcionRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
                fecha_creacion_anexo_9_1 = pr?.fechaCreacionAnexo91?.let { formatter.format(it) },
                fecha_confirmacion_entrega_anexo_9_1 = pr?.fechaConfirmacionEntregaAnexo91?.let { formatter.format(it) },
                fecha_solicitud_anexo_9_2 = pr?.fechaSolicitudAnexo92?.let { formatter.format(it) },
                fecha_aceptacion_servicios_escolares_anexo_9_2 = pr?.fechaAceptacionServiciosEscolaresAnexo92?.let { formatter.format(it) },
                fecha_confirmacion_recibido_anexo_9_2 = pr?.fechaConfirmacionRecibidoAnexo92?.let { formatter.format(it) },
                fecha_solicitud_sinodales = pr?.fechaSolicitudSinodales?.let { formatter.format(it) },
                fecha_confirmacion_sinodales_recibidos = pr?.fechaConfirmacionSinodalesRecibidos?.let { formatter.format(it) },
                fecha_agenda_acto_9_3 = pr?.fechaAgendaActo93?.let { formatter.format(it) },
                estado_plazo = plazo.estado,
                dias_restantes_plazo = plazo.dias,
                fecha_limite_plazo = plazo.fechaLimite?.let { formatter.format(it) },
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

    fun contarParaDepartamento(academicoUsername: String, segmentoSlug: String? = null): Map<String, Int> =
        calcularConteosBandeja(prepararBandejaDepartamento(academicoUsername, segmentoSlug))

    fun bandejaDepartamento(estado: String, academicoUsername: String, segmentoSlug: String? = null): BandejaDepartamentoDto {
        val ctx = prepararBandejaDepartamento(academicoUsername, segmentoSlug)
        return BandejaDepartamentoDto(
            counts = calcularConteosBandeja(ctx),
            items = listarParaDepartamentoConCtx(ctx, estado),
            pestanas = pestanasPropiasBandeja(ctx),
        )
    }

    fun listarParaDepartamento(estado: String, academicoUsername: String, segmentoSlug: String? = null): List<DepartamentoListItemDto> =
        listarParaDepartamentoConCtx(prepararBandejaDepartamento(academicoUsername, segmentoSlug), estado)

    private data class BandejaDepartamentoCtx(
        val porCarrera: List<Egresado>,
        val allLiberar: List<Egresado>,
        val ultimasRevisiones: Map<Pair<ObjectId, ObjectId>, String>,
        val segmentoSlug: String?,
        /** Pestaña Residencia del departamento: solo Residencia Profesional de sus carreras. */
        val soloResidenciaEnPendientes: Boolean,
    )

    private fun prepararBandejaDepartamento(academicoUsername: String, segmentoSlug: String?): BandejaDepartamentoCtx {
        val allBase = filtrarPorCarrerasDeSuDepartamento(
            filtrarEgresadosPorCarreraSiAcademico(egresadoRepository.findAll(), academicoUsername),
            academicoUsername,
        )
        val excluirResidencia = bandejaDepartamentoExcluyeResidencia(academicoUsername) && segmentoSlug.isNullOrBlank()
        val afterModalidad = if (excluirResidencia) allBase.filter { !esResidenciaProfesional(it) } else allBase
        val porCarrera = aplicarFiltroSegmentoCoordinacion(afterModalidad, segmentoSlug, academicoUsername)
        val allLiberar = filtrarBandejaSegmentoCoordinacion(porCarrera, segmentoSlug, academicoUsername)
        val paresRevision = porCarrera.mapNotNull { e ->
            if (esResidenciaProfesional(e)) return@mapNotNull null
            val eid = e.id ?: return@mapNotNull null
            val pid = e.procesoActivoOrNull()?.id ?: return@mapNotNull null
            eid to pid
        }
        val ultimasRevisiones = revisionService.ultimosResultadosPorProcesos(paresRevision)
        return BandejaDepartamentoCtx(
            porCarrera,
            allLiberar,
            ultimasRevisiones,
            segmentoSlug?.trim()?.lowercase()?.ifBlank { null },
            soloResidenciaEnPendientes = academicoDeDepartamento(academicoUsername),
        )
    }

    private fun calcularConteosBandeja(ctx: BandejaDepartamentoCtx): Map<String, Int> {
        val porCarrera = ctx.porCarrera
        val allLiberar = ctx.allLiberar
        val pendientes = allLiberar.count {
            val enviado = it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) && !enCorreccionAcademico(it, ctx)
            enviado && (!ctx.soloResidenciaEnPendientes || llegaAPestanaResidencia(it))
        }
        val enCorreccion = allLiberar.count {
            it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                !liberacionRevisionCompletada(it) && enCorreccionAcademico(it, ctx)
        }
        val aprobados = allLiberar.count { liberacionRevisionCompletada(it) }
        val totalResidencia = allLiberar.count { llegaAPestanaResidencia(it) }
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
            "total_residencia" to totalResidencia,
            "todos" to todos,
            "sinodales_por_asignar" to sinodales,
            "anteproyecto" to anteproyecto,
            "total_anteproyecto" to totalAnteproyecto,
            "liberacion_producto" to liberacionProducto,
            "total_liberacion_producto" to totalLiberacionProducto,
            "total_sinodales" to totalSinodales,
        )
    }

    private fun fechaLlegadaBandeja(e: Egresado, norm: String): Instant {
        val pr = e.procesoActivoOrNull()
        val fecha = when (norm) {
            "anteproyecto" -> pr?.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico
            "liberacion_producto" -> pr?.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii
            else -> pr?.fechaEnviadoDepartamentoAcademico
        }
        return fecha ?: e.fecha_actualizacion
    }

    private fun pestanaFija(nombre: String): Boolean {
        val n = nombre.trim().lowercase()
        return n == "residencia" || n == "anteproyecto" || n == "liberación de producto" ||
            n == "liberacion de producto" || n == "sinodales"
    }

    private fun llegaAPestanaResidencia(e: Egresado): Boolean {
        val pr = e.procesoActivoOrNull() ?: return false
        if (pr.fechaEnviadoDepartamentoAcademico == null) return false
        val pestana = pr.pestanaDepartamento?.trim().orEmpty()
        if (pestana.isNotEmpty() && !pestana.equals("Residencia", ignoreCase = true)) return false
        return esResidenciaProfesional(e) || pestana.equals("Residencia", ignoreCase = true)
    }

    private fun pestanasPropiasBandeja(ctx: BandejaDepartamentoCtx): List<PestanaDepartamentoDto> {
        val conteo = ctx.porCarrera.mapNotNull { eg ->
            val pr = eg.procesoActivoOrNull() ?: return@mapNotNull null
            val nombre = pr.pestanaDepartamento?.trim()?.takeIf { it.isNotEmpty() && !pestanaFija(it) }
                ?: return@mapNotNull null
            if (pr.fechaEnviadoDepartamentoAcademico == null) null else nombre
        }.groupingBy { it }.eachCount().toMutableMap()
        catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .filter { it.origenFlujo == "personalizado" }
            .flatMap { it.pasos }
            .map { it.pestanaDepartamento.trim() }
            .filter { it.isNotEmpty() && !pestanaFija(it) }
            .forEach { nombre -> conteo.putIfAbsent(nombre, 0) }
        return conteo.map { PestanaDepartamentoDto(it.key, it.value) }.sortedBy { it.nombre.lowercase() }
    }

    private fun listarParaDepartamentoConCtx(ctx: BandejaDepartamentoCtx, estado: String): List<DepartamentoListItemDto> {
        val porCarrera = ctx.porCarrera
        val norm = estado.trim().lowercase()
        val all = when (norm) {
            "sinodales", "todos", "anteproyecto", "liberacion_producto" -> porCarrera
            else -> ctx.allLiberar
        }
        val lista = if (norm.startsWith("pestana:")) {
            val nombre = estado.trim().substringAfter(":").trim()
            porCarrera.filter { eg ->
                val pr = eg.procesoActivoOrNull()
                pr?.fechaEnviadoDepartamentoAcademico != null &&
                    pr.pestanaDepartamento?.trim().equals(nombre, ignoreCase = true)
            }
        } else when (norm) {
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
                    !liberacionRevisionCompletada(it) && enCorreccionAcademico(it, ctx)
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
            else -> if (!ctx.segmentoSlug.isNullOrBlank() || ctx.soloResidenciaEnPendientes) {
                all.filter { llegaAPestanaResidencia(it) }
            } else {
                all.filter {
                    it.procesoActivoOrNull()?.fechaEnviadoDepartamentoAcademico != null &&
                        !liberacionRevisionCompletada(it) && !enCorreccionAcademico(it, ctx)
                }
            }
        }
        val listaOrdenada = if (norm == "sinodales") lista else lista.sortedByDescending { fechaLlegadaBandeja(it, norm) }
        val formatter = DateTimeFormatter.ISO_INSTANT
        return listaOrdenada.map { e ->
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
                estadoRevision = estadoRevisionDepartamento(e, ctx),
                fechaSolicitudSinodales = pr.fechaSolicitudSinodales?.let { formatter.format(it) },
                sinodalesAsignados = pr.fechaAsignacionSinodales != null,
                fechaEnvioAnteproyectoDepto = pr.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico?.let { formatter.format(it) },
                fechaRegistradoDepartamento = pr.fechaRegistradoDepartamento?.let { formatter.format(it) },
                fechaLiberacionProducto = pr.fechaSolicitudRegistroLiberacionDeptoAcademico?.let { formatter.format(it) },
                fechaGeneracionAnexosXxxiiXxxxiii = pr.fechaGeneracionAnexosXxxiiXxxxiii?.let { formatter.format(it) },
                fechaGeneracionHoja32 = pr.fechaGeneracionHoja32?.let { formatter.format(it) },
                fechaGeneracionHoja33 = pr.fechaGeneracionHoja33?.let { formatter.format(it) },
                fechaConfirmacionEntregaAnexo93 = pr.fechaConfirmacionEntregaAnexo93?.let { formatter.format(it) },
                asesorFirma = if (esResidenciaProfesional(e)) pr.datos_proyecto.asesor_interno else pr.datos_proyecto.director,
                revisor1 = pr.datos_proyecto.asesor_1,
                revisor2 = pr.datos_proyecto.asesor_2,
                asesorInterno = pr.datos_proyecto.asesor_interno,
                asesorExterno = pr.datos_proyecto.asesor_externo,
                observacionesAnexo32 = pr.datos_proyecto.observaciones_anexo32,
            )
        }
    }

    fun academicoPuedeAccederAEgresado(academicoUsername: String, egresadoId: String): Boolean {
        val e = cargarEgresadoPorId(egresadoId) ?: return false
        val bloquearResidenciaComoVistaGlobal =
            bandejaDepartamentoExcluyeResidencia(academicoUsername) && !puedeUsarFiltroSegmentoCoordinacion(academicoUsername)
        if (bloquearResidenciaComoVistaGlobal && esResidenciaProfesional(e)) return false
        if (academicoDeDepartamento(academicoUsername)) {
            val delDepartamento = filtrarPorCarrerasDeSuDepartamento(listOf(e), academicoUsername)
            if (delDepartamento.isEmpty()) return false
        }
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return true
        if (permitidas.isEmpty()) return true
        return carreraPermitidaParaAcademico(e.datos_personales.carrera, permitidas)
    }

    /**
     * Busca el departamento aunque el slug guardado no coincida carácter por carácter
     * (mayúsculas, guion o acento). Si no hay coincidencia exacta, compara la forma normalizada.
     */
    private fun departamentoPorSlug(slug: String): Catalogo? {
        val crudo = slug.trim()
        if (crudo.isEmpty()) return null
        catalogoRepository.findFirstByTipoAndSlug("departamento", crudo)?.let { return it }
        val lower = crudo.lowercase()
        if (lower != crudo) {
            catalogoRepository.findFirstByTipoAndSlug("departamento", lower)?.let { return it }
        }
        val clave = NormalizacionTexto.normalizarSlug(crudo)
        if (clave.isEmpty()) return null
        return catalogoRepository.findByTipoAndActivoTrue("departamento").firstOrNull { cat ->
            NormalizacionTexto.normalizarSlug(cat.slug.orEmpty()) == clave
        }
    }

    /** Académico de un departamento: solo las carreras de ese departamento. */
    private fun filtrarPorCarrerasDeSuDepartamento(egresados: List<Egresado>, username: String): List<Egresado> {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return emptyList()
        if (u.rol != Rol.ACADEMICO) return egresados
        val slug = u.segmentoAcademico?.trim()?.takeIf { it.isNotEmpty() } ?: return egresados
        val cat = departamentoPorSlug(slug)
        val permitidas = cat?.carreras?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: u.carrerasAsignadas.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return emptyList()
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun academicoDeDepartamento(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        return u.rol == Rol.ACADEMICO && !u.segmentoAcademico?.trim().isNullOrEmpty()
    }

    private fun filtrarEgresadosPorCarreraSiAcademico(egresados: List<Egresado>, academicoUsername: String): List<Egresado> {
        val permitidas = carrerasFiltroAcademico(academicoUsername) ?: return egresados
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun filtrarBandejaSegmentoCoordinacion(egresados: List<Egresado>, segmentoSlug: String?, username: String): List<Egresado> {
        val slug = segmentoSlug?.trim()?.takeIf { it.isNotEmpty() } ?: return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = departamentoPorSlug(slug) ?: return egresados
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { esResidenciaProfesional(it) }
    }

    private fun aplicarFiltroSegmentoCoordinacion(egresados: List<Egresado>, segmentoSlug: String?, username: String): List<Egresado> {
        val slug = segmentoSlug?.trim()?.takeIf { it.isNotEmpty() } ?: return egresados
        if (!puedeUsarFiltroSegmentoCoordinacion(username)) return egresados
        val cat = departamentoPorSlug(slug) ?: return egresados
        val nDup = catalogoRepository.countByTipoAndSlug("departamento", cat.slug ?: slug)
        if (nDup > 1) log.warn("Catálogo 'departamento' slug='{}' tiene {} duplicados.", slug, nDup)
        val permitidas = cat.carreras.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (permitidas.isEmpty()) return egresados
        return egresados.filter { carreraPermitidaParaAcademico(it.datos_personales.carrera, permitidas) }
    }

    private fun puedeUsarFiltroSegmentoCoordinacion(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        return u.rol.esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION, Rol.DEP)
    }

    /** internal (no private): la reutiliza `ReporteService` para forzar el scope del reporte de académico. */
    internal fun carrerasFiltroAcademico(username: String): Set<String>? {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return null
        if (u.rol != Rol.ACADEMICO) return null
        val permitidas = u.carrerasAsignadas.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (permitidas.isEmpty()) null else permitidas
    }

    private fun esAcademicoSoloRevisiones(username: String): Boolean {
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (u.rol != Rol.ACADEMICO) return false
        return u.segmentoAcademico?.trim().isNullOrEmpty() && u.carrerasAsignadas.isEmpty()
    }

    private fun bandejaDepartamentoExcluyeResidencia(username: String): Boolean {
        if (esAcademicoSoloRevisiones(username)) return true
        val u = usuarioRepository.findByUsername(username.trim()) ?: return false
        if (!u.segmentoAcademico.isNullOrBlank()) return false
        if (u.carrerasAsignadas.isNotEmpty()) return false
        return u.rol.esAlgunoDe(Rol.ADMINISTRADOR, Rol.APOYO_TITULACION, Rol.DEP)
    }

    /**
     * Departamento académico (slug + nombre) al que pertenece la carrera del egresado.
     * Antes iteraba `Catalogo.carreras` a mano; ahora resuelve contra `carreras`/
     * `departamentos` (id estable, normalización única) — ver `NormalizacionTexto`
     * y `CarreraDepartamentoSyncRunner`.
     */
    private fun resolverDepartamentoPorCarrera(carrera: String): Pair<String, String>? {
        val slug = NormalizacionTexto.normalizarClave(carrera)
        if (slug.isEmpty()) return null
        val carreraDoc = carreraRepository.findBySlug(slug) ?: return null
        val depto = departamentoRepository.findByCarreraIdsContains(carreraDoc.id)
            .filter { it.activo }
            .maxByOrNull { it.orden }
            ?: return null
        return depto.slug to depto.nombre
    }

    /** Jefe de división, jefe de departamento e iniciales desde Configuración Institucional. */
    private fun resolverDatosInstitucionalesDocumento(carrera: String): DatosInstitucionalesDocumento {
        val dept = configInstitucionalService.resolverPorCarrera(carrera)
        val titularNombre = dept?.jefeNombre?.trim()?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.oficio-sinodales.jefa-departamento-nombre", "")?.trim()?.takeIf { it.isNotBlank() }
            ?: "TITULAR DEL DEPARTAMENTO ACADÉMICO"
        val iniciales = dept?.jefeIniciales?.trim()?.takeIf { it.isNotBlank() }
            ?: env.getProperty("sit.oficio-sinodales.iniciales-firma", "MDJ/mcgl").trim()
        return DatosInstitucionalesDocumento(
            jefeDivisionNombre = configInstitucionalService.obtenerJefeDivisionNombre(),
            jefeDivisionCargo = configInstitucionalService.obtenerJefeDivisionCargo(),
            titularDeptoNombre = titularNombre,
            titularDeptoCargo = dept?.jefeCargo?.trim()?.takeIf { it.isNotBlank() },
            inicialesFirma = iniciales,
        )
    }

    /** El académico que genera el anexo usa su departamento. Si no, el de la carrera. */
    private fun departamentoAnexo(
        carrera: String,
        slugGenerador: String?,
    ): com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoResponseDto? {
        val propio = slugGenerador?.trim()?.takeIf { it.isNotEmpty() }?.let { configInstitucionalService.resolverPorSlug(it) }
        return propio ?: configInstitucionalService.resolverPorCarrera(carrera)
    }

    private fun nombreDepartamentoAnexo(
        dept: com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoResponseDto?,
        carrera: String,
    ): String =
        dept?.departamentoNombreCompleto?.trim()?.takeIf { it.isNotEmpty() }
            ?: dept?.nombre?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolverDepartamentoPorCarrera(carrera)?.second
            ?: ""

    /** Nombre, cargo e iniciales que firman los anexos XXXII y XXXIII. */
    private data class FirmaJefeAnexo(val nombre: String, val cargo: String, val iniciales: String)

    private fun firmaJefeAnexo(dept: com.sit_titulacion.sit.web.api.dto.ConfigDepartamentoResponseDto?): FirmaJefeAnexo {
        val slug = dept?.slug?.trim()?.lowercase().orEmpty().replace('-', '_')
        val nombreDepto = NormalizacionTexto.normalizarClave(
            listOf(dept?.departamentoNombreCompleto, dept?.nombre).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
        )
        val esBasicas = slug.contains("ciencias_basicas") || nombreDepto.contains("ciencias basicas")
        val nombreGuardado = dept?.jefeNombre?.trim()?.takeIf { it.isNotBlank() }
        val cargoGuardado = dept?.jefeCargo?.trim()?.takeIf { it.isNotBlank() }
        val inicialesGuardadas = dept?.jefeIniciales?.trim()?.takeIf { it.isNotBlank() && !it.equals("MDJ/mcgl", ignoreCase = true) }
        return if (esBasicas) {
            FirmaJefeAnexo(
                nombre = nombreGuardado ?: "MARIANA DÍAZ JARQUÍN",
                cargo = cargoGuardado ?: "JEFA DEL DEPARTAMENTO DE CIENCIAS BÁSICAS",
                iniciales = inicialesGuardadas ?: "MDJ/mdj",
            )
        } else {
            FirmaJefeAnexo(
                nombre = nombreGuardado.orEmpty(),
                cargo = cargoGuardado.orEmpty(),
                iniciales = dept?.jefeIniciales?.trim().orEmpty(),
            )
        }
    }

    private data class DatosInstitucionalesDocumento(
        val jefeDivisionNombre: String,
        val jefeDivisionCargo: String,
        val titularDeptoNombre: String,
        val titularDeptoCargo: String?,
        val inicialesFirma: String,
    )

    private fun carreraPermitidaParaAcademico(carrera: String, permitidas: Set<String>): Boolean {
        val key = NormalizacionTexto.normalizarClave(carrera)
        if (key.isEmpty()) return false
        return permitidas.any { NormalizacionTexto.normalizarClave(it) == key }
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

    private fun esExpedienteConclusoDefinitivo(e: Egresado): Boolean {
        val p = e.procesoActivoOrNull() ?: return false
        val estado = p.estado_general.trim().lowercase()
        return estado == "titulado" || tieneUltimoPasoSeguimientoCompletado(p)
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

        val concluso = expedientes.firstOrNull { esExpedienteConclusoDefinitivo(it) }
        if (concluso != null) return VerificacionDuplicadoAlta("BLOQUEADO", "titulado", concluso.id?.toString())

        val egresadoId = expedientes.firstOrNull()?.id?.toString()
        val modalidadNueva = normalizarModalidadAlta(modalidad)
        if (modalidadNueva.isBlank()) {
            return VerificacionDuplicadoAlta("BLOQUEADO", "vencido", egresadoId)
        }

        val modalidadesCerradas = expedientes
            .flatMap { it.procesos }
            .filter { p ->
                val est = p.estado_general.trim().lowercase()
                est == "vencido" || est == "titulado"
            }
            .map { normalizarModalidadAlta(it.datos_proyecto.modalidad) }
            .filter { it.isNotBlank() }
            .toSet()
        if (modalidadNueva in modalidadesCerradas) {
            return VerificacionDuplicadoAlta("BLOQUEADO", "vencido", egresadoId)
        }

        // Modalidad distinta permitida: retomar el mismo expediente (nuevo proceso), nunca alta duplicada.
        return VerificacionDuplicadoAlta("RETOMAR", "vencido", egresadoId)
    }

    fun validarNuevaAltaNumeroControl(datos: EgresadoRequestDto): String? {
        val verificacion = verificarDisponibilidadNumeroControlParaAlta(numero = datos.numero_control, excluirId = null, modalidad = datos.modalidad)
        if (verificacion.estado == "RETOMAR") {
            return "Este número de control ya tiene un expediente vencido. Debes registrar un nuevo proceso sobre ese expediente (elige otra modalidad), no un alta nueva."
        }
        if (verificacion.estado != "BLOQUEADO") return null
        return when (verificacion.expedienteEstado?.trim()) {
            "en_proceso" -> "Este número de control ya tiene un expediente en proceso."
            "titulado" -> "Este número de control ya concluyó su proceso de titulación. No es posible darlo de alta nuevamente."
            "vencido" -> "Este número de control ya tuvo un expediente vencido; para darlo de alta nuevamente debes elegir una modalidad distinta."
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
            contentType = tipoContenidoDescarga(adj.content_type),
            fileName = adj.nombre_original.ifBlank { defaultName },
        )
    }

    /** No reenviar al navegador un tipo que pueda ejecutarse (HTML, SVG, script). */
    private fun tipoContenidoDescarga(raw: String): String {
        val ct = raw.lowercase().substringBefore(";").trim()
        if (
            ct.isBlank() ||
            ct.contains("html") ||
            ct.contains("xml") ||
            ct.contains("javascript") ||
            ct.contains("svg") ||
            ct.startsWith("text/")
        ) {
            return "application/pdf"
        }
        return raw
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
            contentType = tipoContenidoDescarga(archivo.contentType),
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
            content_type = "application/pdf",
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
        if (ultima.fechaCorreccionEgresado != null) {
            return "Ya enviaste la corrección de esta revisión. Espera una nueva revisión de Coordinación."
        }
        val ahora = Instant.now()
        val gridFsIdAnterior = p.documento_adjunto.gridfs_id
        return try {
            val gridFsIdNuevo = subirArchivo(archivo)
            val nuevoAdj = DocumentoAdjunto(
                gridfs_id = gridFsIdNuevo,
                nombre_original = archivo.originalFilename ?: "",
                content_type = "application/pdf",
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
            revisionService.marcarCorreccionEgresado(eid, p.id, ahora)
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
            ex.message ?: "Solo se aceptan archivos PDF."
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

    /** Deshace la liberación de residencia (anexos XXXII/XXXIII) en bandeja departamento. Solo si la DEP no avanzó. */
    fun revertirLiberacionResidencia(id: String): String? {
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        if (!esResidenciaProfesional(e)) return "Solo aplica a residencia profesional."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaGeneracionAnexosXxxiiXxxxiii == null && p.fechaRecibidoRegistroLiberacion == null) {
            return "No está liberado."
        }
        if (p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null) {
            return "No se puede revertir: la DEP ya confirmó la recepción de anexos."
        }
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaRecibidoRegistroLiberacion = null,
                    fechaGeneracionAnexosXxxiiXxxxiii = null,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return null
    }

    /** Deshace «marcar como registrado» del anteproyecto en bandeja departamento. */
    fun revertirRegistradoAnteproyecto(id: String): String? {
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        if (esResidenciaProfesional(e)) return "No aplica a residencia."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaRegistradoDepartamento == null) return "No está marcado como registrado."
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null) {
            return "No se puede revertir: la DEP ya confirmó la recepción inicial."
        }
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(p.copy(fechaRegistradoDepartamento = null, fecha_actualizacion = ahora)),
        )
        return null
    }

    /** Deshace la liberación de producto (tesis) subida por el departamento académico. */
    fun revertirLiberacionProducto(id: String): String? {
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        if (esResidenciaProfesional(e)) return "Solo aplica a modalidades distintas de residencia."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaSolicitudRegistroLiberacionDeptoAcademico == null) return "No está liberado."
        if (p.fechaRecepcionRegistroLiberacionDeptoAcademico != null) {
            return "No se puede revertir: la DEP ya confirmó la recepción."
        }
        val mismoInstanteTrabajo =
            p.fechaRecepcionTrabajoDivisionEstudiosProf == p.fechaSolicitudRegistroLiberacionDeptoAcademico
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaSolicitudRegistroLiberacionDeptoAcademico = null,
                    fechaRecepcionTrabajoDivisionEstudiosProf =
                        if (mismoInstanteTrabajo) null else p.fechaRecepcionTrabajoDivisionEstudiosProf,
                    tesisLiberacionAdjunto = DocumentoAdjunto(),
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return null
    }

    /** Deshace aprobación en Coordinación de apoyo (revisión o liberación de residencia). */
    fun revertirAprobacionCoordinacion(id: String): String? {
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaEnviadoDepartamentoAcademico == null) return "No aplica a expedientes no enviados al departamento."
        if (!liberacionRevisionCompletada(e)) return "No está aprobado."
        if (esResidenciaProfesional(e) && p.fechaGeneracionAnexosXxxiiXxxxiii != null) {
            return revertirLiberacionResidencia(id)
        }
        if (p.fechaCreacionAnexo91 != null) {
            return "No se puede revertir: el trámite ya avanzó en la DEP."
        }
        if (p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null && p.fechaLiberacionDocumentoCoordinacionCat == null) {
            return "No se puede revertir: la DEP ya confirmó la recepción de anexos."
        }
        return revisionService.revertirUltimaAprobacion(id)
    }

    /** Deshace un paso del timeline de seguimiento (DEP), solo si el trámite no avanzó al siguiente paso. */
    fun revertirPasoSeguimiento(id: String, pasoKey: String): String? {
        val key = pasoKey.trim()
        if (key.isEmpty()) return "Paso no válido."
        when (key) {
            "fecha_solicitud_registro_liberacion_depto_academico" -> return revertirLiberacionProducto(id)
            "fecha_recepcion_trabajo_division_estudios_prof" -> return revertirLiberacionProducto(id)
        }
        val e = cargarEgresadoPorId(id) ?: return "Registro no encontrado."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        val ahora = Instant.now()
        fun bloqueado(cond: Boolean, msg: String): String? = if (cond) msg else null

        return when (key) {
            "fecha_confirmacion_entrega_egresado_depto" -> {
                bloqueado(
                    p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null,
                    "No se puede revertir: ya se envió la solicitud al departamento académico.",
                )?.let { return it }
                if (p.fechaConfirmacionEntregaEgresadoDepto == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionEntregaEgresadoDepto = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_envio_solicitud_registro_anteproyecto_depto_academico" -> {
                bloqueado(
                    p.fechaRegistradoDepartamento != null,
                    "No se puede revertir: el departamento académico ya marcó como registrado.",
                )?.let { return it }
                bloqueado(
                    p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null,
                    "No se puede revertir: la DEP ya confirmó la recepción del registro.",
                )?.let { return it }
                if (p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(
                            fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = null,
                            fecha_actualizacion = ahora,
                        ),
                    ),
                )
                null
            }
            "fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii" -> {
                bloqueado(
                    p.fechaSolicitudRegistroLiberacionDeptoAcademico != null,
                    "No se puede revertir: ya hay liberación de producto en departamento académico.",
                )?.let { return it }
                bloqueado(
                    p.fechaRecepcionRegistroLiberacionDeptoAcademico != null,
                    "No se puede revertir: la DEP ya confirmó la recepción de la liberación.",
                )?.let { return it }
                bloqueado(
                    p.fechaEnviadoDepartamentoAcademico != null,
                    "No se puede revertir: el trámite ya fue enviado a Coordinación de Apoyo.",
                )?.let { return it }
                if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(
                            fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = null,
                            fechaRecepcionTrabajoDivisionEstudiosProf =
                                if (p.fechaSolicitudRegistroLiberacionDeptoAcademico == null) null
                                else p.fechaRecepcionTrabajoDivisionEstudiosProf,
                            fecha_actualizacion = ahora,
                        ),
                    ),
                )
                null
            }
            "fecha_recepcion_registro_liberacion_depto_academico" -> {
                bloqueado(
                    p.fechaEnviadoDepartamentoAcademico != null,
                    "No se puede revertir: el trámite ya fue enviado a Coordinación de Apoyo.",
                )?.let { return it }
                bloqueado(
                    p.fechaLiberacionDocumentoCoordinacionCat != null,
                    "No se puede revertir: Coordinación de Apoyo ya aprobó la revisión.",
                )?.let { return it }
                bloqueado(
                    p.fechaCreacionAnexo91 != null,
                    "No se puede revertir: el trámite ya avanzó en la DEP.",
                )?.let { return it }
                if (p.fechaRecepcionRegistroLiberacionDeptoAcademico == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaRecepcionRegistroLiberacionDeptoAcademico = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_enviado_departamento_academico" -> {
                bloqueado(
                    p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null,
                    "No se puede revertir: la DEP ya confirmó la recepción de anexos.",
                )?.let { return it }
                bloqueado(
                    p.fechaLiberacionDocumentoCoordinacionCat != null,
                    "No se puede revertir: Coordinación de Apoyo ya aprobó la revisión.",
                )?.let { return it }
                bloqueado(
                    p.fechaCreacionAnexo91 != null,
                    "No se puede revertir: el trámite ya avanzó en la DEP.",
                )?.let { return it }
                if (p.fechaEnviadoDepartamentoAcademico == null) return "El paso no está completado."
                val pRevertido = if (esResidenciaProfesional(e) && p.fechaCertificacion != null) {
                    p.copy(
                        fechaEnviadoDepartamentoAcademico = null,
                        fechaCertificacion = null,
                        cert_uuid = null,
                        cert_hash = null,
                        fecha_actualizacion = ahora,
                    )
                } else {
                    p.copy(fechaEnviadoDepartamentoAcademico = null, fecha_actualizacion = ahora)
                }
                egresadoRepository.save(e.actualizarProcesoActivo(pRevertido))
                null
            }
            "fecha_confirmacion_recibidos_anexo_xxxi_xxxii" -> {
                bloqueado(
                    p.fechaCreacionAnexo91 != null,
                    "No se puede revertir: ya se generó el anexo 9.1.",
                )?.let { return it }
                if (p.fechaConfirmacionRecibidosAnexoXxxiXxxii == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionRecibidosAnexoXxxiXxxii = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_creacion_anexo_9_1" -> {
                bloqueado(
                    p.fechaConfirmacionEntregaAnexo91 != null,
                    "No se puede revertir: ya se confirmó la entrega del anexo 9.1.",
                )?.let { return it }
                if (p.fechaCreacionAnexo91 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(p.copy(fechaCreacionAnexo91 = null, fecha_actualizacion = ahora)),
                )
                null
            }
            "fecha_confirmacion_entrega_anexo_9_1" -> {
                bloqueado(
                    p.fechaSolicitudAnexo92 != null,
                    "No se puede revertir: ya se solicitó el anexo 9.2.",
                )?.let { return it }
                if (p.fechaConfirmacionEntregaAnexo91 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionEntregaAnexo91 = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_solicitud_anexo_9_2" -> {
                bloqueado(
                    p.fechaAceptacionServiciosEscolaresAnexo92 != null,
                    "No se puede revertir: Servicios escolares ya generó el anexo 9.2.",
                )?.let { return it }
                bloqueado(
                    p.fechaCreacionAnexo92 != null,
                    "No se puede revertir: ya se generó el anexo 9.2.",
                )?.let { return it }
                if (p.fechaSolicitudAnexo92 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(p.copy(fechaSolicitudAnexo92 = null, fecha_actualizacion = ahora)),
                )
                null
            }
            "fecha_confirmacion_recibido_anexo_9_2" -> {
                bloqueado(
                    p.fechaSolicitudSinodales != null,
                    "No se puede revertir: ya se solicitó la asignación de sinodales.",
                )?.let { return it }
                if (p.fechaConfirmacionRecibidoAnexo92 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionRecibidoAnexo92 = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_solicitud_sinodales" -> {
                bloqueado(
                    p.fechaAsignacionSinodales != null,
                    "No se puede revertir: el departamento académico ya asignó sinodales.",
                )?.let { return it }
                if (p.fechaSolicitudSinodales == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(p.copy(fechaSolicitudSinodales = null, fecha_actualizacion = ahora)),
                )
                null
            }
            "fecha_confirmacion_sinodales_recibidos" -> {
                bloqueado(
                    p.fechaAgendaActo93 != null,
                    "No se puede revertir: ya se agendó el acto protocolario.",
                )?.let { return it }
                if (p.fechaConfirmacionSinodalesRecibidos == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionSinodalesRecibidos = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_agenda_acto_9_3" -> {
                bloqueado(
                    p.fechaCreacionAnexo93 != null,
                    "No se puede revertir: ya se generó el anexo 9.3.",
                )?.let { return it }
                if (p.fechaAgendaActo93 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(
                            fechaAgendaActo93 = null,
                            fechaReagendaActo93 = null,
                            fecha_actualizacion = ahora,
                        ),
                    ),
                )
                null
            }
            "fecha_creacion_anexo_9_3" -> {
                bloqueado(
                    p.fechaConfirmacionEntregaAnexo93 != null,
                    "No se puede revertir: ya se confirmó la entrega del anexo 9.3.",
                )?.let { return it }
                bloqueado(
                    p.fechaSolicitudDocumentacionEscaneada != null,
                    "No se puede revertir: ya se solicitó la documentación escaneada.",
                )?.let { return it }
                if (p.fechaCreacionAnexo93 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(p.copy(fechaCreacionAnexo93 = null, fecha_actualizacion = ahora)),
                )
                null
            }
            "fecha_confirmacion_entrega_anexo_9_3" -> {
                bloqueado(
                    p.fechaSolicitudDocumentacionEscaneada != null,
                    "No se puede revertir: ya se solicitó la documentación escaneada.",
                )?.let { return it }
                if (p.fechaConfirmacionEntregaAnexo93 == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaConfirmacionEntregaAnexo93 = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_solicitud_documentacion_escaneada" -> {
                bloqueado(
                    p.fechaEnvioDocumentacionEscaneadaEgresado != null,
                    "No se puede revertir: el egresado ya envió la documentación.",
                )?.let { return it }
                if (p.fechaSolicitudDocumentacionEscaneada == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(fechaSolicitudDocumentacionEscaneada = null, fecha_actualizacion = ahora),
                    ),
                )
                null
            }
            "fecha_confirmacion_documentacion_escaneada_recibida" -> {
                bloqueado(
                    p.fechaTitulacion != null,
                    "No se puede revertir: el proceso ya está concluido.",
                )?.let { return it }
                if (p.fechaConfirmacionDocumentacionEscaneadaRecibida == null) return "El paso no está completado."
                egresadoRepository.save(
                    e.actualizarProcesoActivo(
                        p.copy(
                            fechaConfirmacionDocumentacionEscaneadaRecibida = null,
                            fecha_actualizacion = ahora,
                        ),
                    ),
                )
                null
            }
            else -> "Paso no revertible desde seguimiento."
        }
    }

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
            content_type = "application/pdf",
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

    fun crearAnexo91(id: String, omitirPrerequisito: Boolean = false): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        val prerequisito91 = if (esCeneval(e)) {
            p.fechaConfirmacionEntregaEgresadoDepto != null
        } else {
            p.fechaConfirmacionRecibidosAnexoXxxiXxxii != null
        }
        if (!omitirPrerequisito && !prerequisito91) return null
        val serviciosEscolares = configInstitucionalService.obtenerServiciosEscolares()
        val ahora = Instant.now()
        val certId = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores = construirValoresPlantillaHtml(e, listOf(
            "MODALIDAD" to p.datos_proyecto.modalidad,
            "FECHA_CARTA" to fechaCartaEspanolaCapitalizada(ahora),
            // Tipo oración; solo el paréntesis va en negrita en la plantilla HTML.
            "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegralTipoOracion(p.datos_proyecto.modalidad),
            // Título profesional (Ingeniero informático…), no el nombre de la carrera.
            "TITULO_PROFESIONAL" to tituloProfesionalDesdeCarrera(
                e.datos_personales.carrera,
                generoDocumentoEgresado(e),
            ),
            "DESTINATARIO_SERVICIOS_ESCOLARES" to serviciosEscolares.jefeNombre.uppercase(Locale.forLanguageTag("es-MX")),
            "CARGO_SERVICIOS_ESCOLARES" to serviciosEscolares.jefeCargo.uppercase(Locale.forLanguageTag("es-MX")),
            "QR_CODE" to qrDataUri,
        ) + extrasCodigoRevisionAnexo("9.1"))
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

    fun crearAnexo92(id: String, omitirPrerequisito: Boolean = false): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        if (!omitirPrerequisito && p.fechaConfirmacionEntregaAnexo91 == null) return null
        if (!omitirPrerequisito && p.fechaCreacionAnexo92 == null && p.fechaSolicitudAnexo92 == null) return null
        if (p.fechaAceptacionServiciosEscolaresAnexo92 != null) {
            p.anexo92Pdf?.let { return it }
        }
        val serviciosEscolares = configInstitucionalService.obtenerServiciosEscolares()
        val ahora = Instant.now()
        val certId = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores = construirValoresPlantillaHtml(e, listOf(
            "FECHA_CARTA" to fechaCartaEspanolaCapitalizada(ahora),
            "PREFIJO_EGRESADO" to GeneroPorNombre.etiquetaCiudadano(generoDocumentoEgresado(e)),
            "FIRMA_SE_NOMBRE" to serviciosEscolares.jefeNombre.uppercase(Locale.forLanguageTag("es-MX")),
            "FIRMA_SE_CARGO" to serviciosEscolares.jefeCargo.uppercase(Locale.forLanguageTag("es-MX")),
            "QR_CODE" to qrDataUri,
        ) + extrasCodigoRevisionAnexo("9.2"))
        val pdf = htmlAnexoPdfService.generarDesdeClasspath("templates/html/anexo-9-2.html", valores)
        if (pdf == null) {
            log.warn("crearAnexo92: no se generó PDF para egresado id={}", id)
            return null
        }
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaCreacionAnexo92 = ahora,
                    anexo92Pdf = pdf,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return pdf
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

    fun asignarSinodales(
        id: String,
        presidente: String,
        secretario: String,
        vocal: String,
        vocalSuplente: String,
        numeroOficio: String,
    ): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaSolicitudSinodales == null) return false
        if (p.fechaConfirmacionEntregaAnexo93 != null) return false
        val ahora = Instant.now()
        val presidenteLimpio = presidente.trim()
        val secretarioLimpio = secretario.trim()
        val vocalLimpio = vocal.trim()
        val suplenteLimpio = vocalSuplente.trim()
        val docentes = correosDocentesPorNombre()
        fun correoDe(nombre: String): String =
            CorreosSinodales.resolver(listOf(nombre), docentes).firstOrNull()?.second.orEmpty()
        val tribunal = SinodalesTribunal(
            presidente = presidenteLimpio,
            secretario = secretarioLimpio,
            vocal = vocalLimpio,
            vocal_suplente = suplenteLimpio,
            numero_oficio = numeroOficio.trim(),
            correo_presidente = correoDe(presidenteLimpio),
            correo_secretario = correoDe(secretarioLimpio),
            correo_vocal = correoDe(vocalLimpio),
            correo_vocal_suplente = correoDe(suplenteLimpio),
        )
        val sinCorreo = listOf(
            presidenteLimpio to tribunal.correo_presidente,
            secretarioLimpio to tribunal.correo_secretario,
            vocalLimpio to tribunal.correo_vocal,
            suplenteLimpio to tribunal.correo_vocal_suplente,
        ).filter { (nombre, mail) -> nombre.isNotBlank() && mail.isBlank() }
        if (sinCorreo.isNotEmpty()) {
            log.warn(
                "Sinodales sin correo en el catálogo de docentes (egresado {}): {}",
                e.numero_control,
                sinCorreo.map { it.first },
            )
        }
        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            sinodalesTribunal = tribunal,
            fechaAsignacionSinodales = ahora,
            fecha_actualizacion = ahora,
        )))
        return true
    }

    fun crearOficioAsignacionSinodales(id: String): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        val tribunal = p.sinodalesTribunal ?: return null
        if (p.fechaAsignacionSinodales == null) return null
        if (tribunal.presidente.isBlank() || tribunal.secretario.isBlank() ||
            tribunal.vocal.isBlank() || tribunal.vocal_suplente.isBlank()
        ) {
            return null
        }

        val depto = resolverDepartamentoPorCarrera(e.datos_personales.carrera)
        val nombreDepto = depto?.second ?: "Departamento académico"
        val siglas = siglasOficioDepartamento(depto?.first, nombreDepto)
        val instant = p.fechaAsignacionSinodales!!
        val fechaGeneracion = Instant.now()
        val institucional = resolverDatosInstitucionalesDocumento(e.datos_personales.carrera)
        log.info(
            "Oficio sinodales egresadoId={} carrera='{}' deptoSlug={} titular='{}' jefeDivision='{}'",
            id,
            e.datos_personales.carrera,
            depto?.first,
            institucional.titularDeptoNombre,
            institucional.jefeDivisionNombre,
        )
        val lugar = env.getProperty("sit.oficio-sinodales.lugar", "Nazareno, Xoxocotlán, Oaxaca").trim()
        val instituto = env.getProperty("sit.oficio-sinodales.instituto", "Instituto Tecnológico del Valle de Oaxaca").trim()
        val expediente = p.cert_uuid?.trim()?.uppercase()?.ifBlank { null }
            ?: e.id?.toHexString()?.uppercase()
            ?: e.numero_control.trim().uppercase(Locale.ROOT)
        val generoExtras = extrasGeneroDocumentos(
            e,
            tribunal,
            nombreDepto,
            institucional.titularDeptoNombre,
            institucional.titularDeptoCargo,
            institucional.jefeDivisionNombre,
            institucional.jefeDivisionCargo,
        )

        val valores = construirValoresPlantillaHtml(
            e,
            generoExtras + listOf(
                "NOMBRE" to nombreCompleto(e).uppercase(Locale.forLanguageTag("es-MX")),
                "CARRERA" to e.datos_personales.carrera.trim().uppercase(Locale.forLanguageTag("es-MX")),
                "PROYECTO" to textoProyectoDocumentosTitulacion(e, p)
                    .uppercase(Locale.forLanguageTag("es-MX")),
                "FRASE_TEMA_OFICIO" to fraseTemaOficioSinodales(e, p),
                "EXPEDIENTE" to expediente,
                "OFICIO_NUMERO" to tribunal.numero_oficio.trim().ifBlank {
                    numeroOficioSinodales(e, siglas, instant)
                },
                "LUGAR" to lugar,
                "FECHA_OFICIO" to fechaOficioSlash(fechaGeneracion),
                "ASUNTO" to "Vocal y vocal suplente",
                "JEFE_DIVISION_NOMBRE" to institucional.jefeDivisionNombre,
                "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(p.datos_proyecto.modalidad),
                "PRESIDENTE" to tribunal.presidente.trim().uppercase(Locale.forLanguageTag("es-MX")),
                "SECRETARIO" to tribunal.secretario.trim().uppercase(Locale.forLanguageTag("es-MX")),
                "VOCAL" to tribunal.vocal.trim().uppercase(Locale.forLanguageTag("es-MX")),
                "VOCAL_SUPLENTE" to tribunal.vocal_suplente.trim().uppercase(Locale.forLanguageTag("es-MX")),
                "JEFA_DEPARTAMENTO_NOMBRE" to institucional.titularDeptoNombre.uppercase(Locale.forLanguageTag("es-MX")),
                "NOMBRE_DEPARTAMENTO" to nombreDepto,
                "INICIALES_FIRMA" to institucional.inicialesFirma,
                "NOMBRE_INSTITUTO" to instituto,
            ),
        )
        return htmlAnexoPdfService.generarDesdeClasspath(
            "templates/html/oficio-sinodales/oficio-asignacion-sinodales.html",
            valores,
        )
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

    /** Formato Word de preguntas a sinodales: solo modalidad Tesis, o Monografía en curso de titulación. */
    private fun requiereFormatoPreguntasSinodales(p: ProcesoTitulacion): Boolean {
        val modalidad = p.datos_proyecto.modalidad.trim().lowercase(Locale.ROOT)
        val cursoTit = p.datos_proyecto.curso_titulacion.trim().lowercase(Locale.ROOT) == "si"
        // Tesis (integral) y TESIS PROFESIONAL (titulación tradicional)
        if (modalidad == "tesis" || modalidad.contains("tesis")) return true
        if (modalidad.contains("monograf") && cursoTit) return true
        return false
    }

    /** PDF del anexo, sinodales con correo, avisos tomados en cuenta y cuántos siguen en reintento. */
    fun crearAnexo93(id: String): Anexo93Generado? {
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
        val institucional = resolverDatosInstitucionalesDocumento(e.datos_personales.carrera)
        val depto = resolverDepartamentoPorCarrera(e.datos_personales.carrera)
        val nombreDepto = depto?.second ?: "Departamento académico"
        val tribunal = p.sinodalesTribunal
        val generoExtras = extrasGeneroDocumentos(
            e,
            tribunal,
            nombreDepto,
            institucional.titularDeptoNombre,
            institucional.titularDeptoCargo,
            institucional.jefeDivisionNombre,
            institucional.jefeDivisionCargo,
        )
        val certId = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() } ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId")
        val valores = construirValoresPlantillaHtml(e, generoExtras + listOf(
            "PROYECTO" to textoProyectoDocumentosTitulacion(e, p),
            "ACTO_93" to actoLegible,
            "FECHA_CARTA" to fechaCartaEspanolaAnexo93(Instant.now()),
            "TEXTO_OPCION_TI" to textoOpcionTitulacionIntegral(p.datos_proyecto.modalidad),
            "ACTO_DIA" to diaActo, "ACTO_MES" to mesActo, "ACTO_ANIO" to anioActo, "ACTO_HORA" to horaActo,
            "PRESIDENTE" to (tribunal?.presidente ?: ""),
            "SECRETARIO" to (tribunal?.secretario ?: ""),
            "VOCAL" to (tribunal?.vocal ?: ""),
            "VOCAL_SUPLENTE" to (tribunal?.vocal_suplente ?: ""),
            "JEFE_DIVISION_NOMBRE" to institucional.jefeDivisionNombre.uppercase(Locale.forLanguageTag("es-MX")),
            "JEFE_DEPTO_INICIALES" to configInstitucionalService.obtenerJefeDivisionIniciales(),
            "QR_CODE" to qrDataUri,
        ) + extrasCodigoRevisionAnexo("9.3"))
        val bytes = htmlAnexoPdfService.generarDesdeClasspath(
            "templates/html/anexo-9-3.html",
            valores,
            valoresHtml = mapOf("FRASE_PROYECTO_93" to fraseProyectoAnexo93Html(e, p)),
        ) ?: return null

        var emailsEnviados = 0
        var emailsEnProceso = 0
        var sinodalesConCorreo = 0
        val segmento = catalogoRepository.segmentoDeCarrera(e.datos_personales.carrera)
        val actoReciente = acto.isAfter(Instant.now().minus(60, ChronoUnit.DAYS))
        if (tribunal != null && (esNuevaGeneracion || actoReciente)) {
            val todos = sinodalesResueltos(tribunal)
            sinodalesConCorreo = todos.size
            if (todos.isEmpty()) {
                log.warn("Anexo 9.3: ningún sinodal tiene correo registrado en la colección de docentes (egresado {})", e.numero_control)
            } else {
                val enviados = p.correosSinodalesAnexo93.map { it.lowercase() }.toSet()
                val rechazados = p.correosSinodalesAnexo93Rechazados.map { it.lowercase() }.toSet()
                val idHex = e.id?.toHexString().orEmpty()
                val yaEnviados = todos.count { it.correo.lowercase() in enviados }
                val enCola = todos.filter { s ->
                    val c = s.correo.lowercase()
                    c !in enviados && c !in rechazados && emailService.citatorioYaEnCola(idHex, s.correo)
                }
                val faltantes = todos.filter { s ->
                    val c = s.correo.lowercase()
                    c !in enviados && c !in rechazados && enCola.none { it.correo.equals(s.correo, true) }
                }
                val envio = if (faltantes.isEmpty()) {
                    ResultadoCorreosSinodales()
                } else {
                    val nombreEgresado = listOf(
                        e.datos_personales.nombre,
                        e.datos_personales.apellido_paterno,
                        e.datos_personales.apellido_materno,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    emailService.enviarAnexo93Sinodales(
                        destinatarios = faltantes.map { SinodalCorreoDestino(it.correo, it.rol) },
                        nombreEgresado = nombreEgresado,
                        numeroControl = e.numero_control,
                        fechaActo = actoLegible,
                        pdfBytes = bytes,
                        fileName = "Anexo-9.3-${e.numero_control}.pdf",
                        incluirFormatoPreguntas = requiereFormatoPreguntasSinodales(p),
                        certId = certId,
                        segmento = segmento,
                        egresadoId = idHex,
                        referenciaActo = acto.toEpochMilli().toString(),
                    )
                }
                emailsEnviados = yaEnviados + envio.aceptados + envio.enProceso + enCola.size
                emailsEnProceso = envio.enProceso + enCola.size
                if (envio.correosAceptados.isNotEmpty() || envio.correosRechazados.isNotEmpty()) {
                    registrarCorreosCitatorio(idHex, envio.correosAceptados, envio.correosRechazados, acto.toEpochMilli().toString())
                }
            }
        }

        return Anexo93Generado(bytes, sinodalesConCorreo, emailsEnviados, emailsEnProceso)
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

    /**
     * Confirmar que llegó la documentación escaneada es, en la práctica, el
     * cierre real del proceso — de ahí que también marque `"titulado"` aquí.
     * (Antes había un mecanismo aparte, `subirDocumentoFinal`, para este
     * último paso — se retiró: no tenía ningún botón en el frontend que lo
     * disparara, así que ningún egresado podía llegar nunca a "titulado".)
     */
    fun confirmarDocumentacionEscaneadaRecibida(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (p.fechaEnvioDocumentacionEscaneadaEgresado == null) return false
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return false
        val ahora = Instant.now()
        val guardado = egresadoRepository.save(
            e.actualizarProcesoActivo(p.copy(
                fechaConfirmacionDocumentacionEscaneadaRecibida = ahora,
                fechaTitulacion = ahora,
                estado_general = "titulado",
                fecha_actualizacion = ahora,
                historial_estados = p.historial_estados + HistorialEstado(
                    estado = "titulado", fecha = ahora, observacion = "Documentación escaneada confirmada por el departamento académico.",
                ),
            )),
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

    fun subirDocumentacionEscaneadaEgresado(
        egresadoId: String,
        archivos: List<MultipartFile>,
    ): String? {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return "Identificador inválido." }
        val e = egresadoRepository.findById(oid).orElse(null) ?: return "Registro no encontrado."
        val p = e.procesoActivoOrNull() ?: return "No hay proceso activo."
        if (p.fechaSolicitudDocumentacionEscaneada == null) return "Aún no se ha solicitado la documentación escaneada."
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return "Ya se confirmó la recepción; no se pueden enviar más archivos."
        val validos = archivos.filter { !it.isEmpty }
        if (validos.isEmpty()) return "Debes adjuntar al menos un PDF."
        for (a in validos) {
            if (!esPdfValido(a)) return "Solo se permiten archivos PDF (${a.originalFilename ?: "archivo"})."
        }
        eliminarEntregaEscaneadaAnterior(oid)
        val metas = validos.map { a ->
            ArchivoEscaneadoMeta(
                gridfsId = subirArchivo(a),
                nombreOriginal = a.originalFilename ?: "documento.pdf",
                contentType = "application/pdf",
                tamanioBytes = a.size,
            )
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
        return p.fechaConfirmacionEntregaAnexo93 != null
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

    private fun esPdfValido(archivo: MultipartFile): Boolean =
        try {
            ArchivoPdfValidator.validar(archivo)
            true
        } catch (_: IllegalArgumentException) {
            false
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

        var fechaTitulacionVal = p.fechaTitulacion
        var estado = p.estado_general
        var historial = p.historial_estados
        if (reagenda && p.estado_general == "titulado") {
            fechaTitulacionVal = null; estado = "registrado"
            historial = historial + HistorialEstado(estado = "registrado", fecha = ahora, observacion = "Reagenda acto protocolario 9.3: se reinician pasos posteriores al agendamiento.")
        }

        egresadoRepository.save(e.actualizarProcesoActivo(p.copy(
            fechaAgendaActo93 = inicio,
            fechaReagendaActo93 = if (reagenda) ahora else null,
            fechaCreacionAnexo93 = if (reagenda) null else p.fechaCreacionAnexo93,
            correosSinodalesAnexo93 = if (reagenda) emptyList() else p.correosSinodalesAnexo93,
            correosSinodalesAnexo93Rechazados = if (reagenda) emptyList() else p.correosSinodalesAnexo93Rechazados,
            fechaConfirmacionEntregaAnexo93 = if (reagenda) null else p.fechaConfirmacionEntregaAnexo93,
            fechaSolicitudDocumentacionEscaneada = if (reagenda) null else p.fechaSolicitudDocumentacionEscaneada,
            fechaEnvioDocumentacionEscaneadaEgresado = if (reagenda) null else p.fechaEnvioDocumentacionEscaneadaEgresado,
            fechaConfirmacionDocumentacionEscaneadaRecibida = if (reagenda) null else p.fechaConfirmacionDocumentacionEscaneadaRecibida,
            fechaSolicitudReenvioDocumentacionEscaneada = if (reagenda) null else p.fechaSolicitudReenvioDocumentacionEscaneada,
            observacionesReenvioDocumentacionEscaneada = if (reagenda) null else p.observacionesReenvioDocumentacionEscaneada,
            fechaTitulacion = if (reagenda) fechaTitulacionVal else p.fechaTitulacion,
            estado_general = if (reagenda) estado else p.estado_general,
            historial_estados = if (reagenda) historial else p.historial_estados,
            fecha_actualizacion = ahora,
        )))

        // El correo se envía en segundo plano para no bloquear la respuesta HTTP mientras el SMTP responde.
        val correo = e.datos_personales.correo_electronico?.trim().orEmpty()
        val fechaHoraTexto = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
            .withZone(zonaActo93)
            .format(inicio)
        val nc = e.numero_control
        val nombreEgresado = nombreCompleto(e)
        val tribunal = p.sinodalesTribunal
        val segmento = catalogoRepository.segmentoDeCarrera(e.datos_personales.carrera)
        val sinodales = try {
            correosSinodalesActo(tribunal)
        } catch (ex: Exception) {
            log.warn("Acto 9.3 agendado para {} — no se pudo armar la lista de sinodales: {}", nc, ex.message)
            emptyList()
        }
        if (sinodales.isEmpty()) {
            log.warn(
                "Acto 9.3 agendado para {} sin correos de sinodales en el catálogo de docentes (tribunal={})",
                nc,
                listOfNotNull(
                    tribunal?.presidente,
                    tribunal?.secretario,
                    tribunal?.vocal,
                    tribunal?.vocal_suplente,
                ).filter { it.isNotBlank() },
            )
        } else {
            log.info("Acto 9.3 de {}: aviso a {} sinodal(es)", nc, sinodales.size)
        }
        if (correo.isNotBlank()) {
            emailService.ejecutarEnvio {
                try {
                    val enviado = emailService.enviarAvisoActoProtocolarioAgendado(
                        correoDestino = correo,
                        nombreEgresado = nombreEgresado,
                        numeroControl = nc,
                        fechaHoraActoTexto = fechaHoraTexto,
                        segmento = segmento,
                    )
                    if (!enviado) log.warn("Acto 9.3 agendado para {} pero no se envió correo al egresado ({})", nc, correo)
                } catch (ex: Exception) {
                    log.warn("Acto 9.3 agendado para {} — error enviando correo al egresado: {}", nc, ex.message)
                }
            }
        } else {
            log.warn("Acto 9.3 agendado para {} sin correo del egresado; aviso por correo omitido", nc)
        }
        for ((nombreSinodal, correoSinodal) in sinodales) {
            emailService.ejecutarEnvio {
                try {
                    val enviado = emailService.enviarAvisoActoProtocolarioSinodal(
                        correoDestino = correoSinodal,
                        nombreSinodal = nombreSinodal,
                        nombreEgresado = nombreEgresado,
                        numeroControl = nc,
                        fechaHoraActoTexto = fechaHoraTexto,
                        segmento = segmento,
                    )
                    if (!enviado) {
                        log.warn("Acto 9.3 agendado para {} pero no se envió correo al sinodal {}", nc, correoSinodal)
                    }
                } catch (ex: Exception) {
                    log.warn("Acto 9.3 agendado para {} — error enviando correo al sinodal {}: {}", nc, correoSinodal, ex.message)
                }
            }
        }

        return true
    }

    /** Nombre tal como está en el catálogo → correo. Un docente sin correo no tumba la lista. */
    private fun correosDocentesPorNombre(): List<Pair<String, String>> {
        val docentes = try {
            docenteRepository.findAll().sortedByDescending { it.activo }
        } catch (ex: Exception) {
            log.warn("No se pudo leer el catálogo de docentes para avisar a los sinodales: {}", ex.message)
            emptyList()
        }
        val salida = ArrayList<Pair<String, String>>()
        for (docente in docentes) {
            val correo = runCatching { docente.correo.trim() }.getOrDefault("")
            val nombre = runCatching { docente.nombreCompleto }.getOrDefault("")
            if (correo.isBlank() || nombre.isBlank()) continue
            salida += nombre to correo
        }
        return salida
    }

    private data class SinodalResuelto(val nombre: String, val correo: String, val rol: RolSinodalCorreo)

    /**
     * Usa el correo guardado al asignar el tribunal. Si ese proceso es anterior y no lo tiene,
     * lo busca en el catálogo (también si el docente ya no está marcado como activo).
     */
    private fun sinodalesResueltos(tribunal: SinodalesTribunal): List<SinodalResuelto> {
        val docentes = correosDocentesPorNombre()
        val vistos = mutableSetOf<String>()
        val salida = ArrayList<SinodalResuelto>()
        fun agregar(nombre: String, guardado: String, rol: RolSinodalCorreo) {
            val visible = nombre.trim()
            if (visible.isBlank()) return
            val correo = guardado.trim().ifBlank {
                CorreosSinodales.resolver(listOf(visible), docentes).firstOrNull()?.second.orEmpty()
            }.trim()
            if (correo.isBlank()) return
            if (!vistos.add(correo.lowercase())) return
            salida += SinodalResuelto(visible, correo, rol)
        }
        agregar(tribunal.presidente, tribunal.correo_presidente, RolSinodalCorreo.PRESIDENTE)
        agregar(tribunal.secretario, tribunal.correo_secretario, RolSinodalCorreo.SECRETARIO)
        agregar(tribunal.vocal, tribunal.correo_vocal, RolSinodalCorreo.VOCAL)
        agregar(tribunal.vocal_suplente, tribunal.correo_vocal_suplente, RolSinodalCorreo.VOCAL_SUPLENTE)
        return salida
    }

    /** Correo del catálogo de docentes, por nombre del sinodal. Una sola vez por dirección. */
    private fun correosSinodalesActo(tribunal: SinodalesTribunal?): List<Pair<String, String>> {
        if (tribunal == null) return emptyList()
        return sinodalesResueltos(tribunal).map { it.nombre to it.correo }
    }

    @EventListener
    fun registrarResultadoCorreoSinodal(evento: CorreoMarcadoEvent) {
        if (evento.marca != MARCA_CITATORIO_SINODAL) return
        val aceptados = if (evento.aceptado) listOf(evento.correo) else emptyList()
        val rechazados = if (evento.aceptado) emptyList() else listOf(evento.correo)
        registrarCorreosCitatorio(evento.egresadoId, aceptados, rechazados, evento.referencia)
    }

    private fun registrarCorreosCitatorio(
        egresadoId: String,
        aceptados: List<String>,
        rechazados: List<String>,
        referencia: String?,
    ) {
        if (egresadoId.isBlank() || (aceptados.isEmpty() && rechazados.isEmpty())) return
        synchronized(registroCitatorio) {
        try {
            val e = cargarEgresadoPorId(egresadoId) ?: return
            val p = e.procesoActivoOrNull() ?: return
            val agenda = p.fechaAgendaActo93?.toEpochMilli()?.toString()
            if (referencia != null && agenda != null && referencia != agenda) return
            val aceptadosNorm = aceptados.map { it.trim().lowercase() }.filter { it.isNotBlank() }
            val rechazadosNorm = rechazados.map { it.trim().lowercase() }.filter { it.isNotBlank() }
            val enviados = (p.correosSinodalesAnexo93.map { it.lowercase() } + aceptadosNorm)
                .distinct()
            val rechazadosFinal = (p.correosSinodalesAnexo93Rechazados.map { it.lowercase() } + rechazadosNorm)
                .filter { it !in enviados }
                .distinct()
            if (enviados == p.correosSinodalesAnexo93.map { it.lowercase() } &&
                rechazadosFinal == p.correosSinodalesAnexo93Rechazados.map { it.lowercase() }
            ) {
                return
            }
            egresadoRepository.save(
                e.actualizarProcesoActivo(
                    p.copy(
                        correosSinodalesAnexo93 = enviados,
                        correosSinodalesAnexo93Rechazados = rechazadosFinal,
                        fecha_actualizacion = Instant.now(),
                    ),
                ),
            )
        } catch (ex: Exception) {
            log.warn("No se pudo registrar el citatorio del egresado {}: {}", egresadoId, ex.message)
        }
        }
    }

    /**
     * Anexos ya generados cuyo citatorio no llegó. No manda otra copia si ya fue aceptado,
     * si la dirección fue rechazada o si el correo sigue en la cola.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    fun reintentarCitatoriosSinodalesPendientes() {
        val zona = zonaActo93
        val inicio = LocalDate.now(zona).minusDays(60).atStartOfDay(zona).toInstant()
        val fin = LocalDate.now(zona).plusYears(1).atStartOfDay(zona).toInstant()
        val lista = try {
            egresadoRepository.findActo93AgendadoEnRango(inicio, fin)
        } catch (ex: Exception) {
            log.warn("No se pudieron buscar citatorios de sinodales pendientes: {}", ex.message)
            return
        }
        var generados = 0
        for (egresado in lista) {
            if (generados >= 3) break
            val proceso = egresado.procesoActivoOrNull() ?: continue
            val id = egresado.id?.toHexString() ?: continue
            if (!citatorioSinodalPendiente(proceso, id)) continue
            if (!citatoriosEnCurso.add(id)) continue
            try {
                crearAnexo93(id)
                generados++
            } catch (ex: Exception) {
                log.warn("Citatorio a sinodales no reintentado para {}: {}", egresado.numero_control, ex.message)
            } finally {
                citatoriosEnCurso.remove(id)
            }
        }
    }

    private fun citatorioSinodalPendiente(p: ProcesoTitulacion, egresadoId: String): Boolean {
        val tribunal = p.sinodalesTribunal ?: return false
        val acto = p.fechaAgendaActo93 ?: return false
        if (p.fechaCreacionAnexo93 == null) return false
        if (acto.isBefore(Instant.now().minus(60, ChronoUnit.DAYS))) return false
        val todos = sinodalesResueltos(tribunal)
        if (todos.isEmpty()) return false
        val enviados = p.correosSinodalesAnexo93.map { it.lowercase() }.toSet()
        val rechazados = p.correosSinodalesAnexo93Rechazados.map { it.lowercase() }.toSet()
        return todos.any { s ->
            val c = s.correo.lowercase()
            c !in enviados && c !in rechazados && !emailService.citatorioYaEnCola(egresadoId, s.correo)
        }
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
        val plazo = clasificarPlazo(e, pr)
        val cuadroDesarrolloDto = pr?.let {
            val plazos = plazosModalidad(it.datos_proyecto.modalidad)
            PlazoTitulacionCalculator.cuadroDesarrollo(
                it,
                LocalDate.now(zonaMexico),
                mesesProyecto = plazos.mesesProyecto,
                mesesTitulacion = plazos.mesesTitulacion,
            )
        }?.let {
            PlazoDesarrolloDto(
                estado = it.estado.valorApi,
                etiqueta_estado = it.etiquetaEstado,
                clase_semaforo = it.claseSemaforo,
                fecha_inicio_texto = it.fechaInicioTexto,
                meses_totales = it.mesesTotales,
                fase_post_recepcion = it.fasePostRecepcion,
                fecha_limite_texto = it.fechaLimiteTexto,
                texto_meses_restantes = it.textoMesesRestantes,
                texto_tiempo_restante_corto = it.textoTiempoRestanteCorto,
            )
        }
        return EgresadoDetailDto(
            id = e.id?.toString() ?: "",
            numero_control = e.numero_control,
            datos_personales = DatosPersonalesDto(
                nombre = p.nombre, apellido_paterno = p.apellido_paterno,
                apellido_materno = p.apellido_materno ?: "", carrera = p.carrera,
                nivel = p.nivel, direccion = p.direccion, telefono = p.telefono,
                correo_electronico = p.correo_electronico,
                genero = p.genero,
            ),
            datos_proyecto = pr?.let {
                DatosProyectoDto(
                    nombre_proyecto = it.datos_proyecto.nombre_proyecto,
                    modalidad = it.datos_proyecto.modalidad,
                    tipo_titulacion = it.datos_proyecto.tipo_titulacion,
                    curso_titulacion = it.datos_proyecto.curso_titulacion,
                    asesor_interno = it.datos_proyecto.asesor_interno,
                    asesor_externo = it.datos_proyecto.asesor_externo,
                    director = it.datos_proyecto.director,
                    asesor_1 = it.datos_proyecto.asesor_1,
                    asesor_2 = it.datos_proyecto.asesor_2,
                )
            } ?: DatosProyectoDto("", "", "titulacion_integral", "no", null, null, null, null, null),
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
            fecha_solicitud_documentacion_escaneada = pr?.fechaSolicitudDocumentacionEscaneada?.let { formatter.format(it) },
            fecha_envio_documentacion_escaneada_egresado = pr?.fechaEnvioDocumentacionEscaneadaEgresado?.let { formatter.format(it) },
            fecha_confirmacion_documentacion_escaneada_recibida = pr?.fechaConfirmacionDocumentacionEscaneadaRecibida?.let { formatter.format(it) },
            fecha_solicitud_reenvio_documentacion_escaneada = pr?.fechaSolicitudReenvioDocumentacionEscaneada?.let { formatter.format(it) },
            observaciones_reenvio_documentacion_escaneada = pr?.observacionesReenvioDocumentacionEscaneada,
            avances_flujo_propio = pr?.avancesFlujoPropio.orEmpty().map {
                com.sit_titulacion.sit.web.api.dto.AvanceFlujoPropioDto(it.indice, formatter.format(it.fecha))
            },
            estado_plazo = plazo.estado,
            dias_restantes_plazo = plazo.dias,
            fecha_limite_plazo = plazo.fechaLimite?.let { formatter.format(it) },
            linea_proyecto = plazo.lineaProyecto,
            linea_titulacion = plazo.lineaTitulacion,
            plazo_desarrollo = cuadroDesarrolloDto,
        )
    }

    private fun esResidenciaProfesional(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
    }

    /** Tesis, tesina, proyecto de investigación y monografía: el primer cuadro del anexo XXXIII es el director. */
    private fun esModalidadConDirector(modalidad: String): Boolean {
        val m = NormalizacionTexto.normalizarClave(modalidad)
        return m.contains("tesis") || m.contains("tesina") || m.contains("monograf") || m.contains("investigacion")
    }

    private fun esCeneval(e: Egresado): Boolean {
        val nombre = e.procesoActivoOrNull()?.datos_proyecto?.modalidad?.trim() ?: return false
        if (nombre.contains("ceneval", ignoreCase = true)) return true
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esCeneval == true || cat?.origenFlujo == "ceneval"
    }

    /**
     * Clasificación de plazo para listados y detalle: estado a 4 valores
     * (en_tiempo/rezagado/vencido/concluido — misma precedencia que antes aplicaba
     * `mapear()` en `coordinador-inicio.component.ts`), más el texto narrativo
     * (`lineaProyecto`/`lineaTitulacion`) que antes generaban `seguimiento*.component.ts`.
     * `PlazoTitulacionCalculator.vista()` SIEMPRE se llama (incluso si ya está
     * concluido o es CENEVAL) porque cada sub-flujo ya sabe describir su propio
     * "concluido" con matices distintos (p. ej. "Residencia: proceso concluido..."
     * vs. legacy con proyecto y titulación concluidos por separado) — perder eso
     * con un atajo tipo `return "concluido"` sin más habría sido menos fiel que el
     * TS original. Solo el campo `estado` de 4 valores se sobreescribe encima,
     * para las vistas tipo dashboard que necesitan ese bucket extra.
     */
    /** internal (no private): la reutiliza `ReporteService` para no duplicar la clasificación. */
    internal data class ClasificacionPlazo(
        val estado: String,
        val dias: Long?,
        val fechaLimite: Instant?,
        val lineaProyecto: String,
        val lineaTitulacion: String,
    )

    internal fun clasificarPlazo(e: Egresado, pr: ProcesoTitulacion?): ClasificacionPlazo {
        if (pr == null) return ClasificacionPlazo("en_tiempo", null, null, "", "")
        if (esCeneval(e)) return ClasificacionPlazo("en_tiempo", null, null, "", "")
        val zone = zonaMexico
        val plazos = plazosModalidad(pr.datos_proyecto.modalidad)
        val vista = PlazoTitulacionCalculator.vista(
            pr,
            esResidencia = esResidenciaProfesional(e),
            hoy = LocalDate.now(zone),
            zone = zone,
            mesesProyecto = plazos.mesesProyecto,
            mesesTitulacion = plazos.mesesTitulacion,
            mesesResidencia = plazos.mesesResidencia,
        )
        val estado = if (pr.fechaConfirmacionDocumentacionEscaneadaRecibida != null) "concluido" else vista.estadoGlobal.valorApi
        val fechaLimiteInstant = vista.fechaLimiteMasCercana?.atStartOfDay(zone)?.toInstant()
        return ClasificacionPlazo(estado, vista.diasHastaLimiteMasCercano, fechaLimiteInstant, vista.lineaProyecto, vista.lineaTitulacion)
    }

    private fun liberacionRevisionCompletada(e: Egresado): Boolean {
        val p = e.procesoActivoOrNull() ?: return false
        return p.fechaRecibidoRegistroLiberacion != null || p.fechaLiberacionDocumentoCoordinacionCat != null
    }

    private fun ultimaRevisionResultado(e: Egresado, ctx: BandejaDepartamentoCtx? = null): String? {
        val oid = e.id ?: return null
        val pid = e.procesoActivoOrNull()?.id ?: return null
        if (ctx != null) return ctx.ultimasRevisiones[oid to pid]
        return revisionService.ultimaRevision(oid, pid)?.resultado
    }

    private fun enCorreccionAcademico(e: Egresado, ctx: BandejaDepartamentoCtx? = null): Boolean =
        !esResidenciaProfesional(e) && ultimaRevisionResultado(e, ctx) == "observaciones"

    private fun estadoRevisionDepartamento(e: Egresado, ctx: BandejaDepartamentoCtx? = null): String {
        if (liberacionRevisionCompletada(e)) return "aprobado"
        if (enCorreccionAcademico(e, ctx)) return "con_observaciones"
        return "pendiente"
    }

    /** Fecha límite del plazo activo + cuántos meses se usaron para llegar a ella (para el historial/log). */
    private data class PlazoActivoLocal(val fechaLimite: LocalDate, val meses: Long)

    /**
     * Límite del plazo activo (residencia 6 m desde Anexo XXXI; tesis 12/18 m paso 3 o 6 m tras
     * liberación). Única fuente de este cálculo — antes vivía duplicado (con el riesgo de que
     * alguien tocara una copia y no la otra): [verificarYMarcarVencido] recalculaba `meses` por
     * su cuenta en vez de reusar el resultado de esta función.
     */
    private fun plazoActivoLocal(e: Egresado): PlazoActivoLocal? {
        val p = e.procesoActivoOrNull() ?: return null
        if (p.estado_general.equals("titulado", ignoreCase = true) ||
            p.estado_general.equals("vencido", ignoreCase = true)
        ) {
            return null
        }
        if (p.fechaConfirmacionDocumentacionEscaneadaRecibida != null) return null
        val zone = zonaMexico
        if (esCeneval(e)) return null
        val plazos = plazosModalidad(p.datos_proyecto.modalidad)
        if (esResidenciaProfesional(e)) {
            val inicio = p.documentos.anexo_xxxi.fecha_registro?.atZone(ZoneOffset.UTC)?.toLocalDate()
                ?: p.fechaCreacion.atZone(zone).toLocalDate()
            return PlazoActivoLocal(inicio.plusMonths(plazos.mesesResidencia), plazos.mesesResidencia)
        }
        if (p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii != null ||
            p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
        ) {
            if (p.fechaEnviadoDepartamentoAcademico != null) return null
            val esCurso = p.datos_proyecto.curso_titulacion.equals("si", ignoreCase = true) ||
                p.datos_proyecto.modalidad.contains("cursos especiales", ignoreCase = true)
            val liberacion = p.fechaSolicitudRegistroLiberacionDeptoAcademico
            val inicio = when {
                liberacion != null -> liberacion.atZone(zone).toLocalDate()
                esCurso -> p.documentos.constancia_no_inconveniencia.fecha_expedicion
                    ?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: return null
                else -> p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii?.atZone(zone)?.toLocalDate() ?: return null
            }
            val meses = if (liberacion != null) plazos.mesesTitulacion else plazos.mesesProyecto
            return PlazoActivoLocal(inicio.plusMonths(meses), meses)
        }
        val modalidad = p.datos_proyecto.modalidad
        val meses = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(modalidad.trim(), ignoreCase = true) }
            ?.mesesVigencia?.toLong() ?: mesesPorModalidadFallback(modalidad)
            ?: return null
        val inicio = p.fechaEnviadoDepartamentoAcademico ?: p.fechaCreacion
        return PlazoActivoLocal(inicio.atZone(zone).toLocalDate().plusMonths(meses), meses)
    }

    /**
     * Avanza un paso del flujo armado en el catálogo y ejecuta la acción de ese paso.
     * Tesis, residencia y CENEVAL no entran: si la modalidad no es de flujo propio, no cambia nada.
     */
    fun avanzarFlujoPropio(id: String, loHaceEgresado: Boolean, fechaHora: String? = null): ResultadoAvanceFlujo {
        val objectId = try {
            ObjectId(id)
        } catch (_: Exception) {
            return ResultadoAvanceFlujo.Fallo("No se encontró el expediente.")
        }
        val e = egresadoRepository.findById(objectId).orElse(null)
            ?: return ResultadoAvanceFlujo.Fallo("No se encontró el expediente.")
        val p = e.procesoActivoOrNull() ?: return ResultadoAvanceFlujo.Fallo("No hay un proceso activo.")
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(p.datos_proyecto.modalidad.trim(), ignoreCase = true) }
            ?: return ResultadoAvanceFlujo.Fallo("La modalidad no está en el catálogo.")
        if (cat.origenFlujo != "personalizado" || cat.pasos.isEmpty()) {
            return ResultadoAvanceFlujo.Fallo("Esta modalidad no tiene un flujo propio.")
        }
        val hechos = p.avancesFlujoPropio.map { it.indice }.toSet()
        val indice = cat.pasos.indices.firstOrNull { it !in hechos }
            ?: return ResultadoAvanceFlujo.Fallo("El flujo ya está completo.")
        val paso = cat.pasos[indice]
        val loHaceElEgresado = paso.hace.trim().equals("Egresado", ignoreCase = true)
        if (loHaceEgresado != loHaceElEgresado) {
            val texto = if (loHaceElEgresado) "Este paso lo hace el egresado." else "Este paso lo hace ${paso.hace}."
            return ResultadoAvanceFlujo.Fallo(texto)
        }
        val ahora = Instant.now()
        val confirmarAlAvanzar = paso.accion.trim() == "Confirmar (hasta que avance el proceso)"
        if (confirmarAlAvanzar) {
            val espera = esperaDeConfirmacion(p, cat.pasos.getOrNull(indice - 1))
            if (espera != null) return ResultadoAvanceFlujo.Fallo(espera)
        }
        val pasoEfecto = if (confirmarAlAvanzar) paso.copy(accion = "Confirmar") else paso
        val efecto = efectoAccionFlujoPropio(p, pasoEfecto, ahora, fechaHora)
        if (efecto.error != null) return ResultadoAvanceFlujo.Fallo(efecto.error)
        if (efecto.generar == "9.3") {
            egresadoRepository.save(e.actualizarProcesoActivo(efecto.proceso))
        }
        val pdf = when (efecto.generar) {
            "9.1" -> crearAnexo91(id, omitirPrerequisito = true)
            "9.2" -> crearAnexo92(id, omitirPrerequisito = true)
            "9.3" -> crearAnexo93(id)?.pdf
            else -> null
        }
        if (efecto.generar != null && pdf == null) {
            return ResultadoAvanceFlujo.Fallo("No se pudo generar el anexo ${efecto.generar}.")
        }
        val fresco = egresadoRepository.findById(objectId).orElse(e)
        val proc = fresco.procesoActivoOrNull() ?: efecto.proceso
        var actualizado = efecto.proceso.copy(
            fechaCreacionAnexo91 = proc.fechaCreacionAnexo91 ?: efecto.proceso.fechaCreacionAnexo91,
            fechaCreacionAnexo92 = proc.fechaCreacionAnexo92 ?: efecto.proceso.fechaCreacionAnexo92,
            fechaCreacionAnexo93 = proc.fechaCreacionAnexo93 ?: efecto.proceso.fechaCreacionAnexo93,
            anexo92Pdf = proc.anexo92Pdf ?: efecto.proceso.anexo92Pdf,
            avancesFlujoPropio = p.avancesFlujoPropio + com.sit_titulacion.sit.domain.AvanceFlujoPropio(
                indice = indice,
                fecha = ahora,
                accion = paso.accion,
            ),
            fecha_actualizacion = ahora,
        )
        if (paso.cierraProceso) {
            actualizado = actualizado.copy(
                estado_general = "titulado",
                fechaTitulacion = ahora,
                historial_estados = actualizado.historial_estados + com.sit_titulacion.sit.domain.HistorialEstado(
                    estado = "titulado",
                    fecha = ahora,
                    observacion = "Cerró el flujo de la modalidad",
                ),
            )
        }
        val guardado = egresadoRepository.save(e.actualizarProcesoActivo(actualizado))
        if (efecto.certificar && esResidenciaProfesional(guardado)) {
            try {
                val resultado = certService.certificarDocumento(guardado)
                val proc = guardado.procesoActivoOrNull()
                if (resultado != null && proc != null) {
                    egresadoRepository.save(
                        guardado.actualizarProcesoActivo(
                            proc.copy(
                                documento_adjunto = proc.documento_adjunto.copy(gridfs_id = resultado.nuevoGridFsId),
                                cert_uuid = resultado.certUuid,
                                cert_hash = resultado.certHash,
                                fechaCertificacion = ahora,
                                fecha_actualizacion = Instant.now(),
                            ),
                        ),
                    )
                }
            } catch (ex: Exception) {
                log.error("Error al certificar documento de flujo propio id={}: {}", id, ex.message, ex)
            }
        }
        val archivo = if (pdf != null && efecto.generar != null) {
            ResultadoAvanceFlujo.Pdf(pdf, "Anexo-${efecto.generar}.pdf")
        } else null
        return archivo ?: ResultadoAvanceFlujo.Hecho(efecto.mensaje)
    }

    private data class EfectoAccionFlujo(
        val proceso: ProcesoTitulacion,
        val mensaje: String,
        val generar: String? = null,
        val certificar: Boolean = false,
        val error: String? = null,
    )

    private fun enviarAPestanaDepartamento(p: ProcesoTitulacion, pestanaRaw: String, ahora: Instant): EfectoAccionFlujo {
        val pestana = pestanaRaw.trim()
        val n = pestana.lowercase()
        return when (n) {
            "anteproyecto" -> EfectoAccionFlujo(
                p.copy(
                    fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico ?: ahora,
                    pestanaDepartamento = "Anteproyecto",
                ),
                "Enviado a la pestaña Anteproyecto.",
            )
            "liberación de producto", "liberacion de producto" -> EfectoAccionFlujo(
                p.copy(
                    fechaConfirmacionRecepcionInicialAnexosXxxiXxxii = p.fechaConfirmacionRecepcionInicialAnexosXxxiXxxii ?: ahora,
                    pestanaDepartamento = "Liberación de producto",
                ),
                "Enviado a la pestaña Liberación de producto.",
            )
            "sinodales" -> EfectoAccionFlujo(
                p.copy(
                    fechaSolicitudSinodales = p.fechaSolicitudSinodales ?: ahora,
                    pestanaDepartamento = "Sinodales",
                ),
                "Enviado a la pestaña Sinodales.",
            )
            "residencia", "" -> EfectoAccionFlujo(
                p.copy(
                    fechaEnviadoDepartamentoAcademico = p.fechaEnviadoDepartamentoAcademico ?: ahora,
                    pestanaDepartamento = pestana.ifBlank { null },
                ),
                "Enviado al departamento académico.",
                certificar = true,
            )
            else -> EfectoAccionFlujo(
                p.copy(
                    fechaEnviadoDepartamentoAcademico = p.fechaEnviadoDepartamentoAcademico ?: ahora,
                    pestanaDepartamento = pestana,
                ),
                "Enviado a la pestaña $pestana.",
            )
        }
    }

    /** El botón Confirmar de este paso espera a que quien recibió el paso anterior ya haya avanzado. */
    private fun esperaDeConfirmacion(p: ProcesoTitulacion, previo: PasoFlujoCatalogo?): String? {
        if (previo == null) return "Este confirmar necesita un paso anterior."
        val recibe = previo.recibe.lowercase()
        val pestana = previo.pestanaDepartamento.trim().lowercase()
        val listo = when {
            recibe.contains("departamento") -> when (pestana) {
                "anteproyecto" -> p.fechaRegistradoDepartamento != null
                "liberación de producto", "liberacion de producto" ->
                    p.fechaSolicitudRegistroLiberacionDeptoAcademico != null
                "sinodales" -> p.fechaAsignacionSinodales != null
                else -> p.fechaRecibidoRegistroLiberacion != null
            }
            recibe.contains("servicios") -> p.fechaAceptacionServiciosEscolaresAnexo92 != null
            recibe.contains("egresado") -> p.fechaEnvioDocumentacionEscaneadaEgresado != null
            else -> p.fechaRecibidoRegistroLiberacion != null
        }
        return if (listo) null else "Confirmar aparece cuando ${previo.recibe.trim()} avance el proceso."
    }

    /** Misma acción que el botón del flujo normal, según la acción, el anexo y quién recibe. */
    private fun efectoAccionFlujoPropio(
        p: ProcesoTitulacion,
        paso: PasoFlujoCatalogo,
        ahora: Instant,
        fechaHora: String?,
    ): EfectoAccionFlujo {
        val accion = paso.accion.trim()
        val anexo = paso.anexo.lowercase()
        val recibe = paso.recibe.lowercase()
        fun listo(proceso: ProcesoTitulacion, mensaje: String, generar: String? = null, certificar: Boolean = false) =
            EfectoAccionFlujo(proceso, mensaje, generar, certificar)
        fun fallo(texto: String) = EfectoAccionFlujo(p, "", error = texto)
        return when (accion) {
            "Enviar" -> when {
                recibe.contains("divisi") -> listo(
                    p.copy(fechaRecepcionTrabajoDivisionEstudiosProf = p.fechaRecepcionTrabajoDivisionEstudiosProf ?: ahora),
                    "Enviado a la división de estudios profesionales.",
                )
                recibe.contains("servicios") -> listo(
                    p.copy(fechaSolicitudAnexo92 = p.fechaSolicitudAnexo92 ?: ahora),
                    "Enviado a servicios escolares.",
                )
                recibe.contains("egresado") -> listo(
                    p.copy(fechaSolicitudDocumentacionEscaneada = p.fechaSolicitudDocumentacionEscaneada ?: ahora),
                    "Enviado al egresado.",
                )
                else -> enviarAPestanaDepartamento(p, paso.pestanaDepartamento, ahora)
            }
            "Confirmar" -> when {
                anexo.contains("9.1") -> {
                    if (p.fechaCreacionAnexo91 == null) fallo("Primero genera el anexo 9.1.")
                    else listo(
                        p.copy(fechaConfirmacionEntregaAnexo91 = p.fechaConfirmacionEntregaAnexo91 ?: ahora),
                        "Entrega del anexo 9.1 confirmada.",
                    )
                }
                anexo.contains("9.2") -> {
                    if (p.fechaCreacionAnexo92 == null && p.fechaAceptacionServiciosEscolaresAnexo92 == null) {
                        fallo("Primero se genera el anexo 9.2.")
                    } else listo(
                        p.copy(fechaConfirmacionRecibidoAnexo92 = p.fechaConfirmacionRecibidoAnexo92 ?: ahora),
                        "Recepción del anexo 9.2 confirmada.",
                    )
                }
                anexo.contains("9.3") -> {
                    if (p.fechaCreacionAnexo93 == null) fallo("Primero genera el anexo 9.3.")
                    else listo(
                        p.copy(fechaConfirmacionEntregaAnexo93 = p.fechaConfirmacionEntregaAnexo93 ?: ahora),
                        "Entrega del anexo 9.3 confirmada.",
                    )
                }
                anexo.contains("xxxi") || anexo.contains("xxxii") || anexo.contains("xxxiii") -> listo(
                    p.copy(
                        fechaRecibidoRegistroLiberacion = p.fechaRecibidoRegistroLiberacion ?: ahora,
                        fechaConfirmacionRecibidosAnexoXxxiXxxii = p.fechaConfirmacionRecibidosAnexoXxxiXxxii ?: ahora,
                    ),
                    "Recepción de anexos confirmada.",
                )
                else -> listo(p, "Paso confirmado.")
            }
            "Generar anexo" -> when {
                anexo.contains("9.1") -> listo(p, "Anexo 9.1 generado.", generar = "9.1")
                anexo.contains("9.2") -> listo(p, "Anexo 9.2 generado.", generar = "9.2")
                anexo.contains("9.3") -> {
                    if (p.fechaAgendaActo93 == null) fallo("Agenda el acto antes de generar el anexo 9.3.")
                    else listo(p, "Anexo 9.3 generado.", generar = "9.3")
                }
                anexo.contains("xxxii") || anexo.contains("xxxiii") -> listo(
                    p.copy(
                        fechaGeneracionAnexosXxxiiXxxxiii = p.fechaGeneracionAnexosXxxiiXxxxiii ?: ahora,
                        fechaRecibidoRegistroLiberacion = p.fechaRecibidoRegistroLiberacion ?: ahora,
                    ),
                    "Anexos XXXII y XXXIII registrados.",
                )
                else -> fallo("Elige el anexo que se va a generar.")
            }
            "Solicitar" -> when {
                anexo.contains("9.2") || recibe.contains("servicios") -> listo(
                    p.copy(fechaSolicitudAnexo92 = p.fechaSolicitudAnexo92 ?: ahora),
                    "Solicitud enviada a servicios escolares.",
                )
                anexo.contains("9.3") || paso.nombre.contains("sinodal", ignoreCase = true) -> listo(
                    p.copy(fechaSolicitudSinodales = p.fechaSolicitudSinodales ?: ahora),
                    "Solicitud de sinodales enviada.",
                )
                else -> listo(
                    p.copy(fechaSolicitudDocumentacionEscaneada = p.fechaSolicitudDocumentacionEscaneada ?: ahora),
                    "Solicitud enviada al egresado.",
                )
            }
            "Agendar" -> {
                val inicio = parseFechaHoraActo93(fechaHora ?: "") ?: return fallo("Elige fecha y hora del acto.")
                val zInicio = inicio.atZone(zonaActo93)
                if (zInicio.dayOfWeek.value !in 1..5) return fallo("El acto se agenda de lunes a viernes.")
                val horaInicio = zInicio.toLocalTime()
                val horaFin = horaInicio.plusHours(1)
                if (horaInicio < java.time.LocalTime.of(9, 0) || horaFin > java.time.LocalTime.of(14, 0)) {
                    return fallo("El horario del acto es de 9:00 a 14:00.")
                }
                listo(
                    p.copy(
                        fechaAgendaActo93 = inicio,
                        fechaReagendaActo93 = if (p.fechaAgendaActo93 != null) ahora else p.fechaReagendaActo93,
                    ),
                    "Acto agendado.",
                )
            }
            "Subir documento" -> {
                if (p.fechaEnvioDocumentacionEscaneadaEgresado == null) {
                    fallo("El egresado todavía no sube el PDF de este paso.")
                } else listo(
                    p.copy(fechaConfirmacionDocumentacionEscaneadaRecibida = p.fechaConfirmacionDocumentacionEscaneadaRecibida ?: ahora),
                    "Documento recibido.",
                )
            }
            else -> listo(p, "Paso registrado.")
        }
    }

    private fun plazosModalidad(nombre: String): PlazosModalidad {
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre.trim(), ignoreCase = true) }
        return plazosDeModalidad(cat)
    }

    private fun verificarYMarcarVencido(e: Egresado): Egresado {
        val p = e.procesoActivoOrNull() ?: return e
        if (p.estado_general == "titulado" || p.estado_general == "vencido") return e
        val plazo = plazoActivoLocal(e) ?: return e
        if (LocalDate.now(zonaMexico) > plazo.fechaLimite) {
            val ahora = Instant.now()
            val pVencido = p.copy(
                estado_general = "vencido",
                fecha_actualizacion = ahora,
                historial_estados = p.historial_estados + HistorialEstado(
                    estado = "vencido", fecha = ahora, observacion = "Plazo de ${plazo.meses} mes(es) expirado",
                ),
            )
            val vencido = e.actualizarProcesoActivo(pVencido)
            val guardado = egresadoRepository.save(vencido)
            log.info("Egresado id={} marcado como vencido (plazo {} meses expirado)", e.id, plazo.meses)
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
            m.contains("monograf") -> 12L
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

    private fun generoDocumentoEgresado(e: Egresado): GeneroDocumento =
        GeneroPorNombre.resolver(e.datos_personales.genero, nombreCompleto(e))

    private fun mapaGeneroDocentesActivos(): Map<String, GeneroDocumento> =
        docenteRepository.findByActivoTrue().associate { d ->
            d.nombreCompleto.trim().uppercase(Locale.ROOT) to
                GeneroPorNombre.resolver(d.genero, d.nombreCompleto)
        }

    private fun generoSinodal(nombre: String, mapa: Map<String, GeneroDocumento>): GeneroDocumento {
        val key = nombre.trim().uppercase(Locale.ROOT)
        return mapa[key] ?: GeneroPorNombre.inferir(nombre)
    }

    private fun extrasGeneroDocumentos(
        e: Egresado,
        tribunal: SinodalesTribunal?,
        nombreDepto: String,
        titularDeptoNombre: String,
        titularDeptoCargo: String?,
        jefeDivisionNombre: String,
        jefeDivisionCargo: String,
    ): List<Pair<String, String>> {
        val generoEgresado = generoDocumentoEgresado(e)
        val mapaDoc = mapaGeneroDocentesActivos()
        val cargoTitular = titularDeptoCargo?.trim()?.takeIf { it.isNotBlank() }
            ?: GeneroPorNombre.cargoTitularDepartamento(
                nombreDepto,
                GeneroPorNombre.resolver(null, titularDeptoNombre),
            )
        val extras = mutableListOf(
            "PREFIJO_EGRESADO" to GeneroPorNombre.etiquetaCiudadano(generoEgresado),
            "TRATAMIENTO_EGRESADO" to GeneroPorNombre.tratamientoEgresadoOficio(generoEgresado),
            "JEFE_DIVISION_CARGO" to GeneroPorNombre.cargoConGenero(
                jefeDivisionCargo,
                GeneroPorNombre.resolver(null, jefeDivisionNombre),
            ),
            "JEFA_DEPARTAMENTO_CARGO" to cargoTitular,
        )
        tribunal?.let { t ->
            extras += "ETIQUETA_PRESIDENTE" to GeneroPorNombre.etiquetaPresidente(
                generoSinodal(t.presidente, mapaDoc),
            )
            extras += "ETIQUETA_SECRETARIO" to GeneroPorNombre.etiquetaSecretario(
                generoSinodal(t.secretario, mapaDoc),
            )
        }
        return extras
    }

    private fun extrasCodigoRevisionAnexo(anexo: String): List<Pair<String, String>> {
        val (codigo, revision) = configInstitucionalService.codigoYRevisionAnexo(anexo)
        return listOf(
            "CODIGO_DOCUMENTO" to codigo,
            "REVISION_DOCUMENTO" to revision,
        )
    }

    private fun construirValoresPlantillaHtml(e: Egresado, extras: List<Pair<String, String>>): Map<String, String> {
        val pr = e.procesoActivoOrNull()
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zonaMexico)
        val valores = mutableMapOf(
            "NOMBRE" to nombreCompleto(e),
            "NUMERO_CONTROL" to e.numero_control,
            "CARRERA" to e.datos_personales.carrera,
            "NIVEL" to e.datos_personales.nivel.trim(),
            "PROYECTO" to (pr?.datos_proyecto?.nombre_proyecto ?: ""),
            "FECHA_GENERACION" to fmt.format(Instant.now()),
        )
        for ((k, v) in extras) valores[k] = v
        expandirAliasPlantilla(valores)
        return valores
    }

    private fun fechaCartaEspanolaCapitalizada(instant: Instant): String {
        val z = instant.atZone(zonaMexico)
        val mes = nombreMesEspanol(z.monthValue).lowercase(Locale.forLanguageTag("es-MX"))
            .replaceFirstChar { it.uppercase(Locale.forLanguageTag("es-MX")) }
        return "${z.dayOfMonth} de $mes de ${z.year}"
    }

    private fun fechaCartaEspanolaAnexo93(instant: Instant): String {
        val z = instant.atZone(zonaMexico)
        val mes = nombreMesEspanol(z.monthValue)
        val dia = z.dayOfMonth.toString().padStart(2, '0')
        return "$dia de $mes del ${z.year}"
    }

    private fun toTitleCaseCarrera(s: String): String {
        val exact = mapOf(
            "INGENIERIA EN AGRONOMÍA" to "Ingeniería en Agronomía",
            "LICENCIATURA EN BIOLOGÍA" to "Licenciatura en Biología",
            "INGENIERIA FORESTAL" to "Ingeniería Forestal",
            "INGENIERIA INFORMÁTICA" to "Ingeniería Informática",
            "INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION" to "Ingeniería en Tecnología de la Información y Comunicación",
            "INGENIERIA EN CIENCIA DE DATOS" to "Ingeniería en Ciencia de Datos",
            "INGENIERIA SISTEMAS COMPUTACIONALES" to "Ingeniería en Sistemas Computacionales",
            "INGENIERIA AMBIENTAL" to "Ingeniería Ambiental",
            "INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)" to "Ingeniería en Gestión Empresarial (Virtual)",
        )
        val key = s.trim().uppercase()
        exact[key]?.let { return it }
        return toTitleCase(s)
    }

    /** "Tipo oración": mayúscula al inicio de cada palabra, para texto libre que
     *  pudo haberse capturado todo en mayúsculas o minúsculas (nombres de proyecto,
     *  asesores, etc.). Cada línea de un texto multilínea se procesa por separado. */
    private fun toTitleCase(s: String): String {
        val small = setOf("EN", "DE", "LA", "EL", "LOS", "LAS", "DEL", "Y", "O")
        return s.split("\n").joinToString("\n") { linea ->
            linea.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.mapIndexed { i, w ->
                if (i != 0 && w.uppercase() in small) w.lowercase()
                else w.lowercase().replaceFirstChar { it.uppercaseChar() }
            }.joinToString(" ")
        }
    }

    /** Fecha del oficio de sinodales: 29/abril/2026 */
    private fun fechaOficioSlash(instant: Instant): String {
        val z = instant.atZone(zonaMexico)
        val mes = nombreMesEspanol(z.monthValue).lowercase(Locale.forLanguageTag("es-MX"))
        return "${z.dayOfMonth}/$mes/${z.year}"
    }

    private fun siglasOficioDepartamento(slug: String?, nombreDepto: String): String {
        val mapaRaw = env.getProperty(
            "sit.oficio-sinodales.siglas-por-slug",
            "ciencias_basicas:DCB,ingenierias:DEI,economico_administrativo:DEA,virtuales:DV",
        )
        val mapa = mapaRaw.split(",")
            .mapNotNull { par ->
                val idx = par.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                par.substring(0, idx).trim().lowercase(Locale.ROOT) to par.substring(idx + 1).trim().uppercase(Locale.ROOT)
            }
            .toMap()
        slug?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null }?.let { mapa[it] }?.let { return it }
        val n = nombreDepto.lowercase(Locale.ROOT)
        return when {
            n.contains("básicas") || n.contains("basicas") -> "DCB"
            n.contains("ingenier") -> "DEI"
            n.contains("económico") || n.contains("economico") || n.contains("administrativ") -> "DEA"
            n.contains("virtual") -> "DV"
            else -> "DCB"
        }
    }

    private fun numeroOficioSinodales(e: Egresado, siglas: String, instant: Instant): String {
        val anio = instant.atZone(zonaMexico).year
        val base = e.id?.timestamp?.toLong() ?: instant.toEpochMilli()
        val folio = (base % 998L + 1L).toInt()
        return "$siglas/$folio/$anio"
    }

    private fun nombreMesEspanol(monthValue1to12: Int): String {
        val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
        return meses.getOrElse(monthValue1to12 - 1) { "—" }.replaceFirstChar { it.uppercaseChar() }
    }

    private fun cargoJefaDepartamentoOficio(nombreDepto: String): String {
        var n = nombreDepto.trim().uppercase(Locale.forLanguageTag("es-MX"))
        n = n.replace("DEPARTAMENTO DE ", "DEPTO. DE ")
        n = n.replace("DEPARTAMENTO DEL ", "DEPTO. DEL ")
        return "JEFA DEL $n"
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

    /** Opción entre paréntesis del Anexo 9.1: tipo oración (Tesina, Tesis, …). */
    private fun textoOpcionTitulacionIntegralTipoOracion(modalidad: String): String {
        val m = modalidad.trim().lowercase(Locale.ROOT)
        return when {
            m.contains("residencia") -> "Reporte final de residencia profesional"
            m.contains("tesina") -> "Tesina"
            m.contains("ceneval") -> "Examen CENEVAL"
            m.contains("tesis") -> "Tesis"
            m.contains("monograf") -> "Monografía"
            m.contains("investigacion") || m.contains("investigación") -> "Proyecto de investigación"
            else -> aTipoOracion(modalidad)
        }
    }

    /** Primera letra mayúscula, resto minúsculas (tipo oración). */
    private fun aTipoOracion(s: String): String {
        val t = s.trim().lowercase(Locale.forLanguageTag("es-MX"))
        if (t.isEmpty()) return s
        return t.replaceFirstChar { it.uppercase(Locale.forLanguageTag("es-MX")) }
    }

    /**
     * Título profesional para «obtener mi Título Profesional de …» en Anexo 9.1.
     * Ej.: Ingeniería Informática → Ingeniero/a informático/a (no el nombre de la carrera).
     */
    private fun tituloProfesionalDesdeCarrera(carrera: String, genero: GeneroDocumento): String {
        val k = NormalizacionTexto.normalizarClave(carrera)
        val f = genero == GeneroDocumento.FEMENINO
        val texto = when {
            k.contains("informatica") && !k.contains("tecnologia") ->
                if (f) "Ingeniera informática" else "Ingeniero informático"
            k.contains("forestal") ->
                if (f) "Ingeniera forestal" else "Ingeniero forestal"
            k.contains("agronom") ->
                if (f) "Ingeniera en agronomía" else "Ingeniero en agronomía"
            k.contains("tecnologia de la informacion") ||
                (k.contains("tecnologia") && k.contains("comunicacion")) ->
                if (f) "Ingeniera en tecnología de la información y comunicación"
                else "Ingeniero en tecnología de la información y comunicación"
            k.contains("ciencia de datos") ->
                if (f) "Ingeniera en ciencia de datos" else "Ingeniero en ciencia de datos"
            k.contains("sistemas computacionales") ->
                if (f) "Ingeniera en sistemas computacionales" else "Ingeniero en sistemas computacionales"
            k.contains("ambiental") ->
                if (f) "Ingeniera ambiental" else "Ingeniero ambiental"
            k.contains("gestion empresarial") ->
                if (f) "Ingeniera en gestión empresarial" else "Ingeniero en gestión empresarial"
            k.contains("biologia") ->
                if (f) "Licenciada en biología" else "Licenciado en biología"
            k.startsWith("licenciatura en ") -> {
                val resto = aTipoOracion(carrera.replace(Regex("(?i)^\\s*licenciatura\\s+en\\s+"), ""))
                if (f) "Licenciada en ${resto.replaceFirstChar { it.lowercase(Locale.forLanguageTag("es-MX")) }}"
                else "Licenciado en ${resto.replaceFirstChar { it.lowercase(Locale.forLanguageTag("es-MX")) }}"
            }
            k.startsWith("ingenieria en ") || k.startsWith("ingeniería en ") -> {
                val resto = aTipoOracion(carrera.replace(Regex("(?i)^\\s*ingenier[ií]a\\s+en\\s+"), ""))
                    .replaceFirstChar { it.lowercase(Locale.forLanguageTag("es-MX")) }
                if (f) "Ingeniera en $resto" else "Ingeniero en $resto"
            }
            k.startsWith("ingenieria ") || k.startsWith("ingeniería ") -> {
                val resto = aTipoOracion(carrera.replace(Regex("(?i)^\\s*ingenier[ií]a\\s+"), ""))
                    .replaceFirstChar { it.lowercase(Locale.forLanguageTag("es-MX")) }
                if (f) "Ingeniera $resto" else "Ingeniero $resto"
            }
            else -> toTitleCaseCarrera(carrera)
        }
        return texto.uppercase(Locale.forLanguageTag("es-MX"))
    }

    /** CENEVAL: texto fijo en documentos; demás modalidades: nombre del proyecto. */
    private fun textoProyectoDocumentosTitulacion(e: Egresado, p: ProcesoTitulacion): String {
        if (esCeneval(e)) return "SIN TEMA"
        return p.datos_proyecto.nombre_proyecto.trim()
    }

    private fun fraseTemaOficioSinodales(e: Egresado, p: ProcesoTitulacion): String {
        if (esCeneval(e)) return "SIN TEMA"
        val proy = p.datos_proyecto.nombre_proyecto.trim()
        if (proy.isEmpty()) return ""
        return "con el tema \"${proy.uppercase(Locale.forLanguageTag("es-MX"))}\""
    }

    /** CENEVAL no tiene proyecto: la oración sigue directo al día del acto. */
    private fun fraseProyectoAnexo93Html(e: Egresado, p: ProcesoTitulacion): String {
        if (esCeneval(e)) return ","
        val nombre = p.datos_proyecto.nombre_proyecto.trim()
        if (nombre.isEmpty()) return ","
        val seguro = nombre
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return """, con el proyecto titulado: <span class="b">&quot;$seguro&quot;</span>,"""
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
        ArchivoPdfValidator.validar(archivo)
        val bytes = archivo.bytes
        return gridFsTemplate.store(
            bytes.inputStream(),
            archivo.originalFilename ?: "documento.pdf",
            "application/pdf",
            null,
        ) as ObjectId
    }

    /** Cursos especiales usa el mismo plazo que el checkbox «Curso de titulación». */
    private fun normalizarCursoTitulacion(curso: String?, modalidad: String): String {
        if (curso?.trim()?.equals("si", ignoreCase = true) == true) return "si"
        val mod = modalidad.trim().lowercase(Locale.ROOT)
        if (mod.contains("cursos especiales")) return "si"
        return "no"
    }

    /** Normaliza el tipo de titulación según lo enviado o la modalidad tradicional. */
    private fun normalizarTipoTitulacion(tipo: String?, modalidad: String): String {
        val escrito = tipo?.trim().orEmpty()
        val t = escrito.lowercase(Locale.ROOT)
        if (t == "titulacion" || t == "titulacion_integral") return t
        if (t.isNotEmpty() && t != "curso_titulacion") {
            val propio = catalogoRepository.findByTipoAndActivoTrue("modalidad")
                .mapNotNull { it.tipoUso?.trim()?.takeIf { uso -> uso.isNotEmpty() } }
                .firstOrNull { it.equals(escrito, ignoreCase = true) && it.lowercase(Locale.ROOT) !in setOf("titulacion", "integral") }
            if (propio != null) return propio.take(40)
        }
        val mod = modalidad.trim().lowercase(Locale.ROOT)
        // Modalidades del tipo Titulación (tradicional)
        if (
            mod == "tesis profesional" ||
            mod.contains("tesis profesional") ||
            mod == "proyectos de investigacion" ||
            mod.contains("proyectos de investigacion") ||
            mod.contains("cursos especiales") ||
            mod.contains("examen por areas de conocimiento (ceneval)")
        ) {
            return "titulacion"
        }
        return "titulacion_integral"
    }

    private fun buildDocumentoAdjunto(archivo: MultipartFile?, ahora: Instant): DocumentoAdjunto {
        if (archivo == null || archivo.isEmpty) return DocumentoAdjunto()
        val gridFsId = subirArchivo(archivo)
        return DocumentoAdjunto(
            gridfs_id = gridFsId,
            nombre_original = archivo.originalFilename ?: "",
            content_type = "application/pdf",
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

    /** Asesores del Anexo XXXII: los que ya están guardados en el expediente, no los del formulario. */
    private fun nombresAsesoresAnexo32(e: Egresado, p: ProcesoTitulacion): String {
        val d = p.datos_proyecto
        val crudos = when {
            esResidenciaProfesional(e) -> listOf(d.asesor_interno, d.asesor_externo)
            esModalidadConDirector(d.modalidad) -> listOf(d.director, d.asesor_1, d.asesor_2)
            else -> listOf(d.asesor_interno, d.asesor_externo, d.director, d.asesor_1, d.asesor_2)
        }
        return crudos.mapNotNull { it?.trim()?.takeIf { nombre -> nombre.isNotBlank() } }
            .distinct()
            .joinToString("\n")
    }

    fun crearHoja32(
        id: String,
        observaciones: String? = null,
        slugDepartamento: String? = null,
    ): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        val deptConfig = departamentoAnexo(e.datos_personales.carrera, slugDepartamento)
        val firmaJefe32 = firmaJefeAnexo(deptConfig)
        val certId32 = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() }
            ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri32 = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId32")
        val asesores = nombresAsesoresAnexo32(e, p)
        val textoObservaciones = if (observaciones != null) {
            observaciones.trim()
        } else {
            p.datos_proyecto.observaciones_anexo32?.trim().orEmpty()
        }
        val deptNombre = nombreDepartamentoAnexo(deptConfig, e.datos_personales.carrera)
        val tituloLower = configInstitucionalService.obtenerJefeDivisionTitulo()
            .lowercase().replaceFirstChar { it.uppercase() }
        val valores = construirValoresPlantillaHtml(e, listOf(
            "FECHA_CARTA" to fechaCartaEspanolaAnexo93(Instant.now()),
            "CARRERA" to toTitleCaseCarrera(e.datos_personales.carrera),
            "DEPARTAMENTO_NOMBRE" to deptNombre,
            "JEFE_DIVISION_NOMBRE" to configInstitucionalService.obtenerJefeDivisionNombre(),
            "JEFE_DIVISION_TITULO" to configInstitucionalService.obtenerJefeDivisionTitulo(),
            "JEFE_DIVISION_TITULO_LOWER" to tituloLower,
            "NOMBRE" to toTitleCase(nombreCompleto(e)),
            "NOMBRE_PROYECTO" to toTitleCase(p.datos_proyecto.nombre_proyecto),
            "NOMBRE_ASESORES" to toTitleCase(asesores),
            "OBSERVACIONES" to textoObservaciones,
            "NUM_ESTUDIANTES" to "1",
            "JEFE_DEPTO_NOMBRE" to firmaJefe32.nombre,
            "JEFE_DEPTO_CARGO" to firmaJefe32.cargo,
            "JEFE_DEPTO_INICIALES" to firmaJefe32.iniciales,
            "CORREO_DEPARTAMENTO" to (deptConfig?.correo ?: ""),
            "QR_CODE" to qrDataUri32,
        ))
        val pdf = htmlAnexoPdfService.generarDesdeClasspath(
            "templates/html/hoja-32-registro-proyecto.html",
            valores,
            configInstitucionalService.obtenerOverridesImagenesAnexos(),
            mapOf("INSIGNIAS_HTML" to configInstitucionalService.construirHtmlInsignias()),
        )
        if (pdf != null && (observaciones != null || p.fechaGeneracionHoja32 == null)) {
            val datos = p.datos_proyecto.copy(
                observaciones_anexo32 = textoObservaciones.ifBlank { null },
            )
            val ahora = Instant.now()
            egresadoRepository.save(
                e.actualizarProcesoActivo(
                    p.copy(
                        datos_proyecto = datos,
                        fechaGeneracionHoja32 = p.fechaGeneracionHoja32 ?: ahora,
                        fecha_actualizacion = ahora,
                    ),
                ),
            )
        }
        return pdf
    }

    fun crearHoja33(
        id: String,
        asesor: String? = null,
        revisor1: String? = null,
        revisor2: String? = null,
        slugDepartamento: String? = null,
    ): ByteArray? {
        val e = cargarEgresadoPorId(id) ?: return null
        val p = e.procesoActivoOrNull() ?: return null
        val certId33 = p.cert_uuid?.trim().takeUnless { it.isNullOrBlank() }
            ?: e.id?.toHexString() ?: e.numero_control
        val qrDataUri33 = generarQrDataUri("${baseUrlCert().trimEnd('/')}/#/verificar/$certId33")
        val deptConfig = departamentoAnexo(e.datos_personales.carrera, slugDepartamento)
        val firmaJefe33 = firmaJefeAnexo(deptConfig)
        val deptNombre33 = nombreDepartamentoAnexo(deptConfig, e.datos_personales.carrera)
        val esResidencia = esResidenciaProfesional(e)
        val usarSeleccion = asesor != null || revisor1 != null || revisor2 != null
        val firmaNombre1: String
        val firmaNombre2: String
        val firmaNombre3: String
        val firmaLabel1: String
        val firmaLabel2: String
        val firmaLabel3: String
        if (esResidencia) {
            firmaNombre1 = p.datos_proyecto.asesor_interno?.trim().orEmpty()
            firmaNombre2 = ""
            firmaNombre3 = ""
        } else if (usarSeleccion) {
            firmaNombre1 = asesor?.trim().orEmpty()
            firmaNombre2 = revisor1?.trim().orEmpty()
            firmaNombre3 = revisor2?.trim().orEmpty()
        } else {
            firmaNombre1 = p.datos_proyecto.director?.takeIf { it.isNotBlank() } ?: ""
            firmaNombre2 = p.datos_proyecto.asesor_1?.takeIf { it.isNotBlank() } ?: ""
            firmaNombre3 = p.datos_proyecto.asesor_2?.takeIf { it.isNotBlank() } ?: ""
        }
        firmaLabel1 = when {
            esResidencia -> "Nombre y firma del asesor interno"
            esModalidadConDirector(p.datos_proyecto.modalidad) -> "Nombre y firma del director"
            else -> "Nombre y firma del asesor"
        }
        firmaLabel2 = "Nombre y firma del revisor*"
        firmaLabel3 = "Nombre y firma del revisor*"
        val tituloLower33 = configInstitucionalService.obtenerJefeDivisionTitulo()
            .lowercase().replaceFirstChar { it.uppercase() }
        val valores = construirValoresPlantillaHtml(e, listOf(
            "FECHA_CARTA" to fechaCartaEspanolaAnexo93(Instant.now()),
            "CARRERA" to toTitleCaseCarrera(e.datos_personales.carrera),
            "DEPARTAMENTO_NOMBRE" to deptNombre33,
            "JEFE_DIVISION_NOMBRE" to configInstitucionalService.obtenerJefeDivisionNombre(),
            "JEFE_DIVISION_TITULO" to configInstitucionalService.obtenerJefeDivisionTitulo(),
            "JEFE_DIVISION_TITULO_LOWER" to tituloLower33,
            "NOMBRE" to toTitleCase(nombreCompleto(e)),
            "NOMBRE_PROYECTO" to toTitleCase(p.datos_proyecto.nombre_proyecto),
            "PRODUCTO" to textoOpcionTitulacionIntegral(p.datos_proyecto.modalidad),
            "FIRMA_NOMBRE_1" to toTitleCase(firmaNombre1),
            "FIRMA_NOMBRE_2" to toTitleCase(firmaNombre2),
            "FIRMA_NOMBRE_3" to toTitleCase(firmaNombre3),
            "FIRMA_LABEL_1" to firmaLabel1,
            "FIRMA_LABEL_2" to firmaLabel2,
            "FIRMA_LABEL_3" to firmaLabel3,
            "JEFE_DEPTO_NOMBRE" to firmaJefe33.nombre,
            "JEFE_DEPTO_CARGO" to firmaJefe33.cargo,
            "JEFE_DEPTO_INICIALES" to firmaJefe33.iniciales,
            "CORREO_DEPARTAMENTO" to (deptConfig?.correo ?: ""),
            "QR_CODE" to qrDataUri33,
        ))
        val pdf = htmlAnexoPdfService.generarDesdeClasspath(
            "templates/html/hoja-33-liberacion-proyecto.html",
            valores,
            configInstitucionalService.obtenerOverridesImagenesAnexos(),
            mapOf("INSIGNIAS_HTML" to configInstitucionalService.construirHtmlInsignias()),
        )
        if (pdf != null && (usarSeleccion || p.fechaGeneracionHoja33 == null)) {
            val datos = if (usarSeleccion && !esResidencia) {
                p.datos_proyecto.copy(
                    director = firmaNombre1.ifBlank { null },
                    asesor_1 = firmaNombre2.ifBlank { null },
                    asesor_2 = firmaNombre3.ifBlank { null },
                )
            } else {
                p.datos_proyecto
            }
            val ahora = Instant.now()
            egresadoRepository.save(
                e.actualizarProcesoActivo(
                    p.copy(
                        datos_proyecto = datos,
                        fechaGeneracionHoja33 = p.fechaGeneracionHoja33 ?: ahora,
                        fecha_actualizacion = ahora,
                    ),
                ),
            )
        }
        return pdf
    }

    fun obtenerNumeroControl(id: String): String? = cargarEgresadoPorId(id)?.numero_control

    fun registrarGeneracionAnexos(id: String): Boolean {
        val e = cargarEgresadoPorId(id) ?: return false
        val p = e.procesoActivoOrNull() ?: return false
        if (!esResidenciaProfesional(e)) return false
        if (p.fechaEnviadoDepartamentoAcademico == null) return false
        val ahora = Instant.now()
        egresadoRepository.save(
            e.actualizarProcesoActivo(
                p.copy(
                    fechaRecibidoRegistroLiberacion = p.fechaRecibidoRegistroLiberacion ?: ahora,
                    fechaGeneracionAnexosXxxiiXxxxiii = ahora,
                    fecha_actualizacion = ahora,
                ),
            ),
        )
        return true
    }
}

sealed class ResultadoAvanceFlujo {
    data class Hecho(val mensaje: String) : ResultadoAvanceFlujo()
    data class Fallo(val mensaje: String) : ResultadoAvanceFlujo()
    data class Pdf(val bytes: ByteArray, val nombre: String) : ResultadoAvanceFlujo()
}
