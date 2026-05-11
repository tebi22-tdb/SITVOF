package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

@Document(collection = "registro")
data class Egresado(
    @Id val id: ObjectId? = null,
    val numero_control: String,
    val datos_personales: DatosPersonales,
    val datos_proyecto: DatosProyecto,
    val documentos: Documentos,
    val documento_adjunto: DocumentoAdjunto = DocumentoAdjunto(),
    val estado_general: String = "registrado",
    val historial_estados: List<HistorialEstado> = emptyList(),
    @Field("fecha_creacion") val fechaCreacion: Instant = Instant.now(),
    val fecha_actualizacion: Instant = Instant.now(),
    // ── Flujo no residencia (16 pasos): solicitud anteproyecto → división → registro-liberación depto → envío CAT ──
    @Field("fecha_envio_solicitud_registro_anteproyecto_depto_academico")
    val fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico: Instant? = null,
    @Field("fecha_recepcion_trabajo_division_estudios_prof")
    val fechaRecepcionTrabajoDivisionEstudiosProf: Instant? = null,
    @Field("fecha_solicitud_registro_liberacion_depto_academico")
    val fechaSolicitudRegistroLiberacionDeptoAcademico: Instant? = null,
    @Field("fecha_recepcion_registro_liberacion_depto_academico")
    val fechaRecepcionRegistroLiberacionDeptoAcademico: Instant? = null,
    /** Tras aprobación en revisiones (CAT) o equivalente en flujo extendido. */
    @Field("fecha_liberacion_documento_coordinacion_cat")
    val fechaLiberacionDocumentoCoordinacionCat: Instant? = null,
    /** Cuando se envía al departamento académico (paso 1.1 del seguimiento). */
    @Field("fecha_enviado_departamento_academico") val fechaEnviadoDepartamentoAcademico: Instant? = null,
    /** Cuando el departamento académico recibe registro y liberación (paso 2; se marca al guardar egresado). */
    @Field("fecha_recibido_registro_liberacion") val fechaRecibidoRegistroLiberacion: Instant? = null,
    /** Cuando División de estudios confirma "Recibidos anexo XXXI y XXXII" (paso 2 confirmado). */
    @Field("fecha_confirmacion_recibidos_anexo_xxxi_xxxii") val fechaConfirmacionRecibidosAnexoXxxiXxxii: Instant? = null,
    /** Cuando División de estudios crea/descarga el Anexo 9.1 (paso 3). */
    @Field("fecha_creacion_anexo_9_1") val fechaCreacionAnexo91: Instant? = null,
    /** Egresado confirma entrega del anexo 9.1 al departamento. */
    @Field("fecha_confirmacion_entrega_anexo_9_1") val fechaConfirmacionEntregaAnexo91: Instant? = null,
    /** División solicita al egresado que tramite/descargue la constancia 9.2 (antes de generar el PDF). */
    @Field("fecha_solicitud_anexo_9_2") val fechaSolicitudAnexo92: Instant? = null,
    /** Generación/descarga de constancia 9.2 desde plantilla (antes de confirmar recibido). */
    @Field("fecha_creacion_anexo_9_2") val fechaCreacionAnexo92: Instant? = null,
    /** Egresado confirma recibido constancia 9.2. */
    @Field("fecha_confirmacion_recibido_anexo_9_2") val fechaConfirmacionRecibidoAnexo92: Instant? = null,
    @Field("fecha_solicitud_sinodales") val fechaSolicitudSinodales: Instant? = null,
    /** Nombres completos del tribunal asignados por departamento académico (subdocumento). */
    @Field("sinodales_tribunal") val sinodalesTribunal: SinodalesTribunal? = null,
    @Field("fecha_asignacion_sinodales") val fechaAsignacionSinodales: Instant? = null,
    @Field("fecha_confirmacion_sinodales_recibidos") val fechaConfirmacionSinodalesRecibidos: Instant? = null,
    /** Fecha y hora agendada del acto 9.3. */
    @Field("fecha_agenda_acto_9_3") val fechaAgendaActo93: Instant? = null,
    @Field("fecha_reagenda_acto_9_3") val fechaReagendaActo93: Instant? = null,
    @Field("fecha_creacion_anexo_9_3") val fechaCreacionAnexo93: Instant? = null,
    /** DEP confirma entrega del anexo 9.3 a sinodales y sustentante. */
    @Field("fecha_confirmacion_entrega_anexo_9_3") val fechaConfirmacionEntregaAnexo93: Instant? = null,
    val cert_uuid: String? = null,
    val cert_hash: String? = null,
    @Field("fecha_certificacion") val fechaCertificacion: Instant? = null,
    @Field("gridfs_id_doc_final") val gridfsIdDocFinal: ObjectId? = null,
    @Field("fecha_subida_doc_final") val fechaSubidaDocFinal: Instant? = null,
    @Field("fecha_titulacion") val fechaTitulacion: Instant? = null,
    @Field("fecha_solicitud_documentacion_escaneada") val fechaSolicitudDocumentacionEscaneada: Instant? = null,
    @Field("fecha_envio_documentacion_escaneada_egresado") val fechaEnvioDocumentacionEscaneadaEgresado: Instant? = null,
    @Field("fecha_confirmacion_documentacion_escaneada_recibida") val fechaConfirmacionDocumentacionEscaneadaRecibida: Instant? = null,
    @Field("fecha_solicitud_reenvio_documentacion_escaneada") val fechaSolicitudReenvioDocumentacionEscaneada: Instant? = null,
    @Field("observaciones_reenvio_documentacion_escaneada") val observacionesReenvioDocumentacionEscaneada: String? = null,
)
