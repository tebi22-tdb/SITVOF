package com.sit_titulacion.sit.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.repository.EgresadoRepository
import jakarta.annotation.PostConstruct
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bson.types.ObjectId
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.UUID

data class CertificacionResult(
    val nuevoGridFsId: ObjectId,
    val certUuid: String,
    val certHash: String,
)

@Service
class CertificacionPdfService(
    private val gridFsTemplate: GridFsTemplate,
    private val egresadoRepository: EgresadoRepository,
    @Value("\${sit.cert.keystore-path}") private val keystorePath: String,
    @Value("\${sit.cert.keystore-password}") private val keystorePassword: String,
    @Value("\${sit.cert.base-url}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(CertificacionPdfService::class.java)

    @PostConstruct
    fun inicializar() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val ksFile = File(keystorePath)
        if (!ksFile.exists()) {
            try {
                generarKeystoreAutofirmado(ksFile, keystorePassword)
                log.info("Keystore PKI generado en: {}", keystorePath)
            } catch (ex: Exception) {
                log.error("No se pudo generar el keystore PKI: {}", ex.message, ex)
            }
        }
    }

    fun certificarDocumento(egresado: Egresado): CertificacionResult? {
        val adj = egresado.documento_adjunto
        val gridId = adj.gridfs_id ?: run {
            log.warn("certificarDocumento: egresado {} no tiene documento adjunto", egresado.numero_control)
            return null
        }
        if (!adj.content_type.contains("pdf", ignoreCase = true)) {
            log.warn("certificarDocumento: documento de {} no es PDF ({})", egresado.numero_control, adj.content_type)
            return null
        }

        val pdfOriginal = leerDeGridFs(gridId) ?: run {
            log.warn("certificarDocumento: no se pudo leer GridFS id={} para egresado {}", gridId, egresado.numero_control)
            return null
        }

        val certUuid = generarCertUuid()
        val certHash = calcularSha256(pdfOriginal)

        val pdfConPagina = agregarPaginaCertificacion(pdfOriginal, egresado, certUuid) ?: return null
        val pdfFirmado = firmarPdf(pdfConPagina) ?: return null

        // Guardar nuevo PDF en GridFS ANTES de borrar el anterior
        val nuevoGridId = try {
            gridFsTemplate.store(
                ByteArrayInputStream(pdfFirmado),
                "certificado_${egresado.numero_control}.pdf",
                "application/pdf",
                null,
            ) as ObjectId
        } catch (ex: Exception) {
            log.error("certificarDocumento: error al guardar PDF certificado en GridFS: {}", ex.message, ex)
            return null
        }

        // Borrar PDF anterior solo si el nuevo se guardó correctamente
        try {
            gridFsTemplate.delete(Query.query(Criteria.where("_id").`is`(gridId)))
        } catch (ex: Exception) {
            log.warn("certificarDocumento: no se pudo borrar GridFS anterior id={}: {}", gridId, ex.message)
        }

        log.info("Documento certificado: egresado={}, uuid={}", egresado.numero_control, certUuid)
        return CertificacionResult(nuevoGridId, certUuid, certHash)
    }

    private fun leerDeGridFs(gridId: ObjectId): ByteArray? {
        return try {
            val file = gridFsTemplate.findOne(Query.query(Criteria.where("_id").`is`(gridId))) ?: return null
            gridFsTemplate.getResource(file).inputStream.use { it.readBytes() }
        } catch (ex: Exception) {
            log.warn("leerDeGridFs: error id={}: {}", gridId, ex.message)
            null
        }
    }

    private fun generarCertUuid(): String {
        val raw = UUID.randomUUID().toString().replace("-", "").uppercase()
        return "SIT-${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8, 12)}"
    }

    private fun calcularSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun agregarPaginaCertificacion(pdfBytes: ByteArray, egresado: Egresado, certUuid: String): ByteArray? {
        val tmpIn = Files.createTempFile("sit-cert-in-", ".pdf").toFile()
        val tmpOut = Files.createTempFile("sit-cert-out-", ".pdf").toFile()
        return try {
            tmpIn.writeBytes(pdfBytes)
            val doc = PDDocument.load(tmpIn)
            try {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                val qrBytes = generarQrPng("${baseUrl.trimEnd('/')}/#/verificar/$certUuid", 200)
                val qrImage = PDImageXObject.createFromByteArray(doc, qrBytes, "qr-cert")

                val nombre = safe(nombreCompleto(egresado))
                val control = safe(egresado.numero_control)
                val modalidad = safe(egresado.datos_proyecto.modalidad)
                val carrera = safe(egresado.datos_personales.carrera)
                val fecha = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now())

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                    // Fondo del encabezado (rectángulo azul)
                    cs.setNonStrokingColor(0.15f, 0.35f, 0.55f)
                    cs.addRect(50f, 764f, 495f, 58f)
                    cs.fill()

                    // Título en blanco
                    val fontBold = PDType1Font.HELVETICA_BOLD
                    val fontNormal = PDType1Font.HELVETICA
                    cs.setNonStrokingColor(1f, 1f, 1f)
                    cs.beginText()
                    cs.setFont(fontBold, 18f)
                    val tituloWidth = fontBold.getStringWidth("DOCUMENTO CERTIFICADO") / 1000f * 18f
                    cs.newLineAtOffset((595f - tituloWidth) / 2f, 800f)
                    cs.showText("DOCUMENTO CERTIFICADO")
                    cs.endText()

                    cs.beginText()
                    cs.setFont(fontNormal, 10f)
                    val subWidth = fontNormal.getStringWidth("Sistema Integral de Titulacion - TECNM Campus Oaxaca") / 1000f * 10f
                    cs.newLineAtOffset((595f - subWidth) / 2f, 779f)
                    cs.showText("Sistema Integral de Titulacion del Valle de Oaxaca")
                    cs.endText()

                    cs.setNonStrokingColor(0f, 0f, 0f)
                    cs.setStrokingColor(0.75f, 0.75f, 0.75f)
                    cs.setLineWidth(0.5f)

                    // Separador
                    cs.moveTo(50f, 756f); cs.lineTo(545f, 756f); cs.stroke()

                    // Filas de datos
                    fun fila(label: String, valor: String, y: Float) {
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                        cs.setNonStrokingColor(0f, 0f, 0f)
                        cs.newLineAtOffset(60f, y)
                        cs.showText(label)
                        cs.setFont(PDType1Font.HELVETICA, 11f)
                        cs.showText(valor)
                        cs.endText()
                    }

                    fila("Alumno:        ", nombre, 736f)
                    fila("No. Control:   ", control, 716f)
                    fila("Modalidad:     ", modalidad, 696f)
                    fila("Carrera:       ", carrera, 676f)

                    cs.moveTo(50f, 662f); cs.lineTo(545f, 662f); cs.stroke()

                    fila("Fecha:         ", fecha, 642f)
                    fila("ID:            ", certUuid, 622f)

                    cs.moveTo(50f, 606f); cs.lineTo(545f, 606f); cs.stroke()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 10f)
                    cs.setNonStrokingColor(0.35f, 0.35f, 0.35f)
                    cs.newLineAtOffset(60f, 588f)
                    cs.showText("Escanee el codigo QR o visite: ${baseUrl.trimEnd('/')}/#/verificar/$certUuid")
                    cs.endText()

                    // QR centrado
                    val qrSize = 160f
                    cs.drawImage(qrImage, (595f - qrSize) / 2f, 390f, qrSize, qrSize)

                    cs.moveTo(50f, 378f); cs.lineTo(545f, 378f); cs.stroke()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 9f)
                    cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    cs.newLineAtOffset(60f, 358f)
                    cs.showText("Este documento incluye una firma digital PKI certificada.")
                    cs.endText()

                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 9f)
                    cs.newLineAtOffset(60f, 343f)
                    cs.showText("Puede verificar la firma abriendo el PDF en Adobe Reader u otro lector compatible.")
                    cs.endText()
                }

                doc.save(tmpOut)
            } finally {
                doc.close()
            }
            tmpOut.readBytes()
        } catch (ex: Exception) {
            log.error("agregarPaginaCertificacion: error para {}: {}", egresado.numero_control, ex.message, ex)
            null
        } finally {
            listOf(tmpIn, tmpOut).forEach { f ->
                try { if (f.exists()) f.delete() } catch (e: Exception) {
                    log.warn("No se pudo borrar archivo temporal: {}", f.absolutePath)
                }
            }
        }
    }

    private fun firmarPdf(pdfBytes: ByteArray): ByteArray? {
        val ksFile = File(keystorePath)
        if (!ksFile.exists()) {
            log.warn("firmarPdf: keystore no encontrado en {}", keystorePath)
            return null
        }

        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, keystorePassword.toCharArray()) }
        val alias = ks.aliases().nextElement()
        val privateKey = ks.getKey(alias, keystorePassword.toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(alias)

        val tmpIn = Files.createTempFile("sit-sign-in-", ".pdf").toFile()
        val tmpOut = Files.createTempFile("sit-sign-out-", ".pdf").toFile()
        return try {
            tmpIn.writeBytes(pdfBytes)
            val doc = PDDocument.load(tmpIn)
            try {
                val signature = PDSignature().apply {
                    setFilter(COSName.getPDFName("Adobe.PPKLite"))
                    setSubFilter(COSName.getPDFName("adbe.pkcs7.detached"))
                    name = "SIT - Sistema Integral de Titulacion"
                    reason = "Certificacion de documento academico - Residencia Profesional"
                    location = "TECNM Campus Oaxaca"
                    signDate = Calendar.getInstance()
                }

                val options = SignatureOptions().apply {
                    preferredSignatureSize = 0x7000
                }

                doc.addSignature(signature, { content ->
                    crearFirmaCms(content.readBytes(), privateKey, chain)
                }, options)

                FileOutputStream(tmpOut).use { doc.saveIncremental(it) }
            } finally {
                doc.close()
            }
            tmpOut.readBytes()
        } catch (ex: Exception) {
            log.error("firmarPdf: error al firmar: {}", ex.message, ex)
            null
        } finally {
            listOf(tmpIn, tmpOut).forEach { f ->
                try { if (f.exists()) f.delete() } catch (e: Exception) {
                    log.warn("No se pudo borrar archivo temporal: {}", f.absolutePath)
                }
            }
        }
    }

    private fun crearFirmaCms(content: ByteArray, privateKey: PrivateKey, chain: Array<Certificate>): ByteArray {
        val certList = chain.filterIsInstance<X509Certificate>()
        val store = JcaCertStore(certList)
        val gen = CMSSignedDataGenerator()

        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(privateKey)

        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(),
            ).build(contentSigner, certList[0]),
        )
        gen.addCertificates(store)

        val signed = gen.generate(CMSProcessableByteArray(content), false)
        return signed.encoded
    }

    private fun generarQrPng(contenido: String, tamanio: Int): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, tamanio, tamanio, hints)
        val bos = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", bos)
        return bos.toByteArray()
    }

    private fun generarKeystoreAutofirmado(file: File, password: String) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = Date()
        val expiry = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val subject = X500Name("CN=SIT TECNM, O=TECNM Campus Oaxaca, C=MX")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            expiry,
            subject,
            kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(kp.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password.toCharArray())
        ks.setKeyEntry("sit-cert", kp.private, password.toCharArray(), arrayOf(cert))

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { ks.store(it, password.toCharArray()) }
    }

    private fun nombreCompleto(e: Egresado): String =
        listOf(e.datos_personales.nombre, e.datos_personales.apellido_paterno, e.datos_personales.apellido_materno)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { e.numero_control }

    private fun safe(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("[^\\x00-\\x7F]"), "")
}
