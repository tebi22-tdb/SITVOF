package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Carrera
import com.sit_titulacion.sit.domain.Departamento
import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.repository.CarreraRepository
import com.sit_titulacion.sit.repository.CatalogoRepository
import com.sit_titulacion.sit.repository.DepartamentoRepository
import com.sit_titulacion.sit.repository.EgresadoRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Sincroniza, en cada arranque, las colecciones `carreras`/`departamentos`
 * (con id estable) a partir de `Catalogo(tipo="carrera"/"departamento")`, que
 * sigue siendo donde se administra (alta/edición/baja vía `/api/catalogos`,
 * pantalla de coordinador). Es decir: `Catalogo` es la fuente editable,
 * `Carrera`/`Departamento` son una proyección de solo lectura para el resto
 * del sistema — así se gana un id estable y una única normalización sin
 * reescribir la pantalla de administración de catálogos.
 *
 * Corre después de `SeedCatalogosRunner` (`@Order(3)`) para que ya existan
 * los catálogos base en una instalación nueva. Idempotente: solo escribe si
 * el nombre/orden/carreraIds resueltos cambiaron.
 *
 * También backfillea `Egresado.datos_personales.carreraId`/`departamentoId`:
 * referencias resueltas ADITIVAS (el campo `carrera` en texto libre no se
 * toca) para que reportes/agregaciones futuras puedan filtrar/agrupar por id
 * en vez de repetir la normalización de texto en cada consulta.
 */
@Component
@Order(4)
class CarreraDepartamentoSyncRunner(
    private val catalogoRepository: CatalogoRepository,
    private val carreraRepository: CarreraRepository,
    private val departamentoRepository: DepartamentoRepository,
    private val egresadoRepository: EgresadoRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(CarreraDepartamentoSyncRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val slugACarrera = sincronizarCarreras()
        val carreraIdADepartamentoId = sincronizarDepartamentos(slugACarrera)
        backfillEgresados(slugACarrera, carreraIdADepartamentoId)
    }

    private fun sincronizarCarreras(): Map<String, Carrera> {
        val slugACarrera = mutableMapOf<String, Carrera>()
        var creadas = 0
        var actualizadas = 0
        catalogoRepository.findByTipoAndActivoTrue("carrera").forEachIndexed { i, cat ->
            val slug = NormalizacionTexto.normalizarClave(cat.nombre)
            if (slug.isEmpty()) return@forEachIndexed
            val existente = carreraRepository.findBySlug(slug)
            val carrera = when {
                existente == null -> {
                    creadas++
                    carreraRepository.save(Carrera(nombre = cat.nombre, slug = slug, orden = i))
                }
                existente.nombre != cat.nombre || existente.orden != i || !existente.activo -> {
                    actualizadas++
                    carreraRepository.save(existente.copy(nombre = cat.nombre, orden = i, activo = true))
                }
                else -> existente
            }
            slugACarrera[slug] = carrera
        }
        if (creadas > 0 || actualizadas > 0) {
            log.info("CarreraDepartamentoSyncRunner: carreras {} creadas, {} actualizadas.", creadas, actualizadas)
        }
        return slugACarrera
    }

    private fun sincronizarDepartamentos(slugACarrera: Map<String, Carrera>): Map<ObjectId, ObjectId> {
        val carreraIdADepartamentoId = mutableMapOf<ObjectId, ObjectId>()
        var creados = 0
        var actualizados = 0
        catalogoRepository.findByTipoAndActivoTrue("departamento").forEachIndexed { i, cat ->
            val slugDepto = cat.slug?.trim()?.lowercase()?.ifBlank { null } ?: return@forEachIndexed
            val carreraIds = cat.carreras.mapNotNull { nombreCarrera ->
                slugACarrera[NormalizacionTexto.normalizarClave(nombreCarrera)]?.id
            }
            val existente = departamentoRepository.findBySlug(slugDepto)
            val depto = when {
                existente == null -> {
                    creados++
                    departamentoRepository.save(
                        Departamento(nombre = cat.nombre, slug = slugDepto, carreraIds = carreraIds, orden = i),
                    )
                }
                existente.nombre != cat.nombre || existente.carreraIds != carreraIds || existente.orden != i || !existente.activo -> {
                    actualizados++
                    departamentoRepository.save(
                        existente.copy(nombre = cat.nombre, carreraIds = carreraIds, orden = i, activo = true),
                    )
                }
                else -> existente
            }
            carreraIds.forEach { carreraIdADepartamentoId[it] = depto.id }
        }
        if (creados > 0 || actualizados > 0) {
            log.info("CarreraDepartamentoSyncRunner: departamentos {} creados, {} actualizados.", creados, actualizados)
        }
        return carreraIdADepartamentoId
    }

    private fun backfillEgresados(slugACarrera: Map<String, Carrera>, carreraIdADepartamentoId: Map<ObjectId, ObjectId>) {
        var actualizados = 0
        egresadoRepository.findAll().forEach { e ->
            val dp = e.datos_personales
            val carreraId = slugACarrera[NormalizacionTexto.normalizarClave(dp.carrera)]?.id
            val departamentoId = carreraId?.let { carreraIdADepartamentoId[it] }
            if (dp.carreraId != carreraId || dp.departamentoId != departamentoId) {
                egresadoRepository.save(e.copy(datos_personales = dp.copy(carreraId = carreraId, departamentoId = departamentoId)))
                actualizados++
            }
        }
        if (actualizados > 0) {
            log.info("CarreraDepartamentoSyncRunner: {} egresados con carreraId/departamentoId actualizado.", actualizados)
        }
    }
}
