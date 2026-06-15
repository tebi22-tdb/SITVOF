package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.ConfiguracionInstitucional
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface ConfiguracionInstitucionalRepository : MongoRepository<ConfiguracionInstitucional, ObjectId> {
    fun findFirstByTipo(tipo: String): ConfiguracionInstitucional?
    fun findFirstByTipoAndDepartamentoSlug(tipo: String, slug: String): ConfiguracionInstitucional?
    fun findByTipo(tipo: String): List<ConfiguracionInstitucional>
}
