package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.DatosPersonales
import com.sit_titulacion.sit.domain.DatosProyecto
import com.sit_titulacion.sit.domain.Egresado
import com.sit_titulacion.sit.domain.ProcesoTitulacion
import com.sit_titulacion.sit.repository.CarreraRepository
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.repository.DocenteRepository
import com.sit_titulacion.sit.repository.DocumentacionEscaneadaRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import java.time.Instant

/**
 * Cobertura mínima y dirigida: `EgresadoService` tiene demasiadas
 * dependencias para probarlo entero (ver constructor), así que este archivo
 * solo cubre `confirmarDocumentacionEscaneadaRecibida` — el punto donde se
 * conectó "titulado" tras encontrar que `subirDocumentoFinal` (el mecanismo
 * viejo) no tenía ningún botón en el frontend que lo disparara.
 */
@ExtendWith(MockitoExtension::class)
class EgresadoServiceTest {

    @Mock private lateinit var egresadoRepository: EgresadoRepository
    @Mock private lateinit var documentacionEscaneadaRepository: DocumentacionEscaneadaRepository
    @Mock private lateinit var catalogoRepository: CatalogoRepository
    @Mock private lateinit var carreraRepository: CarreraRepository
    @Mock private lateinit var departamentoRepository: DepartamentoRepository
    @Mock private lateinit var usuarioRepository: UsuarioRepository
    @Mock private lateinit var docenteRepository: DocenteRepository
    @Mock private lateinit var emailService: EmailService
    @Mock private lateinit var residenciaPlazoNotificacionService: ResidenciaPlazoNotificacionService
    @Mock private lateinit var noResidenciaPlazoNotificacionService: NoResidenciaPlazoNotificacionService
    @Mock private lateinit var gridFsTemplate: GridFsTemplate
    @Mock private lateinit var env: Environment
    @Mock private lateinit var htmlAnexoPdfService: HtmlAnexoPdfService
    @Mock private lateinit var revisionService: RevisionService
    @Mock private lateinit var certService: CertificacionPdfService
    @Mock private lateinit var configInstitucionalService: ConfiguracionInstitucionalService

    private fun servicio() = EgresadoService(
        egresadoRepository, documentacionEscaneadaRepository, catalogoRepository, carreraRepository,
        departamentoRepository, usuarioRepository, docenteRepository, emailService,
        residenciaPlazoNotificacionService, noResidenciaPlazoNotificacionService, gridFsTemplate, env,
        htmlAnexoPdfService, revisionService, certService, configInstitucionalService,
    )

    private fun egresadoConDocumentacionEnviada(id: ObjectId = ObjectId()): Egresado {
        val proceso = ProcesoTitulacion(
            datos_proyecto = DatosProyecto(nombre_proyecto = "x", modalidad = "Tesis"),
            fechaEnvioDocumentacionEscaneadaEgresado = Instant.now(),
        )
        return Egresado(
            id = id,
            numero_control = "C$id",
            datos_personales = DatosPersonales(
                nombre = "x", apellido_paterno = "y", apellido_materno = "z",
                carrera = "Ingeniería en Sistemas Computacionales", nivel = "Licenciatura",
            ),
            procesos = listOf(proceso),
        )
    }

    @Test
    fun confirmarDocumentacionEscaneadaRecibida_marcaTituladoYFechaTitulacion() {
        val eg = egresadoConDocumentacionEnviada()
        `when`(egresadoRepository.findByObjectIdConTimeout(eg.id!!)).thenReturn(eg)
        `when`(egresadoRepository.save(any(Egresado::class.java))).thenAnswer { it.arguments[0] }

        val ok = servicio().confirmarDocumentacionEscaneadaRecibida(eg.id.toString())

        assertTrue(ok)
        val captor = ArgumentCaptor.forClass(Egresado::class.java)
        verify(egresadoRepository).save(captor.capture())
        val procesoGuardado = captor.value.procesoActivoOrNull()!!
        assertEquals("titulado", procesoGuardado.estado_general)
        assertNotNull(procesoGuardado.fechaTitulacion)
        assertNotNull(procesoGuardado.fechaConfirmacionDocumentacionEscaneadaRecibida)
        assertTrue(procesoGuardado.historial_estados.any { it.estado == "titulado" })
    }

    @Test
    fun confirmarDocumentacionEscaneadaRecibida_sinEnvioPreviaFalla() {
        val eg = egresadoConDocumentacionEnviada().let {
            it.copy(procesos = it.procesos.map { p -> p.copy(fechaEnvioDocumentacionEscaneadaEgresado = null) })
        }
        `when`(egresadoRepository.findByObjectIdConTimeout(eg.id!!)).thenReturn(eg)

        val ok = servicio().confirmarDocumentacionEscaneadaRecibida(eg.id.toString())

        assertFalse(ok)
    }
}
