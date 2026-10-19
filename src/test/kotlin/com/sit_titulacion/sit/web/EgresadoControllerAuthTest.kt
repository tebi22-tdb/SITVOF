package com.sit_titulacion.sit.web

import com.sit_titulacion.sit.config.UsuarioPrincipal
import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.service.EgresadoService
import com.sit_titulacion.sit.service.EmailService
import com.sit_titulacion.sit.service.OriginalidadService
import com.sit_titulacion.sit.service.RevisionService
import com.sit_titulacion.sit.service.UsuarioService
import com.sit_titulacion.sit.web.api.EgresadoController
import com.sit_titulacion.sit.web.api.dto.EgresadoRequestDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus

/**
 * Tests unitarios de **autorización** en EgresadoController.
 *
 * Estrategia: instanciación directa del controller con dependencias mockeadas
 * (mismo patrón que EgresadoServiceTest / DepartamentoBandejaTest).
 * No se levanta Spring context — los checks de rol son los primeros en ejecutarse
 * y devuelven 403 antes de tocar cualquier servicio.
 *
 * Reglas que se verifican:
 *  - puedeEliminarEgresado  → solo ADMINISTRADOR
 *  - puedeAltaOEditarEgresado → ADMINISTRADOR o DEP
 */
@ExtendWith(MockitoExtension::class)
class EgresadoControllerAuthTest {

    @Mock private lateinit var egresadoService: EgresadoService
    @Mock private lateinit var egresadoRepository: EgresadoRepository
    @Mock private lateinit var usuarioService: UsuarioService
    @Mock private lateinit var emailService: EmailService
    @Mock private lateinit var revisionService: RevisionService
    @Mock private lateinit var originalidadService: OriginalidadService
    @Mock private lateinit var env: Environment

    private lateinit var controller: EgresadoController

    @BeforeEach
    fun setUp() {
        controller = EgresadoController(
            egresadoService,
            egresadoRepository,
            usuarioService,
            emailService,
            revisionService,
            originalidadService,
            env,
        )
    }

    /** Crea un UsuarioPrincipal real (sin mock) para el rol indicado. */
    private fun principal(rol: Rol) =
        UsuarioPrincipal(Usuario(username = "test-${rol.valorBd}", passwordHash = "hash", rol = rol))

    /** EgresadoRequestDto mínimo válido para pasar como parámetro (no llega a evaluarse en casos 403). */
    private fun datosMinimos() = EgresadoRequestDto(
        numero_control  = "NC001",
        nombre          = "Test",
        apellidoPaterno = "AP",
        apellidoMaterno = "AM",
        carrera         = "Ingeniería en Sistemas Computacionales",
        nivel           = "Licenciatura",
        modalidad       = "Tesis",
    )

    // ════════════════════════════════════════════════════════════════════════════════
    // POST /api/egresados/eliminar/{id}
    // Regla: puedeEliminarEgresado → SOLO ADMINISTRADOR
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `eliminar - EGRESADO recibe 403`() {
        val response = controller.eliminar("some-id", principal(Rol.EGRESADO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "EGRESADO no debe poder eliminar expedientes")
    }

    @Test
    fun `eliminar - ACADEMICO recibe 403`() {
        val response = controller.eliminar("some-id", principal(Rol.ACADEMICO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "ACADEMICO no debe poder eliminar expedientes")
    }

    @Test
    fun `eliminar - DEP recibe 403 (alta y edicion si, pero solo ADMINISTRADOR puede eliminar)`() {
        val response = controller.eliminar("some-id", principal(Rol.DEP))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "DEP puede dar de alta/editar pero NO eliminar — la eliminación es exclusiva del ADMINISTRADOR")
    }

    @Test
    fun `eliminar - principal null recibe 403`() {
        val response = controller.eliminar("some-id", null)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "Sin autenticación debe rechazar con 403")
    }

    @Test
    fun `eliminar - ADMINISTRADOR puede eliminar (204 si existe el expediente)`() {
        `when`(egresadoService.eliminar("some-id")).thenReturn(true)
        val response = controller.eliminar("some-id", principal(Rol.ADMINISTRADOR))
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode,
            "ADMINISTRADOR debe poder eliminar y recibir 204 cuando el expediente existe")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POST /api/egresados  (crear — multipart mínimo)
    // Regla: puedeAltaOEditarEgresado → ADMINISTRADOR o DEP
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `crear - EGRESADO recibe 403`() {
        val response = controller.crear(datosMinimos(), null, principal(Rol.EGRESADO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "El propio egresado no puede registrar nuevos expedientes")
    }

    @Test
    fun `crear - ACADEMICO recibe 403`() {
        val response = controller.crear(datosMinimos(), null, principal(Rol.ACADEMICO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "ACADEMICO no tiene permiso de alta — solo revisión/coordinación")
    }

    @Test
    fun `crear - principal null recibe 403`() {
        val response = controller.crear(datosMinimos(), null, null)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "Sin autenticación debe rechazar con 403")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POST /api/egresados/{id}/nuevo-proceso  (activarNuevoProceso)
    // Regla: puedeAltaOEditarEgresado → ADMINISTRADOR o DEP
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `activarNuevoProceso - EGRESADO recibe 403`() {
        val response = controller.activarNuevoProceso("some-id", datosMinimos(), null, principal(Rol.EGRESADO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "EGRESADO no puede abrir un nuevo proceso de titulación")
    }

    @Test
    fun `activarNuevoProceso - ACADEMICO recibe 403`() {
        val response = controller.activarNuevoProceso("some-id", datosMinimos(), null, principal(Rol.ACADEMICO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "ACADEMICO no puede abrir un nuevo proceso de titulación")
    }

    @Test
    fun `activarNuevoProceso - principal null recibe 403`() {
        val response = controller.activarNuevoProceso("some-id", datosMinimos(), null, null)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "Sin autenticación debe rechazar con 403")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POST /api/egresados/{id}  (actualizar)
    // Regla: puedeAltaOEditarEgresado → ADMINISTRADOR o DEP
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `actualizar - EGRESADO recibe 403`() {
        val response = controller.actualizar("some-id", datosMinimos(), null, principal(Rol.EGRESADO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "EGRESADO no puede editar expedientes ajenos")
    }

    @Test
    fun `actualizar - ACADEMICO recibe 403`() {
        val response = controller.actualizar("some-id", datosMinimos(), null, principal(Rol.ACADEMICO))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "ACADEMICO no tiene permiso de edición de expedientes")
    }

    @Test
    fun `actualizar - principal null recibe 403`() {
        val response = controller.actualizar("some-id", datosMinimos(), null, null)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode,
            "Sin autenticación debe rechazar con 403")
    }
}
