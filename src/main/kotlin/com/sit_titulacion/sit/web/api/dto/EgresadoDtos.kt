package com.sit_titulacion.sit.web.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** DTOs de la API de egresados (entrada, respuesta, lista y detalle). */

data class EgresadoRequestDto(
    @field:NotBlank @field:Size(max = 20)
    val numero_control: String,
    @field:NotBlank @field:Size(max = 100)
    val nombre: String,
    @JsonProperty("apellido_paterno") @field:NotBlank @field:Size(max = 100)
    val apellidoPaterno: String,
    @JsonProperty("apellido_materno") @field:NotBlank @field:Size(max = 100)
    val apellidoMaterno: String,
    @field:NotBlank @field:Size(max = 150)
    val carrera: String,
    @field:NotBlank @field:Size(max = 50)
    val nivel: String,
    @field:Size(max = 200) val direccion: String? = null,
    @field:Size(max = 20)  val telefono: String? = null,
    /** Mismo nombre que en JSON (snake_case) para que Jackson lo deserialice bien en multipart. */
    @field:Size(max = 150) val correo_electronico: String? = null,
    @JsonProperty("nombre_proyecto") @field:Size(max = 300) val nombreProyecto: String? = null,
    @field:NotBlank @field:Size(max = 100)
    val modalidad: String,
    @JsonProperty("curso_titulacion")  @field:Size(max = 100) val cursoTitulacion: String? = null,
    @JsonProperty("asesor_interno")    @field:Size(max = 150) val asesorInterno: String? = null,
    @JsonProperty("asesor_externo")    @field:Size(max = 150) val asesorExterno: String? = null,
    @field:Size(max = 150) val director: String? = null,
    @JsonProperty("asesor_1") @field:Size(max = 150) val asesor1: String? = null,
    @JsonProperty("asesor_2") @field:Size(max = 150) val asesor2: String? = null,
    @JsonProperty("fecha_registro_anexo")        @field:Size(max = 20) val fechaRegistroAnexo: String? = null,
    @JsonProperty("fecha_expedicion_constancia") @field:Size(max = 20) val fechaExpedicionConstancia: String? = null,
    @field:Size(max = 2000) val observaciones: String? = null,
    @JsonProperty("quitar_archivo") val quitarArchivo: Boolean? = null,
)

data class EgresadoResponseDto(
    val id: String,
    val numero_control: String,
    /** true = correo enviado; false = no enviado (motivo en aviso_credenciales); null = no aplica (solo en alta). */
    @JsonProperty("credenciales_enviadas_correo") val credenciales_enviadas_correo: Boolean? = null,
    @JsonProperty("aviso_credenciales") val aviso_credenciales: String? = null,
)

data class EgresadoListItemDto(
    val id: String,
    val numero_control: String,
    val nombre: String,
    val carrera: String,
    val modalidad: String,
    @JsonProperty("fecha_creacion") val fecha_creacion: String?,
    @JsonProperty("fecha_enviado_departamento_academico") val fecha_enviado_departamento_academico: String?,
    @JsonProperty("fecha_recibido_registro_liberacion") val fecha_recibido_registro_liberacion: String? = null,
    @JsonProperty("fecha_confirmacion_recibidos_anexo_xxxi_xxxii") val fecha_confirmacion_recibidos_anexo_xxxi_xxxii: String? = null,
    @JsonProperty("fecha_actualizacion") val fecha_actualizacion: String?,
    @JsonProperty("fecha_creacion_anexo_9_3") val fecha_creacion_anexo_9_3: String?,
    @JsonProperty("fecha_confirmacion_entrega_anexo_9_3") val fecha_confirmacion_entrega_anexo_9_3: String? = null,
    @JsonProperty("fecha_solicitud_documentacion_escaneada") val fecha_solicitud_documentacion_escaneada: String? = null,
    @JsonProperty("fecha_envio_documentacion_escaneada_egresado") val fecha_envio_documentacion_escaneada_egresado: String? = null,
    @JsonProperty("fecha_confirmacion_documentacion_escaneada_recibida") val fecha_confirmacion_documentacion_escaneada_recibida: String? = null,
)

