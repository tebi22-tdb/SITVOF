package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Carrera
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CarreraRepository : MongoRepository<Carrera, ObjectId> {
    fun findBySlug(slug: String): Carrera?
    fun findByActivoTrue(): List<Carrera>
}
