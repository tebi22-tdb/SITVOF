package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.*
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DocumentacionEscaneadaRepository
import com.sit_titulacion.sit.repository.DocenteRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.gridfs.GridFsTemplate
import java.time.Instant

/**
 * Tests unitarios para el cálculo de badges y filtrado de listas en el dashboard
 * del departamento académico (contarParaDepartamento / listarParaDepartamento).
 *
 * Escenarios cubiertos:
 *  - Residencia: badge rojo (pendientes liberar), badge azul (todos liberados), lista con/sin segmento
 *  - Anteproyecto (tesis): badge rojo (sin registrar), badge azul (todos registrados), lista muestra todos
 *  - Sinodales: badge rojo (sin asignar), badge azul (todos asignados),
 *    verificación del bug original (confirmacion tardía no genera rojo)
 *  - Pestaña Todos: incluye registros del flujo antiguo (legacy)
 *  - Segundo proceso: no contamina conteos del proceso anterior
 *  - Carrera fuera del segmento: no aparece en ninguna pestaña
 */
@ExtendWith(MockitoExtension::class)
class DepartamentoBandejaTest {

    @Mock private lateinit var egresadoRepository: EgresadoRepository
    @Mock private lateinit var documentacionEscaneadaRepository: DocumentacionEscaneadaRepository
    @Mock private lateinit var catalogoRepository: CatalogoRepository
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

    private lateinit var service: EgresadoService

    companion object {
        const val COORD = "coord@test.com"
        const val SEGMENTO = "eco-adm"
        const val CARRERA = "Contaduría Pública"
        const val CARRERA_OTRA = "Ingeniería en Sistemas"
        val T: Instant = Instant.now()
    }