/** Item para la lista del departamento académico (Pendientes, En corrección, Aprobados, etc.). */
data class DepartamentoListItemDto(
    val id: String,
    @JsonProperty("nombre") val nombre: String,
    @JsonProperty("numero_control") val numeroControl: String,
    val modalidad: String,
    @JsonProperty("fecha_actualizacion") val fechaActualizacion: String?,
    @JsonProperty("fecha_enviado_departamento_academico") val fechaEnviadoDepartamento: String?,
    /** "pendiente" | "con_observaciones" | "aprobado" para mostrar badge en la lista. */
    @JsonProperty("estado_revision") val estadoRevision: String = "pendiente",
    /** Solo pestaña Sinodales: cuándo el egresado solicitó tribunal. */
    @JsonProperty("fecha_solicitud_sinodales") val fechaSolicitudSinodales: String? = null,
    /** true si el departamento ya guardó la asignación (fecha_asignacion_sinodales). */
    @JsonProperty("sinodales_asignados") val sinodalesAsignados: Boolean = false,
    /** Pestaña Anteproyecto: fecha en que DEP envió anteproyecto + Anexo XXXI al departamento. */
    @JsonProperty("fecha_envio_anteproyecto_depto") val fechaEnvioAnteproyectoDepto: String? = null,
    /** Pestaña Anteproyecto: fecha en que se marcó como registrado en la bandeja del departamento. */
    @JsonProperty("fecha_registrado_departamento") val fechaRegistradoDepartamento: String? = null,
)

/** Cuerpo para asignar o actualizar sinodales (departamento académico). */
data class AsignarSinodalesRequestDto(
    @JsonProperty("presidente") val presidente: String = "",
    @JsonProperty("secretario") val secretario: String = "",
    @JsonProperty("vocal") val vocal: String = "",
    @JsonProperty("vocal_suplente") val vocalSuplente: String = "",
)

/** Respuesta GET/POST sinodales (no se incluye en detalle de seguimiento del egresado). */
data class SinodalesRespuestaDto(
    @JsonProperty("fecha_asignacion_sinodales") val fechaAsignacionSinodales: String? = null,
    @JsonProperty("presidente") val presidente: String = "",
    @JsonProperty("secretario") val secretario: String = "",
    @JsonProperty("vocal") val vocal: String = "",
    @JsonProperty("vocal_suplente") val vocalSuplente: String = "",
)

/** Revisión guardada (respuesta de listar y al crear). */
data class RevisionDto(
    val id: String,
    @JsonProperty("egresado_id") val egresadoId: String,
    @JsonProperty("numero_revision") val numeroRevision: Int,
    val fecha: String,
    @JsonProperty("revisado_por") val revisadoPor: String,
    val resultado: String,
    val observaciones: String? = null,
    @JsonProperty("enviado_al_egresado") val enviadoAlEgresado: Boolean = false,
    @JsonProperty("fecha_envio_egresado") val fechaEnvioEgresado: String? = null,
    @JsonProperty("tiene_documento_adjunto") val tieneDocumentoAdjunto: Boolean = false,
    @JsonProperty("documento_nombre") val documentoNombre: String? = null,
    @JsonProperty("documento_content_type") val documentoContentType: String? = null,
    @JsonProperty("documento_tamanio_bytes") val documentoTamanioBytes: Long? = null,
)

/** Cuerpo para crear una revisión (Enviar revisión con observaciones). */
data class CreateRevisionRequestDto(
    val resultado: String, // "observaciones" | "aprobado"
    val observaciones: String? = null,
)

data class SolicitarReenvioDocumentacionEscaneadaRequestDto(
    val observaciones: String? = null,
)

