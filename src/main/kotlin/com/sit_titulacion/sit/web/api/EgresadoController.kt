package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.config.RolSoporte
import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.service.EgresadoService
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.service.UsuarioService
import com.sit_titulacion.sit.web.api.dto.DepartamentoListItemDto
import com.sit_titulacion.sit.web.api.dto.EgresadoDetailDto
import com.sit_titulacion.sit.web.api.dto.EgresadoListItemDto
import com.sit_titulacion.sit.web.api.dto.EgresadoRequestDto
import com.sit_titulacion.sit.web.api.dto.EgresadoResponseDto
import com.sit_titulacion.sit.web.api.dto.CreateRevisionRequestDto
import com.sit_titulacion.sit.web.api.dto.AgendarActoRequestDto
import com.sit_titulacion.sit.web.api.dto.AsignarSinodalesRequestDto
import com.sit_titulacion.sit.web.api.dto.SinodalesRespuestaDto
import com.sit_titulacion.sit.web.api.dto.RevisionDto
import com.sit_titulacion.sit.web.api.dto.SolicitarReenvioDocumentacionEscaneadaRequestDto
import com.sit_titulacion.sit.service.RevisionService
import com.sit_titulacion.sit.service.DocumentoStream
import com.sit_titulacion.sit.service.OriginalidadService
import org.springframework.core.env.Environment
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import jakarta.validation.Valid
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/egresados")
class EgresadoController(
    private val egresadoService: EgresadoService,
    private val egresadoRepository: EgresadoRepository,
    private val usuarioService: UsuarioService,
    private val emailService: EmailService,
    private val revisionService: RevisionService,
    private val originalidadService: OriginalidadService,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(EgresadoController::class.java)

    /** Diagnóstico: base de datos, colección y cantidad de documentos. */
    @GetMapping("/diagnostico")
    fun diagnostico(): ResponseEntity<Map<String, Any>> {
        val dbName = env.getProperty("spring.data.mongodb.database") ?: env.getProperty("spring.data.mongodb.uri")?.substringAfterLast("/")?.substringBefore("?") ?: "test"
        val collectionName = "registro"
        val count = egresadoRepository.count()
        return ResponseEntity.ok(mapOf(
            "mensaje" to "Configuración actual del backend",
            "base_de_datos" to dbName,
            "coleccion" to collectionName,
            "documentos_en_coleccion" to count,
            "donde_ver_en_compass" to "En Compass: 77.37.74.122:27017 → base '$dbName' → colección '$collectionName'",
        ))
    }

    @GetMapping
    fun listar(
        @RequestParam(required = false) numero_control: String?,
        @RequestParam(required = false) fecha_desde: String?,
        @RequestParam(required = false) fecha_hasta: String?,
        @RequestParam(required = false) tipo_filtro: String?,
        @RequestParam(required = false, defaultValue = "true") aplicar_scope_departamento: Boolean,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<List<EgresadoListItemDto>> {
        val desde = parseFechaParam(fecha_desde)
        val hasta = parseFechaParam(fecha_hasta, endOfDay = true)
        val tipo = when (tipo_filtro?.trim()?.lowercase()) {
            "anexo_xxxi", "anexo" -> "anexo_xxxi"
            "constancia" -> "constancia"
            else -> null
        }
        val scopeUsername =
            if (aplicar_scope_departamento && principal != null && puedeVerBandejaDepartamento(principal.getRol())) {
                principal.username
            } else {
                null
            }
        val lista = egresadoService.listarParaLista(numero_control, desde, hasta, tipo, scopeUsername)
        return ResponseEntity.ok(lista)
    }

    /** Conteos para pestañas del departamento / coordinación. Académico o personal de coordinación. */
    @GetMapping("/departamento/counts")
    fun contarDepartamento(
        @RequestParam(required = false) segmento: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        return ResponseEntity.ok(egresadoService.contarParaDepartamento(principal.username, segmento))
    }

    /** Lista para departamento / coordinación (Pendientes, Aprobados, Todos). Académico o coordinación. */
    @GetMapping("/departamento")
    fun listarDepartamento(
        @RequestParam(required = false, defaultValue = "pendientes") estado: String,
        @RequestParam(required = false) segmento: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        val lista = egresadoService.listarParaDepartamento(estado, principal.username, segmento)
        return ResponseEntity.ok(lista)
    }

    private fun parseFechaParam(s: String?, endOfDay: Boolean = false): java.time.Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            val date = java.time.LocalDate.parse(s)
            if (endOfDay) {
                date.atTime(23, 59, 59, 999_999_999).atOffset(java.time.ZoneOffset.UTC).toInstant()
            } else {
                date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Respaldo: obtener por número de control (evita 404 si el id en lista era de otra base). */
    @GetMapping("/por-numero/{numeroControl}")
    fun obtenerPorNumeroControl(@PathVariable numeroControl: String): ResponseEntity<*> {
        log.info("detalle-egresado: buscando por numero_control={}", numeroControl)
        val detalle = egresadoService.obtenerPorNumeroControl(numeroControl)
        log.info("detalle-egresado: resultado por numero_control={} encontrado={}", numeroControl, detalle != null)
        return if (detalle != null) ResponseEntity.ok(detalle)
        else ResponseEntity.notFound().build<Void>()
    }

    /** Seguimiento del egresado: devuelve su propio registro (por egresadoId o por número de control). */
    @GetMapping("/mi-seguimiento")
    fun miSeguimiento(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val numeroControl = principal.username.trim().ifBlank { null } ?: return ResponseEntity.notFound().build<Void>()
        val egresadoIdFromUsuario = principal.getEgresadoId()
        val detalle = if (egresadoIdFromUsuario != null) {
            egresadoService.obtenerPorEgresadoId(egresadoIdFromUsuario).also { d ->
                if (d != null) log.debug("mi-seguimiento: username={}, encontrado por egresadoId", numeroControl)
                else log.warn("mi-seguimiento: username={}, egresadoId={} no existe en registro", numeroControl, egresadoIdFromUsuario)
            }
        } else {
            egresadoService.obtenerPorNumeroControlParaSeguimiento(numeroControl).also { d ->
                if (d != null) {
                    log.debug("mi-seguimiento: username={}, encontrado por numero_control", numeroControl)
                    try {
                        usuarioService.crearOVincularUsuarioEgresado(numeroControl, ObjectId(d.id))
                        log.info("mi-seguimiento: usuario {} vinculado a egresado {} para próximas consultas", numeroControl, d.id)
                    } catch (e: Exception) {
                        log.warn("mi-seguimiento: no se pudo vincular egresadoId al usuario {}: {}", numeroControl, e.message)
                    }
                } else {
                    log.warn("mi-seguimiento: username={}, no hay egresadoId ni registro con numero_control (revisar BD)", numeroControl)
                }
            }
        }
        return if (detalle != null) ResponseEntity.ok(detalle)
        else ResponseEntity.notFound().build<Void>()
    }

    @GetMapping("/{id}")
    fun obtenerPorId(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "true") aplicar_scope_departamento: Boolean,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal != null) {
            val rolTokens = RolSoporte.tokensRol(principal.getRol())
            val esAcademico = rolTokens.contains("academico")
            // Coordinador / administrador (p. ej. rol compuesto) acceden sin restricción de carrera
            if (!RolSoporte.esCoordinadorOAdmin(principal.getRol())) {
                when {
                    esAcademico -> respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
                    puedeVerBandejaDepartamento(principal.getRol()) ->
                        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
                }
            }
        }
        log.info("detalle-egresado: buscando por id={}", id)
        val detalle = egresadoService.obtenerPorId(id)
        log.info("detalle-egresado: resultado por id={} encontrado={}", id, detalle != null)
        return if (detalle != null) ResponseEntity.ok(detalle)
        else ResponseEntity.notFound().build<Void>()
    }

    /** Descarga/visualiza el documento adjunto del egresado (PDF/Word). Académico o personal de coordinación. */
    @GetMapping("/{id}/documento")
    fun obtenerDocumento(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val doc = egresadoService.obtenerDocumentoAdjunto(id) ?: return ResponseEntity.notFound().build<Void>()
        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.fileName.replace("\"", "%22") + "\"")
        }
        val mediaType = try { MediaType.parseMediaType(doc.contentType) } catch (_: Exception) { MediaType.APPLICATION_OCTET_STREAM }
        return ResponseEntity.ok()
            .headers(headers)
            .contentType(mediaType)
            .body(InputStreamResource(doc.inputStream))
    }

    @GetMapping("/{id}/tesis-liberacion")
    fun obtenerTesisLiberacion(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val doc = egresadoService.obtenerTesisLiberacionAdjunto(id) ?: return ResponseEntity.notFound().build<Void>()
        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.fileName.replace("\"", "%22") + "\"")
        }
        val mediaType = try { MediaType.parseMediaType(doc.contentType) } catch (_: Exception) { MediaType.APPLICATION_OCTET_STREAM }
        return ResponseEntity.ok()
            .headers(headers)
            .contentType(mediaType)
            .body(InputStreamResource(doc.inputStream))
    }

    /**
     * Sustituye el PDF/Word adjunto al expediente. Académico o personal de coordinación.
     * Sin `consumes` estricto: algunos clientes envían `multipart/form-data` con parámetros extra en Content-Type
     * y Spring dejaba de emparejar el handler (404 "No static resource").
     */
    @PostMapping("/{id}/documento/reemplazar")
    fun reemplazarDocumentoAdjunto(
        @PathVariable id: String,
        @RequestParam("archivo") archivo: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        if (archivo.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Seleccione un archivo no vacío."))
        }
        return if (egresadoService.reemplazarDocumentoAdjunto(id, archivo)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "No se encontró el egresado."))
        }
    }

    /** Verifica si un título de proyecto choca con registros existentes. Requiere sesión. */
    @GetMapping("/verificar-originalidad")
    fun verificarOriginalidad(
        @RequestParam titulo: String,
        @RequestParam(required = false) excluirId: String? = null,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val resultado = originalidadService.verificar(titulo, excluirId)
        return ResponseEntity.ok(
            mapOf(
                "estado" to resultado.estado,
                "titulo_similar" to (resultado.tituloSimilar ?: ""),
                "expediente_estado" to (resultado.expedienteEstado ?: ""),
            ),
        )
    }

    /** Verifica si el número de control ya está registrado (mensajes de alta). */
    @GetMapping("/verificar-numero-control")
    fun verificarNumeroControlAlta(
        @RequestParam numero_control: String,
        @RequestParam(required = false) modalidad: String?,
        @RequestParam(required = false) excluirId: String?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val r = egresadoService.verificarDisponibilidadNumeroControlParaAlta(numero_control, excluirId, modalidad)
        return ResponseEntity.ok(
            mapOf(
                "estado" to r.estado,
                "expediente_estado" to (r.expedienteEstado ?: ""),
                "egresado_id" to (r.egresadoId ?: ""),
            ),
        )
    }

@PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun crear(
        @Valid @RequestPart("datos") datos: EgresadoRequestDto,
        @RequestPart(value = "archivo", required = false) archivo: MultipartFile? = null,
    ): ResponseEntity<*> {
        val errorControl = egresadoService.validarNuevaAltaNumeroControl(datos)
        if (errorControl != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to errorControl),
            )
        }
        val dupCtrl = egresadoService.verificarDisponibilidadNumeroControlParaAlta(datos.numero_control, null, datos.modalidad)
        if (dupCtrl.estado == "BLOQUEADO") {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to mensajeConflictoNumeroControlAlta(dupCtrl.expedienteEstado)),
            )
        }
        if (!usuarioService.puedeReutilizarUsernameParaEgresado(datos.numero_control)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "error" to
                        "Este número de control ya está en uso para inicio de sesión. No puedes registrar otro egresado con el mismo número.",
                ),
            )
        }
        val origResultado = originalidadService.verificar(datos.nombreProyecto ?: "")
        if (origResultado.estado == "BLOQUEADO" || origResultado.estado == "ADVERTENCIA") {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                EgresadoResponseDto(
                    id = "",
                    numero_control = datos.numero_control,
                    credenciales_enviadas_correo = false,
                    aviso_credenciales = if (origResultado.estado == "BLOQUEADO") {
                        "El título «${datos.nombreProyecto}» ya está registrado en el sistema y no puede usarse nuevamente."
                    } else {
                        "El título «${datos.nombreProyecto}» es demasiado similar a uno existente; debes usar un título distinto."
                    },
                ),
            )
        }
        val egresado = egresadoService.crear(datos, archivo)
        val idStr = egresado.id?.toString() ?: ""
        val correo = datos.correo_electronico?.trim().orEmpty()

        if (idStr.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                EgresadoResponseDto(
                    id = "",
                    numero_control = egresado.numero_control,
                    credenciales_enviadas_correo = false,
                    aviso_credenciales = "Registro guardado sin identificador; revise la base de datos.",
                ),
            )
        }

        val oid = ObjectId(idStr)
        val (user, passwordPlana) = try {
            usuarioService.crearOVincularUsuarioEgresado(egresado.numero_control.trim(), oid)
        } catch (e: Exception) {
            log.error("Error al crear usuario egresado para id={}: {}", idStr, e.message, e)
            return ResponseEntity.status(HttpStatus.CREATED).body(
                EgresadoResponseDto(
                    id = idStr,
                    numero_control = egresado.numero_control,
                    credenciales_enviadas_correo = false,
                    aviso_credenciales =
                        "Egresado registrado. Error al crear usuario: ${e.message ?: "desconocido"}.",
                ),
            )
        }

        var credencialesOk: Boolean?
        var aviso: String?
        if (correo.isBlank()) {
            credencialesOk = false
            aviso =
                "Usuario creado (inicio de sesión: número de control). No se envió correo: falta correo electrónico en el registro."
        } else {
            try {
                val enviado = emailService.enviarCredenciales(correo, user, passwordPlana)
                if (enviado) {
                    credencialesOk = true
                    aviso = null
                } else {
                    credencialesOk = false
                    aviso =
                        "Usuario creado; no se envió correo (revise correo en el registro y configuración SMTP del servidor)."
                }
            } catch (e: Exception) {
                log.error("No se pudo enviar correo de credenciales a {}: {}", correo, e.message, e)
                credencialesOk = false
                aviso =
                    "Usuario creado; no se pudo enviar el correo (${e.message ?: "error SMTP"}). Comunique la contraseña por otro medio o reintente."
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            EgresadoResponseDto(
                id = idStr,
                numero_control = egresado.numero_control,
                credenciales_enviadas_correo = credencialesOk,
                aviso_credenciales = aviso,
            ),
        )
    }

    @PostMapping("/{id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun actualizar(
        @PathVariable id: String,
        @Valid @RequestPart("datos") datos: EgresadoRequestDto,
        @RequestPart(value = "archivo", required = false) archivo: MultipartFile? = null,
    ): ResponseEntity<*> {
        return if (egresadoService.actualizar(id, datos, archivo)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.notFound().build<Void>()
        }
    }

    @PostMapping("/eliminar/{id}")
    fun eliminar(@PathVariable id: String): ResponseEntity<*> {
        return if (egresadoService.eliminar(id)) {
            ResponseEntity.noContent().build<Void>()
        } else {
            ResponseEntity.notFound().build<Void>()
        }
    }

    /**
     * Abre un nuevo proceso de titulación para un egresado cuyo proceso anterior venció o se tituló.
     * El proceso anterior queda en el historial. Solo el coordinador puede invocar esto.
     */
    @PostMapping("/{id}/nuevo-proceso", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun activarNuevoProceso(
        @PathVariable id: String,
        @Valid @RequestPart("datos") datos: EgresadoRequestDto,
        @RequestPart(value = "archivo", required = false) archivo: MultipartFile? = null,
    ): ResponseEntity<*> {
        val error = egresadoService.activarNuevoProceso(id, datos, archivo)
        return if (error == null) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.badRequest().body(mapOf("error" to error))
        }
    }

    /** Marca el egresado como "Enviado al departamento académico" (paso 1.1 del seguimiento). */
    @PostMapping("/{id}/enviar-departamento-academico")
    fun enviarDepartamentoAcademico(@PathVariable id: String): ResponseEntity<*> {
        return if (egresadoService.marcarEnviadoDepartamentoAcademico(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.notFound().build<Void>()
        }
    }

    /** Dashboard departamento: marca el anteproyecto como registrado/acusado en la bandeja. Independiente del flujo de seguimiento. */
    @PostMapping("/{id}/departamento/marcar-registrado")
    fun marcarRegistradoDepartamento(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.marcarRegistradoDepartamento(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(mapOf("error" to "No se pudo marcar como registrado."))

    /** No residencia paso 1: DEP confirma entrega del egresado (anexo XXXI y anteproyecto). */
    @PostMapping("/{id}/no-residencia/confirmar-entrega-egresado-depto")
    fun confirmarEntregaEgresadoDeptoNoResidencia(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.confirmarEntregaEgresadoDeptoNoResidencia(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(
            mapOf("error" to "No se pudo confirmar. Solo no residencia y debe estar pendiente el paso 1."),
        )

    /** No residencia paso 2: envío de anteproyecto y solicitud de registro (anexo XXXII) al departamento académico. */
    @PostMapping("/{id}/no-residencia/solicitar-registro-anteproyecto")
    fun solicitarRegistroAnteproyectoNoResidencia(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.solicitarRegistroAnteproyectoNoResidencia(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(mapOf("error" to "No se pudo registrar el envío. Confirma antes la entrega del egresado (paso 1)."))

    @PostMapping("/{id}/no-residencia/confirmar-recepcion-inicial-anexos-xxxi-xxxii")
    fun confirmarRecepcionInicialAnexosXxxiXxxiiNoResidencia(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.confirmarRecepcionInicialAnexosXxxiXxxiiNoResidencia(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(
            mapOf("error" to "No se pudo confirmar. Requiere envío de anteproyecto previo y no aplica a residencia."),
        )

    @PostMapping("/{id}/no-residencia/confirmar-recepcion-trabajo-division")
    fun confirmarRecepcionTrabajoNoResidencia(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.confirmarRecepcionTrabajoNoResidencia(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(mapOf("error" to "No se pudo confirmar. Confirma antes la recepción inicial de anexos XXXI/XXXII (flujo 16) o revisa que no sea residencia."))

    /** No residencia paso 5: departamento académico sube tesis y libera producto. */
    @PostMapping(value = ["/{id}/no-residencia/liberar-producto"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun liberarProductoNoResidencia(
        @PathVariable id: String,
        @RequestParam("archivo") archivo: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (archivo.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Selecciona el PDF de la tesis."))
        }
        val err = egresadoService.liberarProductoNoResidencia(id, archivo)
        return if (err == null) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(mapOf("error" to err))
    }

    @PostMapping("/{id}/no-residencia/confirmar-recepcion-registro-liberacion-depto")
    fun confirmarRecepcionRegistroLiberacionNoResidencia(@PathVariable id: String): ResponseEntity<*> =
        if (egresadoService.confirmarRecepcionRegistroLiberacionNoResidencia(id)) ResponseEntity.ok().build<Void>()
        else ResponseEntity.badRequest().body(mapOf("error" to "No se pudo confirmar la recepción de registro y liberación."))

    /** Marca "recibido registro y liberación" desde departamento académico (solo Residencia). */
    @PostMapping("/{id}/liberar")
    fun liberar(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        return if (egresadoService.liberar(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo liberar. Solo aplica a Residencia Profesional enviada al departamento y no liberada previamente."),
            )
        }
    }

    @PostMapping("/{id}/confirmar-recibidos-anexo-xxxi-xxxii")
    fun confirmarRecibidosAnexoXxxiXxxii(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarRecibidosAnexoXxxiXxxii(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "No se pudo confirmar recibidos XXXI/XXXII para este registro."))
        }
    }

    @GetMapping("/{id}/anexo-9-1")
    fun descargarAnexo91(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        val bytes = egresadoService.crearAnexo91(id)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo generar el PDF del anexo 9.1 (plantilla HTML del sistema). Revisa los logs del servidor."),
            )
        val fileName = "Anexo-9.1-$id.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .body(bytes)
    }

    @PostMapping("/{id}/confirmar-entrega-anexo-9-1")
    fun confirmarEntregaAnexo91(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarEntregaAnexo91(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "No se pudo confirmar entrega 9.1."))
        }
    }

    /** División: solicita al egresado la constancia 9.2 (sin generar PDF aquí). */
    @PostMapping("/{id}/solicitar-constancia-9-2-division")
    fun solicitarConstancia92Division(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.solicitarConstancia92Division(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo registrar la solicitud 9.2 (revisa entrega 9.1 o que no esté duplicada)."),
            )
        }
    }

    @GetMapping("/{id}/anexo-9-2")
    fun descargarAnexo92(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        val bytes = egresadoService.crearAnexo92(id)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf(
                    "error" to
                        "No se pudo generar el 9.2: primero división debe usar «Solicitar constancia 9.2», " +
                        "o falló LibreOffice/plantilla.",
                ),
            )
        val fileName = "Anexo-9.2-$id.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .body(bytes)
    }

    @PostMapping("/{id}/confirmar-recibido-anexo-9-2")
    fun confirmarRecibidoAnexo92(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarRecibidoAnexo92(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo confirmar: falta la solicitud de división para la 9.2 o ya estaba confirmado."),
            )
        }
    }

    @PostMapping("/{id}/solicitar-sinodales")
    fun solicitarSinodales(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.solicitarSinodales(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "No se pudo solicitar sinodales."))
        }
    }

    @GetMapping("/{id}/sinodales")
    fun getSinodales(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val s = egresadoService.obtenerSinodales(id)
        return ResponseEntity.ok(
            SinodalesRespuestaDto(
                presidente = s?.presidente ?: "",
                secretario = s?.secretario ?: "",
                vocal = s?.vocal ?: "",
                vocalSuplente = s?.vocal_suplente ?: "",
            ),
        )
    }

    @PostMapping("/{id}/sinodales", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun asignarSinodales(
        @PathVariable id: String,
        @RequestBody body: AsignarSinodalesRequestDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        return if (egresadoService.asignarSinodales(id, body.presidente, body.secretario, body.vocal, body.vocalSuplente)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "No se pudieron guardar los sinodales."))
        }
    }

    @PostMapping("/{id}/confirmar-sinodales-recibidos")
    fun confirmarSinodalesRecibidos(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarSinodalesRecibidos(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "No se pudo confirmar sinodales recibidos."))
        }
    }

    /** Lista revisiones del egresado. Académico o personal de coordinación. */
    @GetMapping("/{id}/revisiones")
    fun listarRevisiones(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val lista = revisionService.listarPorEgresado(id)
        return ResponseEntity.ok(lista)
    }

    /** Envía una revisión al egresado para que pueda verla en su seguimiento (solo revisiones con observaciones). */
    @PostMapping("/{id}/revisiones/{revisionId}/enviar")
    fun enviarRevisionAlEgresado(
        @PathVariable id: String,
        @PathVariable revisionId: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val enviada = revisionService.enviarRevisionAlEgresado(id, revisionId)
        return if (enviada != null) ResponseEntity.ok(enviada)
        else ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "No se pudo enviar la revisión. Verifica que sea una revisión con observaciones válida."))
    }

    /** Crea una revisión (Enviar revisión con observaciones). Académico o personal de coordinación. */
    @PostMapping(value = ["/{id}/revisiones"], consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun crearRevision(
        @PathVariable id: String,
        @RequestBody body: CreateRevisionRequestDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val creada = revisionService.crear(id, body, principal.getRol())
        return if (creada != null) ResponseEntity.status(HttpStatus.CREATED).body(creada)
        else ResponseEntity.notFound().build<Void>()
    }

    /** Egresado sube su PDF final (9.1 + 9.2 + 9.3) para concluir la titulación. */
    @PostMapping("/mi-seguimiento/documento-final")
    fun subirDocumentoFinal(
        @RequestParam("archivo") archivo: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val numeroControl = principal.username.trim().ifBlank { null }
            ?: return ResponseEntity.notFound().build<Void>()
        if (archivo.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Selecciona un archivo PDF no vacío."))
        }
        return try {
            if (egresadoService.subirDocumentoFinal(numeroControl, archivo)) {
                ResponseEntity.ok().build<Void>()
            } else {
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    mapOf("error" to "No se pudo registrar la titulación. Verifica que el proceso esté completo o que no hayas subido el documento previamente."),
                )
            }
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Archivo no válido.")))
        }
    }

    /** Crea revisión con adjunto PDF opcional en multipart/form-data. */
    @PostMapping(value = ["/{id}/revisiones"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun crearRevisionMultipart(
        @PathVariable id: String,
        @RequestParam resultado: String,
        @RequestParam(required = false) observaciones: String?,
        @RequestPart(required = false) archivo: MultipartFile?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val creada = revisionService.crear(
            id,
            CreateRevisionRequestDto(resultado = resultado, observaciones = observaciones),
            principal.getRol(),
            archivo,
        )
        return if (creada != null) {
            ResponseEntity.status(HttpStatus.CREATED).body(creada)
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "No se pudo crear la revisión. Si adjuntas archivo, debe ser PDF válido."))
        }
    }

    /** Descarga documento PDF adjunto de una revisión (vista coordinación/académico). */
    @GetMapping("/{id}/revisiones/{revisionId}/documento")
    fun descargarDocumentoRevision(
        @PathVariable id: String,
        @PathVariable revisionId: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null || !puedeVerBandejaDepartamento(principal.getRol())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        }
        respuestaSiNoAccesoEgresadoBandeja(id, principal)?.let { return it }
        val doc = revisionService.obtenerDocumentoAdjunto(id, revisionId, requiereEnviadaEgresado = false)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "La revisión no tiene documento adjunto."))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${doc.fileName}\"")
            .body(doc.bytes)
    }

    /** Seguimiento del egresado: revisiones que ya fueron enviadas para corrección. */
    @GetMapping("/mi-seguimiento/revisiones")
    fun miSeguimientoRevisiones(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val egresadoId = resolverEgresadoIdParaMiSeguimiento(principal) ?: return ResponseEntity.notFound().build<Void>()
        return ResponseEntity.ok(revisionService.listarEnviadasAlEgresado(egresadoId))
    }

    /** Descarga adjunto PDF de una revisión enviada al egresado en su seguimiento. */
    @GetMapping("/mi-seguimiento/revisiones/{revisionId}/documento")
    fun miSeguimientoDescargarDocumentoRevision(
        @PathVariable revisionId: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val egresadoId = resolverEgresadoIdParaMiSeguimiento(principal) ?: return ResponseEntity.notFound().build<Void>()
        val doc = revisionService.obtenerDocumentoAdjunto(egresadoId, revisionId, requiereEnviadaEgresado = true)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "No hay documento adjunto para esta revisión."))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${doc.fileName}\"")
            .body(doc.bytes)
    }

    /** Egresado: sube expediente corregido (PDF o .docx) y sustituye el archivo que ve Coordinación de Apoyo a la Titulación. */
    @PostMapping("/mi-seguimiento/documento-expediente/reemplazar")
    fun miSeguimientoReemplazarDocumentoExpediente(
        @RequestParam("archivo") archivo: MultipartFile,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!esRolEgresado(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                mapOf("error" to "Solo el egresado puede subir el expediente corregido desde el seguimiento."),
            )
        }
        val egresadoId = resolverEgresadoIdParaMiSeguimiento(principal)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "No se encontró tu registro de titulación."))
        if (archivo.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Selecciona un archivo no vacío."))
        }
        val err = egresadoService.reemplazarDocumentoExpedienteEgresadoTrasRevision(egresadoId, archivo)
        return if (err == null) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to err))
        }
    }

    @PostMapping("/{id}/agendar-acto-9-3", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun agendarActo93(
        @PathVariable id: String,
        @RequestBody body: AgendarActoRequestDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.agendarActo93(id, body.fecha_hora)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "No se pudo agendar: fecha/hora fuera del horario permitido (L-V 10:00-14:00) o el intervalo ya está ocupado."))
        }
    }

    @GetMapping("/agenda-acto-9-3/ocupados")
    fun listarAgendaActo93Ocupados(@AuthenticationPrincipal principal: UsuarioPrincipal?): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        val fechas = egresadoService.listarActo93Ocupados().map { DateTimeFormatter.ISO_INSTANT.format(it) }
        return ResponseEntity.ok(mapOf("ocupados" to fechas))
    }

    @GetMapping("/{id}/anexo-9-3")
    fun descargarAnexo93(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        val bytes = egresadoService.crearAnexo93(id)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo generar el PDF del anexo 9.3 (plantilla HTML del sistema). Revisa los logs del servidor."),
            )
        val fileName = "Anexo-9.3-$id.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .body(bytes)
    }

    @PostMapping("/{id}/confirmar-entrega-anexo-9-3")
    fun confirmarEntregaAnexo93(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarEntregaAnexo93(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo confirmar: primero debe generarse el anexo 9.3 o ya estaba confirmada la entrega."),
            )
        }
    }

    @PostMapping("/{id}/solicitar-documentacion-escaneada")
    fun solicitarDocumentacionEscaneada(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.solicitarDocumentacionEscaneada(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo registrar la solicitud. Revisa que el anexo 9.3 esté listo (y la entrega 9.3 en residencia) y que no exista una solicitud previa."),
            )
        }
    }

    @PostMapping("/{id}/confirmar-documentacion-escaneada-recibida")
    fun confirmarDocumentacionEscaneadaRecibida(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.confirmarDocumentacionEscaneadaRecibida(id)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo confirmar: el egresado debe haber enviado sus PDF o ya estaba confirmada la recepción."),
            )
        }
    }

    @GetMapping("/{id}/documentacion-escaneada")
    fun obtenerDocumentacionEscaneada(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        val doc = egresadoService.obtenerDocumentacionEscaneadaBytes(id) ?: return ResponseEntity.notFound().build<Void>()
        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.fileName.replace("\"", "%22") + "\"")
        }
        val mediaType = try { MediaType.parseMediaType(doc.contentType) } catch (_: Exception) { MediaType.APPLICATION_PDF }
        return ResponseEntity.ok()
            .headers(headers)
            .contentType(mediaType)
            .contentLength(doc.bytes.size.toLong())
            .body(doc.bytes)
    }

    @PostMapping("/{id}/solicitar-documentacion-escaneada-nuevamente", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun solicitarDocumentacionEscaneadaNuevamente(
        @PathVariable id: String,
        @RequestBody body: SolicitarReenvioDocumentacionEscaneadaRequestDto,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        respuestaSiAcademicoSinCarrera(id, principal)?.let { return it }
        return if (egresadoService.solicitarDocumentacionEscaneadaNuevamente(id, body.observaciones)) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                mapOf("error" to "No se pudo solicitar nuevamente. Verifica que el egresado ya haya enviado PDF y que aún no esté confirmado."),
            )
        }
    }

    @PostMapping("/mi-seguimiento/documentacion-escaneada", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun miSeguimientoSubirDocumentacionEscaneada(
        @RequestPart("archivos") archivos: List<MultipartFile>?,
        @AuthenticationPrincipal principal: UsuarioPrincipal?,
    ): ResponseEntity<*> {
        if (principal == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build<Void>()
        if (!esRolEgresado(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                mapOf("error" to "Solo el egresado puede subir documentación desde el seguimiento."),
            )
        }
        val egresadoId = resolverEgresadoIdParaMiSeguimiento(principal)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "No se encontró tu registro de titulación."))
        val err = egresadoService.subirDocumentacionEscaneadaEgresado(egresadoId, archivos)
        return if (err == null) {
            ResponseEntity.ok().build<Void>()
        } else {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to err))
        }
    }

    private fun esRolEgresado(principal: UsuarioPrincipal): Boolean {
        val r = principal.getRol().trim().lowercase().replace(' ', '_')
        return r == "egresado"
    }

    /**
     * Misma idea que el front `AuthService.isCoordinador()` + académicos:
     * pueden ver la bandeja de revisión / coordinación y listados de departamento.
     */
    private fun puedeVerBandejaDepartamento(rol: String?): Boolean {
        if (rol.isNullOrBlank()) return false
        return RolSoporte.tieneAlgunRol(
            rol,
            "academico",
            "coordinador",
            "apoyo_titulacion",
            "division_estudios_prof_admin",
            "administrador",
        )
    }

    /** Académico con carreras asignadas no puede abrir expedientes de otras carreras. */
    private fun respuestaSiAcademicoSinCarrera(id: String, principal: UsuarioPrincipal?): ResponseEntity<*>? {
        if (principal == null) return null
        if (principal.getRol().trim().lowercase() != "academico") return null
        return respuestaSiNoAccesoEgresadoBandeja(id, principal)
    }

    private fun mensajeConflictoNumeroControlAlta(expedienteEstado: String?): String =
        when (expedienteEstado?.trim()) {
            "vencido" -> "Este número de control ya está registrado; el expediente ya venció. No puedes duplicarlo."
            "titulado" -> "Este número de control ya fue usado en un expediente titulado."
            else -> "Este número de control ya está registrado; el expediente se encuentra en proceso."
        }

    /** Coordinación / misma regla que la bandeja: residencia excluida donde aplica; carrera solo para académicos segmentados. */
    private fun respuestaSiNoAccesoEgresadoBandeja(id: String, principal: UsuarioPrincipal): ResponseEntity<*>? =
        if (!egresadoService.academicoPuedeAccederAEgresado(principal.username, id)) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "No autorizado para este egresado."))
        } else {
            null
        }

    /** Obtiene el id de egresado para el usuario logueado en seguimiento (vínculo o número de control). */
    private fun resolverEgresadoIdParaMiSeguimiento(principal: UsuarioPrincipal): String? {
        val numeroControl = principal.username.trim().ifBlank { return null }
        val egresadoIdFromUsuario = principal.getEgresadoId()
        val detalle = if (egresadoIdFromUsuario != null) {
            egresadoService.obtenerPorEgresadoId(egresadoIdFromUsuario).also { d ->
                if (d != null) log.debug("mi-seguimiento-revisiones: username={}, encontrado por egresadoId", numeroControl)
                else log.warn("mi-seguimiento-revisiones: username={}, egresadoId={} no existe en registro", numeroControl, egresadoIdFromUsuario)
            }
        } else {
            egresadoService.obtenerPorNumeroControlParaSeguimiento(numeroControl).also { d ->
                if (d != null) {
                    log.debug("mi-seguimiento-revisiones: username={}, encontrado por numero_control", numeroControl)
                    try {
                        usuarioService.crearOVincularUsuarioEgresado(numeroControl, ObjectId(d.id))
                        log.info("mi-seguimiento-revisiones: usuario {} vinculado a egresado {}", numeroControl, d.id)
                    } catch (e: Exception) {
                        log.warn("mi-seguimiento-revisiones: no se pudo vincular egresadoId al usuario {}: {}", numeroControl, e.message)
                    }
                } else {
                    log.warn("mi-seguimiento-revisiones: username={}, no hay egresado asociado", numeroControl)
                }
            }
        }
        return detalle?.id
    }
}
