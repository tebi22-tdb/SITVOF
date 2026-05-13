package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Revision
import com.sit_titulacion.sit.domain.RevisionDocumentoAdjunto
import com.sit_titulacion.sit.repository.CatalogoRepository
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
    private val catalogoRepository: CatalogoRepository,
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
        val eg = egresadoRepository.findById(oid).orElse(null) ?: return null
        val p = eg.procesoActivoOrNull() ?: return null
        val siguiente = (revisionRepository.countByEgresadoIdAndProcesoId(oid, p.id) + 1).toInt()
        val adjunto = if (archivoAdjunto != null && !archivoAdjunto.isEmpty) {
            toAdjunto(archivoAdjunto) ?: return null
        } else null

        val rev = Revision(
            egresadoId = oid,
            procesoId = p.id,
            numeroRevision = siguiente,
            revisadoPor = revisadoPor,
            resultado = body.resultado.trim().lowercase().takeIf { it in listOf("observaciones", "aprobado") } ?: "observaciones",
            observaciones = body.observaciones?.trim()?.takeIf { it.isNotBlank() },
            documentoAdjunto = adjunto,
        )
        val guardada = revisionRepository.save(rev)

        if (guardada.resultado == "aprobado") {
            val esResidencia = catalogoEsResidencia(eg, p.datos_proyecto.modalidad)
            val flujoNoResExtendido = !esResidencia && p.fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico != null
            val yaLiberado = p.fechaRecibidoRegistroLiberacion != null || p.fechaLiberacionDocumentoCoordinacionCat != null
            if (!esResidencia && p.fechaEnviadoDepartamentoAcademico != null && !yaLiberado) {
                val ahora = Instant.now()
                var docAdjunto = p.documento_adjunto
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
                        log.warn("Certificacion no completada para egresado id={}", egresadoId)
                    }
                } catch (ex: Exception) {
                    log.error("Error al certificar documento para egresado id={}: {}", egresadoId, ex.message, ex)
                }

                val pActualizado = if (flujoNoResExtendido) {
                    p.copy(
                        fechaLiberacionDocumentoCoordinacionCat = ahora,
                        fechaConfirmacionRecibidosAnexoXxxiXxxii = ahora,
                        fecha_actualizacion = ahora,
                        documento_adjunto = docAdjunto,
                        cert_uuid = certUuid,
                        cert_hash = certHash,
                        fechaCertificacion = fechaCert,
                    )
                } else {
                    p.copy(
                        fechaRecibidoRegistroLiberacion = ahora,
                        fecha_actualizacion = ahora,
                        documento_adjunto = docAdjunto,
                        cert_uuid = certUuid,
                        cert_hash = certHash,
                        fechaCertificacion = fechaCert,
                    )
                }
                egresadoRepository.save(eg.actualizarProcesoActivo(pActualizado))
            }
        }
        return toDto(guardada)
    }

    fun listarPorEgresado(egresadoId: String): List<RevisionDto> {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return emptyList() }
        val eg = egresadoRepository.findById(oid).orElse(null) ?: return emptyList()
        val pid = eg.procesoActivoOrNull()?.id ?: return emptyList()
        return revisionRepository.findByEgresadoIdAndProcesoIdOrderByNumeroRevisionDesc(oid, pid).map { toDto(it) }
    }

    fun listarEnviadasAlEgresado(egresadoId: String): List<RevisionDto> {
        val oid = try { ObjectId(egresadoId) } catch (_: Exception) { return emptyList() }
        val eg = egresadoRepository.findById(oid).orElse(null) ?: return emptyList()
        val pid = eg.procesoActivoOrNull()?.id ?: return emptyList()
        return revisionRepository.findEnviadasAlEgresadoPorProceso(oid, pid).map { toDto(it) }
    }

    fun enviarRevisionAlEgresado(egresadoId: String, revisionId: String): RevisionDto? {
        val egresadoOid = try { ObjectId(egresadoId) } catch (_: Exception) { return null }
        val revisionOid = try { ObjectId(revisionId) } catch (_: Exception) { return null }
        val existente = revisionRepository.findByIdAndEgresadoId(revisionOid, egresadoOid) ?: return null
        if (existente.resultado != "observaciones") return null
        if (existente.enviadoAlEgresado) return toDto(existente)
        val enviada = revisionRepository.save(existente.copy(enviadoAlEgresado = true, fechaEnvioEgresado = Instant.now()))
        return toDto(enviada)
    }

    fun ultimaRevision(egresadoId: ObjectId, procesoId: ObjectId): Revision? =
        revisionRepository.findByEgresadoIdAndProcesoIdOrderByNumeroRevisionDesc(egresadoId, procesoId).firstOrNull()

    fun obtenerDocumentoAdjunto(egresadoId: String, revisionId: String, requiereEnviadaEgresado: Boolean): RevisionDocumentoDescarga? {
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

    private fun catalogoEsResidencia(eg: com.sit_titulacion.sit.domain.Egresado, modalidad: String): Boolean {
        val nombre = modalidad.trim()
        val cat = catalogoRepository.findByTipoAndActivoTrue("modalidad")
            .find { it.nombre.trim().equals(nombre, ignoreCase = true) }
        return cat?.esResidencia ?: nombre.equals("Residencia Profesional", ignoreCase = true)
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
