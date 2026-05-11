package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Catalogo
import com.sit_titulacion.sit.repository.CatalogoRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Siembra los catálogos base (carreras, niveles, modalidades, departamentos) en la colección "catalogos".
 * Los strings de carrera son copias exactas de frontend/src/app/core/datos.ts para no romper
 * el campo datos_personales.carrera ya almacenado en expedientes existentes.
 * Es idempotente: si un ítem ya existe (por tipo + nombre, case-insensitive), se omite.
 */
@Component
@Order(3)
class SeedCatalogosRunner(
    private val catalogoRepository: CatalogoRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SeedCatalogosRunner::class.java)

    override fun run(args: ApplicationArguments) {
        var creados = 0

        // ── Carreras ─────────────────────────────────────────────────────────
        // IMPORTANTE: el espacio al final de "INGENIERIA EN TECNOLOGIA..." es intencional;
        // coincide con el string en datos.ts y con los valores ya guardados en MongoDB.
        val carreras = listOf(
            "INGENIERIA EN AGRONOMÍA",
            "LICENCIATURA EN BIOLOGÍA",
            "INGENIERIA FORESTAL",
            "INGENIERIA INFORMÁTICA",
            "INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ",
            "INGENIERIA EN CIENCIA DE DATOS",
            "INGENIERIA SISTEMAS COMPUTACIONALES",
            "INGENIERIA AMBIENTAL",
            "INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)",
        )
        carreras.forEachIndexed { i, nombre ->
            creados += sembrarSiNoExiste("carrera", nombre, orden = i)
        }

        // ── Niveles ───────────────────────────────────────────────────────────
        listOf("Licenciatura", "Maestría", "Posgrado").forEachIndexed { i, nombre ->
            creados += sembrarSiNoExiste("nivel", nombre, orden = i)
        }

        // ── Modalidades ───────────────────────────────────────────────────────
        data class ModalidadSeed(val nombre: String, val meses: Int?, val esResidencia: Boolean, val esCursoTitulacion: Boolean = false)
        listOf(
            ModalidadSeed("Tesis",                     meses = 12,   esResidencia = false),
            ModalidadSeed("Tesina",                    meses = 12,   esResidencia = false),
            ModalidadSeed("Residencia Profesional",    meses = 6,    esResidencia = true),
            ModalidadSeed("CENEVAL",                   meses = null, esResidencia = false),
            ModalidadSeed("Proyecto de Investigación", meses = 12,   esResidencia = false),
            ModalidadSeed("Monografía",                meses = 12,   esResidencia = false, esCursoTitulacion = true),
        ).forEachIndexed { i, m ->
            creados += sembrarSiNoExiste(
                tipo = "modalidad",
                nombre = m.nombre,
                orden = i,
                mesesVigencia = m.meses,
                esResidencia = m.esResidencia,
                esCursoTitulacion = m.esCursoTitulacion,
            )
        }

        // ── Departamentos ─────────────────────────────────────────────────────
        data class DeptSeed(val nombre: String, val slug: String, val carreras: List<String>)
        listOf(
            DeptSeed(
                nombre = "Carreras virtuales",
                slug = "virtuales",
                carreras = listOf(
                    "INGENIERIA SISTEMAS COMPUTACIONALES",
                    "INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)",
                ),
            ),
            DeptSeed(
                nombre = "Ingenierías",
                slug = "ingenierias",
                carreras = listOf(
                    "INGENIERIA EN AGRONOMÍA",
                    "INGENIERIA FORESTAL",
                    "INGENIERIA AMBIENTAL",
                ),
            ),
            DeptSeed(
                nombre = "Departamento Económico-Administrativo",
                slug = "economico_administrativo",
                carreras = listOf(
                    "INGENIERIA INFORMÁTICA",
                    "INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ",
                    "INGENIERIA EN CIENCIA DE DATOS",
                ),
            ),
            DeptSeed(
                nombre = "Departamento de ciencias básicas",
                slug = "ciencias_basicas",
                carreras = listOf("LICENCIATURA EN BIOLOGÍA"),
            ),
        ).forEachIndexed { i, d ->
            creados += sembrarSiNoExiste(
                tipo = "departamento",
                nombre = d.nombre,
                orden = i,
                slug = d.slug,
                carreras = d.carreras,
            )
        }

        if (creados > 0) log.info("SeedCatalogos: {} ítems nuevos creados en colección 'catalogos'", creados)
        else log.debug("SeedCatalogos: todos los catálogos ya existían, nada que sembrar")
    }

    private fun sembrarSiNoExiste(
        tipo: String,
        nombre: String,
        orden: Int = 0,
        mesesVigencia: Int? = null,
        esResidencia: Boolean = false,
        esCursoTitulacion: Boolean = false,
        slug: String? = null,
        carreras: List<String> = emptyList(),
    ): Int {
        if (catalogoRepository.existsByTipoAndNombreIgnoreCase(tipo, nombre)) return 0
        val slugNorm = slug?.trim()?.ifBlank { null }
        if (slugNorm != null && catalogoRepository.countByTipoAndSlug(tipo, slugNorm) > 0) return 0
        catalogoRepository.save(
            Catalogo(
                tipo = tipo,
                nombre = nombre,
                orden = orden,
                mesesVigencia = mesesVigencia,
                esResidencia = esResidencia,
                esCursoTitulacion = esCursoTitulacion,
                slug = slugNorm,
                carreras = carreras,
            ),
        )
        return 1
    }
}