data class EgresadoDetailDto(
    val id: String,
    val numero_control: String,
    val datos_personales: DatosPersonalesDto,
    val datos_proyecto: DatosProyectoDto,
    val documentos: DocumentosDto,
    @JsonProperty("documento_adjunto") val documento_adjunto: DocumentoAdjuntoDto? = null,
    val estado_general: String,
    val fecha_creacion: String?,
    val fecha_actualizacion: String?,
    /** ID del proceso de titulación activo (último en la lista). */
    @JsonProperty("proceso_id") val proceso_id: String? = null,
    /** Cuántos procesos de titulación tiene el egresado (1 en primera solicitud, 2+ si reintentó). */
    @JsonProperty("total_procesos") val total_procesos: Int = 1,
    /** Resumen de procesos anteriores (todos excepto el activo). */
    @JsonProperty("procesos_anteriores") val procesos_anteriores: List<ProcesoAnteriorDto> = emptyList(),
    @JsonProperty("fecha_envio_solicitud_registro_anteproyecto_depto_academico")
    val fecha_envio_solicitud_registro_anteproyecto_depto_academico: String? = null,
    /** Marca del departamento académico (bandeja Anteproyecto); requisito para que la DEP confirme el paso 2 en no residencia. */
    @JsonProperty("fecha_registrado_departamento") val fecha_registrado_departamento: String? = null,
    @JsonProperty("fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii")
    val fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii: String? = null,
    @JsonProperty("fecha_recepcion_trabajo_division_estudios_prof")
    val fecha_recepcion_trabajo_division_estudios_prof: String? = null,
    @JsonProperty("fecha_solicitud_registro_liberacion_depto_academico")
    val fecha_solicitud_registro_liberacion_depto_academico: String? = null,
    @JsonProperty("fecha_recepcion_registro_liberacion_depto_academico")
    val fecha_recepcion_registro_liberacion_depto_academico: String? = null,
    @JsonProperty("fecha_liberacion_documento_coordinacion_cat")
    val fecha_liberacion_documento_coordinacion_cat: String? = null,
    @JsonProperty("fecha_enviado_departamento_academico") val fecha_enviado_departamento_academico: String? = null,
    @JsonProperty("fecha_recibido_registro_liberacion") val fecha_recibido_registro_liberacion: String? = null,
    @JsonProperty("fecha_confirmacion_recibidos_anexo_xxxi_xxxii") val fecha_confirmacion_recibidos_anexo_xxxi_xxxii: String? = null,
    @JsonProperty("fecha_creacion_anexo_9_1") val fecha_creacion_anexo_9_1: String? = null,
    @JsonProperty("fecha_confirmacion_entrega_anexo_9_1") val fecha_confirmacion_entrega_anexo_9_1: String? = null,
    @JsonProperty("fecha_solicitud_anexo_9_2") val fecha_solicitud_anexo_9_2: String? = null,
    @JsonProperty("fecha_creacion_anexo_9_2") val fecha_creacion_anexo_9_2: String? = null,
    @JsonProperty("fecha_confirmacion_recibido_anexo_9_2") val fecha_confirmacion_recibido_anexo_9_2: String? = null,
    @JsonProperty("fecha_solicitud_sinodales") val fecha_solicitud_sinodales: String? = null,
    /** Cuando el departamento académico guardó Presidente/Secretario/Vocal/Vocal suplente (habilita confirmar recibidos). */
    @JsonProperty("fecha_asignacion_sinodales") val fecha_asignacion_sinodales: String? = null,
    @JsonProperty("fecha_confirmacion_sinodales_recibidos") val fecha_confirmacion_sinodales_recibidos: String? = null,
    @JsonProperty("fecha_agenda_acto_9_3") val fecha_agenda_acto_9_3: String? = null,
    @JsonProperty("fecha_reagenda_acto_9_3") val fecha_reagenda_acto_9_3: String? = null,
    @JsonProperty("fecha_creacion_anexo_9_3") val fecha_creacion_anexo_9_3: String? = null,
    @JsonProperty("fecha_confirmacion_entrega_anexo_9_3") val fecha_confirmacion_entrega_anexo_9_3: String? = null,
    @JsonProperty("fecha_titulacion") val fecha_titulacion: String? = null,
    @JsonProperty("tiene_doc_final") val tiene_doc_final: Boolean = false,
    @JsonProperty("fecha_solicitud_documentacion_escaneada") val fecha_solicitud_documentacion_escaneada: String? = null,
    @JsonProperty("fecha_envio_documentacion_escaneada_egresado") val fecha_envio_documentacion_escaneada_egresado: String? = null,
    @JsonProperty("fecha_confirmacion_documentacion_escaneada_recibida") val fecha_confirmacion_documentacion_escaneada_recibida: String? = null,
    @JsonProperty("fecha_solicitud_reenvio_documentacion_escaneada")
    val fecha_solicitud_reenvio_documentacion_escaneada: String? = null,
    @JsonProperty("observaciones_reenvio_documentacion_escaneada")
    val observaciones_reenvio_documentacion_escaneada: String? = null,
)

