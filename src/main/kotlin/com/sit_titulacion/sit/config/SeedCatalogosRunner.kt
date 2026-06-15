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
            ModalidadSeed("Tesina",                    meses = 12,   esResidencia = false, esCursoTitulacion = true),
            ModalidadSeed("Residencia Profesional",    meses = 6,    esResidencia = true),
            ModalidadSeed("CENEVAL",                   meses = null, esResidencia = false),
            ModalidadSeed("Proyecto de Investigación", meses = 12,   esResidencia = false, esCursoTitulacion = true),
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

        // Migración: corregir esCursoTitulacion en modalidades ya existentes con valor incorrecto
        listOf("Tesina", "Proyecto de Investigación").forEach { nombre ->
            val cat = catalogoRepository.findFirstByTipoAndNombreIgnoreCase("modalidad", nombre)
            if (cat != null && !cat.esCursoTitulacion) {
                catalogoRepository.save(cat.copy(esCursoTitulacion = true))
                log.info("SeedCatalogos: modalidad '{}' actualizada → esCursoTitulacion=true", nombre)
            }
        }

        // ── Departamentos ─────────────────────────────────────────────────────
        // Eliminar departamentos fantasma (slugs que ya no forman parte del catálogo oficial)
        listOf("veterinaria").forEach { slugFantasma ->
            val fantasmas = catalogoRepository.findAll().filter {
                it.tipo == "departamento" && it.slug == slugFantasma
            }
            fantasmas.forEach { f ->
                catalogoRepository.delete(f)
                log.warn("SeedCatalogos: eliminado departamento fantasma slug='{}' id={}", slugFantasma, f.id)
            }
        }

        // Eliminar duplicados de slug que pudieran haberse creado en ejecuciones anteriores
        val slugsConocidos = listOf("virtuales", "ingenierias", "economico_administrativo", "ciencias_basicas")
        slugsConocidos.forEach { slug ->
            val todos = catalogoRepository.findByTipoAndActivoTrue("departamento").filter { it.slug == slug }
            if (todos.size > 1) {
                val conservar = todos.first()
                todos.drop(1).forEach { dup ->
                    catalogoRepository.delete(dup)
                    log.warn("SeedCatalogos: eliminado duplicado de departamento slug='{}' id={}", slug, dup.id)
                }
            }
        }

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
            val existente = catalogoRepository.findFirstByTipoAndNombreIgnoreCase("departamento", d.nombre)
            if (existente != null) {
                // Siempre actualizar slug y carreras; pueden haber cambiado desde el primer seed
                val necesitaActualizar = existente.slug != d.slug || existente.carreras != d.carreras || existente.orden != i
                if (necesitaActualizar) {
                    catalogoRepository.save(existente.copy(slug = d.slug, carreras = d.carreras, orden = i))
                    log.info("SeedCatalogos: departamento '{}' actualizado (slug/carreras)", d.nombre)
                }
            } else {
                creados += sembrarSiNoExiste(
                    tipo = "departamento",
                    nombre = d.nombre,
                    orden = i,
                    slug = d.slug,
                    carreras = d.carreras,
                )
            }
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
