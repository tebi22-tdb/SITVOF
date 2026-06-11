package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.ResidenciaCoordinacion
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ResidenciaCoordinacionRepository : MongoRepository<ResidenciaCoordinacion, ObjectId> {
    @Query("{ 'numero_control': ?0 }")
    fun findByNumeroControl(numeroControl: String): List<ResidenciaCoordinacion>

    @Query("{ 'coordinacion': ?0 }")
    fun findByCoordinacion(coordinacion: String): List<ResidenciaCoordinacion>
}