data class ProcesoAnteriorDto(
    @JsonProperty("proceso_id") val procesoId: String,
    val modalidad: String,
    @JsonProperty("nombre_proyecto") val nombreProyecto: String,
    val estado: String,
    @JsonProperty("fecha_creacion") val fechaCreacion: String,
    @JsonProperty("fecha_cierre") val fechaCierre: String?,
    @JsonProperty("fecha_enviado_departamento_academico") val fechaEnviadoDepartamentoAcademico: String? = null,
    @JsonProperty("fecha_recibido_registro_liberacion") val fechaRecibidoRegistroLiberacion: String? = null,
    @JsonProperty("fecha_confirmacion_recibidos_anexo_xxxi_xxxii") val fechaConfirmacionRecibidosAnexoXxxiXxxii: String? = null,
    @JsonProperty("fecha_liberacion_documento_coordinacion_cat") val fechaLiberacionDocumentoCoordinacionCat: String? = null,
    @JsonProperty("fecha_envio_solicitud_registro_anteproyecto_depto_academico") val fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico: String? = null,
    @JsonProperty("fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii")
    val fechaConfirmacionRecepcionInicialAnexosXxxiXxxii: String? = null,
    @JsonProperty("fecha_creacion_anexo_9_1") val fechaCreacionAnexo91: String? = null,
    @JsonProperty("fecha_confirmacion_entrega_anexo_9_1") val fechaConfirmacionEntregaAnexo91: String? = null,
    @JsonProperty("fecha_solicitud_anexo_9_2") val fechaSolicitudAnexo92: String? = null,
    @JsonProperty("fecha_confirmacion_recibido_anexo_9_2") val fechaConfirmacionRecibidoAnexo92: String? = null,
    @JsonProperty("fecha_solicitud_sinodales") val fechaSolicitudSinodales: String? = null,
    @JsonProperty("fecha_confirmacion_sinodales_recibidos") val fechaConfirmacionSinodalesRecibidos: String? = null,
    @JsonProperty("fecha_agenda_acto_9_3") val fechaAgendaActo93: String? = null,
    @JsonProperty("fecha_creacion_anexo_9_3") val fechaCreacionAnexo93: String? = null,
    @JsonProperty("fecha_confirmacion_entrega_anexo_9_3") val fechaConfirmacionEntregaAnexo93: String? = null,
    @JsonProperty("fecha_solicitud_documentacion_escaneada") val fechaSolicitudDocumentacionEscaneada: String? = null,
    @JsonProperty("fecha_envio_documentacion_escaneada_egresado") val fechaEnvioDocumentacionEscaneadaEgresado: String? = null,
    @JsonProperty("fecha_confirmacion_documentacion_escaneada_recibida") val fechaConfirmacionDocumentacionEscaneadaRecibida: String? = null,
    @JsonProperty("fecha_titulacion") val fechaTitulacion: String? = null,
)

data class DocumentoAdjuntoDto(
    @JsonProperty("nombre_original") val nombre_original: String = "",
    @JsonProperty("tamanio_bytes") val tamanio_bytes: Long = 0L,
)

data class DatosPersonalesDto(
    val nombre: String,
    @JsonProperty("apellido_paterno") val apellido_paterno: String,
    @JsonProperty("apellido_materno") val apellido_materno: String,
    val carrera: String,
    val nivel: String,
    val direccion: String? = null,
    val telefono: String? = null,
    @JsonProperty("correo_electronico") val correo_electronico: String? = null,
)

data class DatosProyectoDto(
    @JsonProperty("nombre_proyecto") val nombre_proyecto: String,
    val modalidad: String,
    @JsonProperty("curso_titulacion") val curso_titulacion: String = "no",
    @JsonProperty("asesor_interno") val asesor_interno: String? = null,
    @JsonProperty("asesor_externo") val asesor_externo: String? = null,
    val director: String? = null,
    @JsonProperty("asesor_1") val asesor_1: String? = null,
    @JsonProperty("asesor_2") val asesor_2: String? = null,
)

data class DocumentosDto(
    @JsonProperty("anexo_xxxi") val anexo_xxxi: AnexoDto? = null,
    @JsonProperty("constancia_no_inconveniencia") val constancia_no_inconveniencia: ConstanciaDto? = null,
)

data class AnexoDto(
    @JsonProperty("fecha_registro") val fecha_registro: String? = null,
    val estado: String? = null,
)

data class ConstanciaDto(
    @JsonProperty("fecha_expedicion") val fecha_expedicion: String? = null,
    val estado: String? = null,
)
