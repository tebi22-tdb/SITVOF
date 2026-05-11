package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Revision
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface RevisionRepository : MongoRepository<Revision, ObjectId> {

    /** Todas las revisiones del egresado, más reciente primero (por número y fecha). */
    @Query(value = "{ 'egresado_id' : ?0 }", sort = "{ 'numero_revision' : -1, 'fecha' : -1 }")
    fun findByEgresadoIdOrderByNumeroRevisionDesc(egresadoId: ObjectId): List<Revision>

    @Query(value = "{ 'egresado_id' : ?0, 'enviado_al_egresado' : true }", sort = "{ 'fecha_envio_egresado' : -1, 'fecha' : -1 }")
    fun findEnviadasAlEgresado(egresadoId: ObjectId): List<Revision>

    @Query("{ '_id' : ?0, 'egresado_id' : ?1 }")
    fun findByIdAndEgresadoId(id: ObjectId, egresadoId: ObjectId): Revision?

    fun countByEgresadoId(egresadoId: ObjectId): Long
}
