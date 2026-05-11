package com.sit_titulacion.sit.web.api

import com.sit_titulacion.sit.repository.EgresadoRepository
import com.sit_titulacion.sit.web.api.dto.TituladoPublicoDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId

@RestController
@RequestMapping("/api/repositorio")
class RepositorioController(private val egresadoRepository: EgresadoRepository) {

    @GetMapping
    fun listar(): ResponseEntity<List<TituladoPublicoDto>> {
        val titulados = egresadoRepository.findByEstadoGeneral("titulado")
            .sortedByDescending { it.fechaCreacionAnexo93 ?: it.fechaCreacion }
            .map { e ->
                val p = e.datos_personales
                val proy = e.datos_proyecto
                val nombre = listOf(p.nombre, p.apellido_paterno, p.apellido_materno)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                val anio = (e.fechaTitulacion ?: e.fechaCreacionAnexo93 ?: e.fechaCreacion)
                    .atZone(ZoneId.systemDefault()).year
                TituladoPublicoDto(
                    nombre = nombre.ifBlank { "—" },
                    carrera = p.carrera,
                    nivel = p.nivel,
                    modalidad = proy.modalidad,
                    nombreProyecto = proy.nombre_proyecto,
                    asesorInterno = proy.asesor_interno,
                    asesorExterno = proy.asesor_externo,
                    director = proy.director,
                    asesor1 = proy.asesor_1,
                    asesor2 = proy.asesor_2,
                    anio = anio,
                )
            }
        return ResponseEntity.ok(titulados)
    }
}