    @BeforeEach
    fun setUp() {
        // La propiedad zonaActo93 es lazy; se configura por si acaso se accede en algún path.
        lenient().`when`(env.getProperty("sit.acto93.zona-horaria", "America/Mexico_City"))
            .thenReturn("America/Mexico_City")

        service = EgresadoService(
            egresadoRepository,
            documentacionEscaneadaRepository,
            catalogoRepository,
            usuarioRepository,
            docenteRepository,
            emailService,
            residenciaPlazoNotificacionService,
            noResidenciaPlazoNotificacionService,
            gridFsTemplate,
            env,
            htmlAnexoPdfService,
            revisionService,
            certService,
        )

        // lenient() evita UnnecessaryStubbingException en tests donde no se llaman todos los stubs
        val coord = Usuario(username = COORD, passwordHash = "x", rol = "coordinador")
        lenient().`when`(usuarioRepository.findByUsername(COORD)).thenReturn(coord)

        val deptoCat = Catalogo(
            tipo = "departamento",
            nombre = "Económico-Administrativo",
            slug = SEGMENTO,
            carreras = listOf(CARRERA),
        )
        lenient().`when`(catalogoRepository.findFirstByTipoAndSlug("departamento", SEGMENTO)).thenReturn(deptoCat)
        lenient().`when`(catalogoRepository.countByTipoAndSlug("departamento", SEGMENTO)).thenReturn(1)

        val modalidades = listOf(
            Catalogo(tipo = "modalidad", nombre = "Residencia Profesional", esResidencia = true),
            Catalogo(tipo = "modalidad", nombre = "Tesis", esResidencia = false),
            Catalogo(tipo = "modalidad", nombre = "Tesina", esResidencia = false),
        )
        lenient().`when`(catalogoRepository.findByTipoAndActivoTrue("modalidad")).thenReturn(modalidades)
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Builders de datos de prueba
    // ────────────────────────────────────────────────────────────────────────────────

    private fun proceso(
        modalidad: String,
        enviado: Instant? = null,
        recibidoLiberacion: Instant? = null,
        liberadoCat: Instant? = null,
        anteproyectoEnviado: Instant? = null,
        marcadoRegistrado: Instant? = null,
        sinodalesSolicitados: Instant? = null,
        sinodalesAsignados: Instant? = null,
    ) = ProcesoTitulacion(
        datos_proyecto = DatosProyecto(nombre_proyecto = "Proyecto Test", modalidad = modalidad),
        fechaEnviadoDepartamentoAcademico = enviado,
        fechaRecibidoRegistroLiberacion = recibidoLiberacion,
        fechaLiberacionDocumentoCoordinacionCat = liberadoCat,
        fechaEnvioSolicitudRegistroAnteproyectoDeptoAcademico = anteproyectoEnviado,
        fechaRegistradoDepartamento = marcadoRegistrado,
        fechaSolicitudSinodales = sinodalesSolicitados,
        fechaAsignacionSinodales = sinodalesAsignados,
    )

    private fun egresado(
        control: String,
        carrera: String = CARRERA,
        vararg procesos: ProcesoTitulacion,
    ) = Egresado(
        id = ObjectId(),
        numero_control = control,
        datos_personales = DatosPersonales(
            nombre = "Test",
            apellido_paterno = "AP",
            apellido_materno = "AM",
            carrera = carrera,
            nivel = "Licenciatura",
        ),
        procesos = procesos.toList(),
    )

    private fun conteos(vararg lista: Egresado) =
        lista.toList().also { `when`(egresadoRepository.findAll()).thenReturn(it) }
            .let { service.contarParaDepartamento(COORD, SEGMENTO) }

    private fun lista(tab: String, vararg egresados: Egresado): List<*> {
        `when`(egresadoRepository.findAll()).thenReturn(egresados.toList())
        return service.listarParaDepartamento(tab, COORD, SEGMENTO)
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // RESIDENCIA — badges
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `residencia pendiente de liberacion - badge rojo muestra 1`() {
        val counts = conteos(
            egresado("R001", procesos = arrayOf(proceso("Residencia Profesional", enviado = T)))
        )
        assertEquals(1, counts["pendientes"], "pendientes debe ser 1 → frontend muestra badge rojo")
        assertEquals(0, counts["aprobados"])
    }

    @Test
    fun `residencia ya liberada - badge azul (pendientes=0 aprobados=1)`() {
        val counts = conteos(
            egresado("R001", procesos = arrayOf(
                proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T)
            ))
        )
        assertEquals(0, counts["pendientes"], "pendientes=0 → frontend cambia a badge azul")
        assertEquals(1, counts["aprobados"], "aprobados=1 → badge azul muestra este número")
    }

    @Test
    fun `dos residencias una pendiente una liberada - badge rojo con 1`() {
        val counts = conteos(
            egresado("R001", procesos = arrayOf(proceso("Residencia Profesional", enviado = T))),
            egresado("R002", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
        )
        assertEquals(1, counts["pendientes"])
        assertEquals(1, counts["aprobados"])
    }

    @Test
    fun `todas las residencias liberadas - badge azul con total correcto`() {
        val counts = conteos(
            egresado("R001", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
            egresado("R002", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
            egresado("R003", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
        )
        assertEquals(0, counts["pendientes"])
        assertEquals(3, counts["aprobados"], "badge azul debe mostrar los 3 liberados")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // RESIDENCIA — lista por pestaña
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `lista residencia CON segmento muestra pendientes Y liberados`() {
        val result = lista("pendientes",
            egresado("R010", procesos = arrayOf(proceso("Residencia Profesional", enviado = T))),
            egresado("R011", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
        )
        assertEquals(2, result.size, "con segmento todos aparecen; la acción Liberar solo se muestra para pendientes")
    }

    @Test
    fun `lista pendientes SIN segmento muestra solo no aprobados en vista coordinacion global`() {
        // Sin segmento = vista global coordinacion. Residencia queda excluida por bandejaDepartamentoExcluyeResidencia.
        // Se usan Tesis: uno pendiente, otro ya aprobado por CAT (fechaLiberacionDocumentoCoordinacionCat set).
        `when`(egresadoRepository.findAll()).thenReturn(listOf(
            egresado("T010", procesos = arrayOf(proceso("Tesis", enviado = T))),
            egresado("T011", procesos = arrayOf(proceso("Tesis", enviado = T, liberadoCat = T))),
        ))
        val result = service.listarParaDepartamento("pendientes", COORD, null)
        assertEquals(1, result.size, "sin segmento el aprobado por CAT no aparece en pendientes")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // ANTEPROYECTO — badges
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `anteproyecto enviado sin marcar - badge rojo con 1`() {
        val counts = conteos(
            egresado("T001", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T)))
        )
        assertEquals(1, counts["anteproyecto"], "badge rojo = 1 → falta marcar como registrado")
        assertEquals(1, counts["total_anteproyecto"])
    }

    @Test
    fun `anteproyecto marcado como registrado - badge azul (pendiente=0 total=1)`() {
        val counts = conteos(
            egresado("T001", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T)))
        )
        assertEquals(0, counts["anteproyecto"], "pendiente=0 → frontend muestra badge azul")
        assertEquals(1, counts["total_anteproyecto"], "total=1 → badge azul muestra este número")
    }

    @Test
    fun `tres anteproyectos dos marcados uno pendiente - badge rojo con 1`() {
        val counts = conteos(
            egresado("T010", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T))),
            egresado("T011", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T))),
            egresado("T012", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
        )
        assertEquals(1, counts["anteproyecto"], "badge rojo = 1 (solo el pendiente)")
        assertEquals(3, counts["total_anteproyecto"], "total = 3 → badge azul cuando todos estén marcados")
    }

    @Test
    fun `todos los anteproyectos marcados - badge azul con total correcto`() {
        val counts = conteos(
            egresado("T020", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T))),
            egresado("T021", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T))),
        )
        assertEquals(0, counts["anteproyecto"])
        assertEquals(2, counts["total_anteproyecto"])
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // ANTEPROYECTO — lista por pestaña
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `lista anteproyecto muestra marcados y no marcados - registros se quedan en la pestana`() {
        val result = lista("anteproyecto",
            egresado("T030", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T))),
            egresado("T031", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
        )
        assertEquals(2, result.size, "ambos deben aparecer; el botón desaparece en el marcado pero el registro queda")
    }

    @Test
    fun `lista anteproyecto excluye tesis sin fecha de envio de anteproyecto`() {
        val result = lista("anteproyecto",
            egresado("T040", procesos = arrayOf(proceso("Tesis"))),               // sin anteproyectoEnviado
            egresado("T041", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `lista anteproyecto no incluye residencia aunque tenga fecha de envio`() {
        val result = lista("anteproyecto",
            egresado("R050", procesos = arrayOf(proceso("Residencia Profesional", anteproyectoEnviado = T))),
            egresado("T051", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
        )
        assertEquals(1, result.size, "la residencia no debe aparecer en la pestaña Anteproyecto")
    }

    @Test
    fun `lista anteproyecto excluye los que ya pasaron a revision (fecha_enviado_depto set)`() {
        val result = lista("anteproyecto",
            egresado("T060", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, enviado = T))),
            egresado("T061", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
        )
        assertEquals(1, result.size, "si ya fue enviado a revisión sale de la pestaña Anteproyecto")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // SINODALES — badges
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `sinodal solicitado sin asignar - badge rojo con 1`() {
        val counts = conteos(
            egresado("S001", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T)))
        )
        assertEquals(1, counts["sinodales_por_asignar"], "badge rojo = 1")
        assertEquals(1, counts["total_sinodales"])
    }

    @Test
    fun `sinodal asignado - badge azul (sin_asignar=0 total=1)`() {
        val counts = conteos(
            egresado("S001", procesos = arrayOf(
                proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T)
            ))
        )
        assertEquals(0, counts["sinodales_por_asignar"], "asignado = 0 pendientes → badge azul")
        assertEquals(1, counts["total_sinodales"])
    }

    @Test
    fun `BUG ORIGINAL - sinodal asignado pero sin confirmacion tardia no genera badge rojo`() {
        // Antes del fix: se usaba fechaConfirmacionSinodalesRecibidos como criterio del badge rojo.
        // La confirmación es un paso posterior (puede tardar semanas), generando badge rojo
        // aunque el departamento ya asignó los sinodales.
        // Ahora: el criterio correcto es fechaAsignacionSinodales == null.
        val counts = conteos(
            egresado("S010", procesos = arrayOf(
                proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T)
                // fechaConfirmacionSinodalesRecibidos queda null (paso posterior)
            ))
        )
        assertEquals(0, counts["sinodales_por_asignar"],
            "el departamento ya asignó sinodales → NO debe mostrar badge rojo aunque la confirmación tardía esté pendiente")
    }

    @Test
    fun `dos sinodales uno asignado uno pendiente - badge rojo con 1`() {
        val counts = conteos(
            egresado("S020", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T))),
            egresado("S021", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T))),
        )
        assertEquals(1, counts["sinodales_por_asignar"])
        assertEquals(2, counts["total_sinodales"])
    }

    @Test
    fun `todos los sinodales asignados - badge azul con total correcto`() {
        val counts = conteos(
            egresado("S030", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T))),
            egresado("S031", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T))),
            egresado("S032", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T))),
        )
        assertEquals(0, counts["sinodales_por_asignar"])
        assertEquals(3, counts["total_sinodales"], "badge azul debe mostrar los 3 asignados")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // PESTAÑA TODOS — contenido y registros legacy
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `pestaña todos incluye registro legacy (tesis con enviado directo sin pasar por anteproyecto)`() {
        `when`(egresadoRepository.findAll()).thenReturn(listOf(
            egresado("L001", procesos = arrayOf(proceso("Tesis", enviado = T))) // flujo antiguo: sin anteproyectoEnviado
        ))
        val todos = service.listarParaDepartamento("todos", COORD, SEGMENTO)
        val anteproyecto = service.listarParaDepartamento("anteproyecto", COORD, SEGMENTO)

        assertEquals(1, todos.size, "el registro legacy sí aparece en Todos")
        assertEquals(0, anteproyecto.size, "el registro legacy NO aparece en Anteproyecto (no tiene fecha de envío de anteproyecto)")
    }

    @Test
    fun `pestaña todos no genera badge rojo en otros tabs para registro legacy`() {
        val counts = conteos(
            egresado("L002", procesos = arrayOf(proceso("Tesis", enviado = T))) // flujo antiguo
        )
        // El registro aparece en Todos pero no debe inflar el badge rojo de Anteproyecto ni Sinodales
        assertEquals(0, counts["anteproyecto"])
        assertEquals(0, counts["sinodales_por_asignar"])
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // SEGUNDO PROCESO (modalidad caducada, egresado reintenta con otra modalidad)
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `segundo proceso de titulacion - solo el proceso activo cuenta en los badges`() {
        // Primer proceso: tenía sinodales asignados (proceso caducado)
        val primerProceso = proceso("Tesis",
            sinodalesSolicitados = T,
            sinodalesAsignados = T,
        )
        // Segundo proceso activo: anteproyecto enviado, sin sinodales aún
        val segundoProceso = proceso("Tesis", anteproyectoEnviado = T)

        val counts = conteos(egresado("M001", procesos = arrayOf(primerProceso, segundoProceso)))

        assertEquals(0, counts["sinodales_por_asignar"],
            "el proceso anterior con sinodales no contamina los conteos del proceso activo")
        assertEquals(0, counts["total_sinodales"],
            "sinodales del proceso caducado no deben aparecer en el total")
        assertEquals(1, counts["anteproyecto"],
            "solo el proceso activo (segundo) se contabiliza")
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // FILTRO DE SEGMENTO — egresados de otra carrera no aparecen
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `egresado de carrera fuera del segmento no aparece en ninguna pestaña ni en conteos`() {
        val counts = conteos(
            egresado("X001", carrera = CARRERA_OTRA,
                procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))
            )
        )
        assertEquals(0, counts["anteproyecto"])
        assertEquals(0, counts["total_anteproyecto"])
        assertEquals(0, counts["sinodales_por_asignar"])
        assertEquals(0, counts["total_sinodales"])
    }

    @Test
    fun `egresado del segmento y egresado fuera - solo el del segmento cuenta`() {
        val counts = conteos(
            egresado("X010", carrera = CARRERA,
                procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))
            ),
            egresado("X011", carrera = CARRERA_OTRA,
                procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))
            ),
        )
        assertEquals(1, counts["anteproyecto"], "solo el del segmento cuenta")
        assertEquals(1, counts["total_anteproyecto"])
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // ESCENARIO MIXTO — varios tipos en el mismo departamento
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `escenario completo - residencia y tesis conviven correctamente en badges`() {
        val counts = conteos(
            // Residencia pendiente
            egresado("MIX001", procesos = arrayOf(proceso("Residencia Profesional", enviado = T))),
            // Residencia liberada
            egresado("MIX002", procesos = arrayOf(proceso("Residencia Profesional", enviado = T, recibidoLiberacion = T))),
            // Tesis con anteproyecto sin marcar
            egresado("MIX003", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T))),
            // Tesis con anteproyecto marcado y sinodal pendiente
            egresado("MIX004", procesos = arrayOf(proceso("Tesis", anteproyectoEnviado = T, marcadoRegistrado = T, sinodalesSolicitados = T))),
            // Tesis con sinodal ya asignado
            egresado("MIX005", procesos = arrayOf(proceso("Tesis", sinodalesSolicitados = T, sinodalesAsignados = T, enviado = T))),
        )

        // Residencia badges
        assertEquals(1, counts["pendientes"], "residencia: 1 por liberar")
        assertEquals(1, counts["aprobados"], "residencia: 1 ya liberada")

        // Anteproyecto badges
        assertEquals(1, counts["anteproyecto"], "anteproyecto: 1 pendiente de marcar")
        assertEquals(2, counts["total_anteproyecto"], "anteproyecto total: 2 (MIX003 + MIX004)")

        // Sinodales badges
        assertEquals(1, counts["sinodales_por_asignar"], "sinodales: 1 pendiente (MIX004)")
        assertEquals(2, counts["total_sinodales"], "sinodales total: 2 (MIX004 + MIX005)")
    }
}
