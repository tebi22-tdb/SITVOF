package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Docente
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface DocenteRepository : MongoRepository<Docente, ObjectId> {
    fun findByActivoTrue(): List<Docente>
}
