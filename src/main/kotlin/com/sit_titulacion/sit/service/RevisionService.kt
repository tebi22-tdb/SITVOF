package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Revision
import com.sit_titulacion.sit.domain.RevisionDocumentoAdjunto
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.RevisionRepository
import com.sit_titulacion.sit.web.api.dto.CreateRevisionRequestDto
import com.sit_titulacion.sit.web.api.dto.RevisionDto
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class RevisionService(
    private val revisionRepository: RevisionRepository,
    private val egresadoRepository: EgresadoRepository,
    private val certService: CertificacionPdfService,
) {
    private val log = LoggerFactory.getLogger(RevisionService::class.java)
    private val formatter = DateTimeFormatter.ISO_INSTANT

    data class RevisionDocumentoDescarga(
        val bytes: ByteArray,
        val contentType: String,
        val fileName: String,
    )

    fun crear(
        egresadoId: String,
        body: CreateRevisionRequestDto,
        revisadoPor: String,
        archivoAdjunto: MultipartFile? = null,
    ): RevisionDto? {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return null }
        if (!egresadoRepository.existsById(oid)) return null
        val siguiente = (revisionRepository.countByEgresadoId(oid) + 1).toInt()
        val adjunto = if (archivoAdjunto != null && !archivoAdjunto.isEmpty) {
            toAdjunto(archivoAdjunto) ?: return null
        } else {
            null
        }
        val rev = Revision(
            egresadoId = oid,
            numeroRevision = siguiente,
            revisadoPor = revisadoPor,
            resultado = body.resultado.trim().lowercase().takeIf { it in listOf("observaciones", "aprobado") } ?: "observaciones",
            observaciones = body.observaciones?.trim()?.takeIf { it.isNotBlank() },
            documentoAdjunto = adjunto,
        )
        val guardada = revisionRepository.save(rev)
        if (guardada.resultado == "aprobado") {
            val eg = egresadoRepository.findById(oid).orElse(null)
            if (eg != null) {
                val esResidencia =
                    eg.datos_proyecto.modalidad.trim().equals("Residencia Profesional", ignoreCase = true)
                val flujoNoResExtendido = !esResidencia && eg.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
                val yaLiberado =
                    eg.fechaRecibidoRegistroLiberacion != null || eg.fechaLiberacionDocumentoCoordinacionCat != null
                if (!esResidencia && eg.fechaEnviadoDepartamentoAcademico != null && !yaLiberado) {
                    val ahora = Instant.now()

                    // Intentar certificar el documento antes de marcar como aprobado
                    var docAdjunto = eg.documento_adjunto
                    var certUuid: String? = null
                    var certHash: String? = null
                    var fechaCert: Instant? = null
                    try {
                        val resultado = certService.certificarDocumento(eg)
                        if (resultado != null) {
                            docAdjunto = docAdjunto.copy(gridfs_id = resultado.nuevoGridFsId)
                            certUuid = resultado.certUuid
                            certHash = resultado.certHash
                            fechaCert = ahora
                        } else {
                            log.warn("Certificacion no completada para egresado id={}: documento faltante o no PDF", egresadoId)
                        }
                    } catch (ex: Exception) {
                        log.error("Error al certificar documento para egresado id={}: {}", egresadoId, ex.message, ex)
                    }

                    if (flujoNoResExtendido) {
                        egresadoRepository.save(
                            eg.copy(
                                fechaLiberacionDocumentoCoordinacionCat = ahora,
                                fechaConfirmacionRecibidosAnexoXxxiXxxii = ahora,
                                fecha_actualizacion = ahora,
                                documento_adjunto = docAdjunto,
                                cert_uuid = certUuid,
                                cert_hash = certHash,
                                fechaCertificacion = fechaCert,
                            ),
                        )
                    } else {
                        egresadoRepository.save(
                            eg.copy(
                                fechaRecibidoRegistroLiberacion = ahora,
                                fecha_actualizacion = ahora,
                                documento_adjunto = docAdjunto,
                                cert_uuid = certUuid,
                                cert_hash = certHash,
                                fechaCertificacion = fechaCert,
                            ),
                        )
                    }
                }
            }
        }
        return toDto(guardada)
    }

    fun listarPorEgresado(egresadoId: String): List<RevisionDto> {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return emptyList() }
        return revisionRepository.findByEgresadoIdOrderByNumeroRevisionDesc(oid).map { toDto(it) }
    }

    fun listarEnviadasAlEgresado(egresadoId: String): List<RevisionDto> {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return emptyList() }
        return revisionRepository.findEnviadasAlEgresado(oid).map { toDto(it) }
    }

    fun enviarRevisionAlEgresado(egresadoId: String, revisionId: String): RevisionDto? {
        val egresadoOid = try { ObjectId(egresadoId) } catch (_: Exception) { return null }
        val revisionOid = try { ObjectId(revisionId) } catch (_: Exception) { return null }
        val existente = revisionRepository.findByIdAndEgresadoId(revisionOid, egresadoOid) ?: return null
        if (existente.resultado != "observaciones") return null
        if (existente.enviadoAlEgresado) return toDto(existente)
        val enviada = revisionRepository.save(
            existente.copy(
                enviadoAlEgresado = true,
                fechaEnvioEgresado = Instant.now(),
            ),
        )
        return toDto(enviada)
    }

    fun ultimaRevision(egresadoId: ObjectId): Revision? =
        revisionRepository.findByEgresadoIdOrderByNumeroRevisionDesc(egresadoId).firstOrNull()

    fun obtenerDocumentoAdjunto(
        egresadoId: String,
        revisionId: String,
        requiereEnviadaEgresado: Boolean,
    ): RevisionDocumentoDescarga? {
        val egresadoOid = try { ObjectId(egresadoId) } catch (_: Exception) { return null }
        val revisionOid = try { ObjectId(revisionId) } catch (_: Exception) { return null }
        val rev = revisionRepository.findByIdAndEgresadoId(revisionOid, egresadoOid) ?: return null
        if (requiereEnviadaEgresado && !rev.enviadoAlEgresado) return null
        val doc = rev.documentoAdjunto ?: return null
        return RevisionDocumentoDescarga(
            bytes = doc.contenido,
            contentType = doc.contentType.ifBlank { "application/pdf" },
            fileName = doc.nombreOriginal.ifBlank { "revision-adjunta.pdf" },
        )
    }

    private fun toAdjunto(archivo: MultipartFile): RevisionDocumentoAdjunto? {
        val nombreOriginal = (archivo.originalFilename ?: "revision-adjunta.pdf").trim().ifBlank { "revision-adjunta.pdf" }
        val esPdfPorNombre = nombreOriginal.lowercase().endsWith(".pdf")
        val ct = (archivo.contentType ?: "").lowercase()
        val esPdfPorTipo = ct == "application/pdf" || ct == "application/x-pdf"
        if (!esPdfPorNombre && !esPdfPorTipo) return null
        val bytes = archivo.bytes
        if (bytes.isEmpty()) return null
        return RevisionDocumentoAdjunto(
            nombreOriginal = nombreOriginal,
            contentType = "application/pdf",
            tamanioBytes = bytes.size.toLong(),
            contenido = bytes,
        )
    }

    private fun toDto(r: Revision) = RevisionDto(
        id = r.id?.toHexString() ?: "",
        egresadoId = r.egresadoId.toHexString(),
        numeroRevision = r.numeroRevision,
        fecha = formatter.format(r.fecha),
        revisadoPor = r.revisadoPor,
        resultado = r.resultado,
        observaciones = r.observaciones,
        enviadoAlEgresado = r.enviadoAlEgresado,
        fechaEnvioEgresado = r.fechaEnvioEgresado?.let { formatter.format(it) },
        tieneDocumentoAdjunto = r.documentoAdjunto != null,
        documentoNombre = r.documentoAdjunto?.nombreOriginal,
        documentoContentType = r.documentoAdjunto?.contentType,
        documentoTamanioBytes = r.documentoAdjunto?.tamanioBytes,
    )
}
