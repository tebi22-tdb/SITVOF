package com.sit_titulacion.sit.domain

import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant

/**
 * Un intento de titulación dentro de la cuenta de un egresado.
 * Un egresado puede tener varios procesos si el primero venció y solicita con otra modalidad.
 * El proceso activo es siempre el último elemento de Egresado.procesos.
 */
data class ProcesoTitulacion(
    val id: ObjectId = ObjectId(),
    val datos_proyecto: DatosProyecto,
    val documentos: Documentos = Documentos(),
    val documento_adjunto: DocumentoAdjunto = DocumentoAdjunto(),
    val estado_general: String = "registrado",
    val historial_estados: List<HistorialEstado> = emptyList(),
    @Field("fecha_creacion") val fechaCreacion: Instant = Instant.now(),
    val fecha_actualizacion: Instant = Instant.now(),

    // ── Flujo no residencia (16 pasos) ──────────────────────────────────────
    @Field("fecha_envio_solicitud_registro_anteproyecto_depto_academico")
    val fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico: Instant? = null,
    /** Dashboard del departamento: marca que el registro fue acusado/procesado en la bandeja. Independiente del flujo de seguimiento. */
    @Field("fecha_registrado_departamento")
    val fechaRegistradoDepartamento: Instant? = null,
    /** No residencia (flujo 16): la DEP confirma recepción de anexo XXXI, anteproyecto y anexo XXXII antes del plazo de desarrollo. */
    @Field("fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii")
    val fechaConfirmacionRecepcionInicialAnexosXxxiXxxii: Instant? = null,
    @Field("fecha_recepcion_trabajo_division_estudios_prof")
    val fechaRecepcionTrabajoDivisionEstudiosProf: Instant? = null,
    @Field("fecha_solicitud_registro_liberacion_depto_academico")
    val fechaSolicitudRegistroLiberacionDeptoAcademico: Instant? = null,
    @Field("fecha_recepcion_registro_liberacion_depto_academico")
    val fechaRecepcionRegistroLiberacionDeptoAcademico: Instant? = null,
    @Field("fecha_liberacion_documento_coordinacion_cat")
    val fechaLiberacionDocumentoCoordinacionCat: Instant? = null,

    // ── Departamento académico ───────────────────────────────────────────────
    @Field("fecha_enviado_departamento_academico")
    val fechaEnviadoDepartamentoAcademico: Instant? = null,
    @Field("fecha_recibido_registro_liberacion")
    val fechaRecibidoRegistroLiberacion: Instant? = null,

    // ── División de estudios / Anexos ────────────────────────────────────────
    @Field("fecha_confirmacion_recibidos_anexo_xxxi_xxxii")
    val fechaConfirmacionRecibidosAnexoXxxiXxxii: Instant? = null,
    @Field("fecha_creacion_anexo_9_1")
    val fechaCreacionAnexo91: Instant? = null,
    @Field("fecha_confirmacion_entrega_anexo_9_1")
    val fechaConfirmacionEntregaAnexo91: Instant? = null,
    @Field("fecha_solicitud_anexo_9_2")
    val fechaSolicitudAnexo92: Instant? = null,
    @Field("fecha_creacion_anexo_9_2")
    val fechaCreacionAnexo92: Instant? = null,
    @Field("fecha_confirmacion_recibido_anexo_9_2")
    val fechaConfirmacionRecibidoAnexo92: Instant? = null,

    // ── Sinodales y acto 9.3 ────────────────────────────────────────────────
    @Field("fecha_solicitud_sinodales")
    val fechaSolicitudSinodales: Instant? = null,
    @Field("sinodales_tribunal")
    val sinodalesTribunal: SinodalesTribunal? = null,
    @Field("fecha_asignacion_sinodales")
    val fechaAsignacionSinodales: Instant? = null,
    @Field("fecha_confirmacion_sinodales_recibidos")
    val fechaConfirmacionSinodalesRecibidos: Instant? = null,
    @Field("fecha_agenda_acto_9_3")
    val fechaAgendaActo93: Instant? = null,
    @Field("fecha_reagenda_acto_9_3")
    val fechaReagendaActo93: Instant? = null,
    @Field("fecha_creacion_anexo_9_3")
    val fechaCreacionAnexo93: Instant? = null,
    @Field("fecha_confirmacion_entrega_anexo_9_3")
    val fechaConfirmacionEntregaAnexo93: Instant? = null,

    // ── Certificación y documento final ─────────────────────────────────────
    val cert_uuid: String? = null,
    val cert_hash: String? = null,
    @Field("fecha_certificacion")
    val fechaCertificacion: Instant? = null,
    @Field("gridfs_id_doc_final")
    val gridfsIdDocFinal: ObjectId? = null,
    @Field("fecha_subida_doc_final")
    val fechaSubidaDocFinal: Instant? = null,
    @Field("fecha_titulacion")
    val fechaTitulacion: Instant? = null,

    // ── Documentación escaneada ──────────────────────────────────────────────
    @Field("fecha_solicitud_documentacion_escaneada")
    val fechaSolicitudDocumentacionEscaneada: Instant? = null,
    @Field("fecha_envio_documentacion_escaneada_egresado")
    val fechaEnvioDocumentacionEscaneadaEgresado: Instant? = null,
    @Field("fecha_confirmacion_documentacion_escaneada_recibida")
    val fechaConfirmacionDocumentacionEscaneadaRecibida: Instant? = null,
    @Field("fecha_solicitud_reenvio_documentacion_escaneada")
    val fechaSolicitudReenvioDocumentacionEscaneada: Instant? = null,
    @Field("observaciones_reenvio_documentacion_escaneada")
    val observacionesReenvioDocumentacionEscaneada: String? = null,
)
