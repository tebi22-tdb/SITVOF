package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.DocumentacionEscaneada
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface DocumentacionEscaneadaRepository : MongoRepository<DocumentacionEscaneada, ObjectId> {
    fun findByEgresadoId(egresadoId: ObjectId): DocumentacionEscaneada?
}
