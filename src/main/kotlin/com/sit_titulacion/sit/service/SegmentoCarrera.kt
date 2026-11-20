package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.NormalizacionTexto
import com.sit_titulacion.sit.repository.CatalogoRepository

/** Slug de departamento académico al que pertenece la carrera, el mismo del usuario de ese departamento. */
internal fun CatalogoRepository.segmentoDeCarrera(carrera: String?): String? {
    val clave = NormalizacionTexto.normalizarClave(carrera?.trim().orEmpty())
    if (clave.isEmpty()) return null
    return findByTipoAndActivoTrue("departamento").firstOrNull { cat ->
        cat.carreras.any { NormalizacionTexto.normalizarClave(it) == clave }
    }?.slug?.trim()?.lowercase()?.ifBlank { null }
}
