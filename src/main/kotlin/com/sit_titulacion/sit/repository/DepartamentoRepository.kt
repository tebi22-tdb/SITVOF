package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Departamento
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface DepartamentoRepository : MongoRepository<Departamento, ObjectId> {
    fun findBySlug(slug: String): Departamento?
    fun findByActivoTrue(): List<Departamento>
    fun findByCarreraIdsContains(carreraId: ObjectId): List<Departamento>
}
