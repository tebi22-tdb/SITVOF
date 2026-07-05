package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Revision
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface RevisionRepository : MongoRepository<Revision, ObjectId> {

    @Query(value = "{ 'egresado_id' : ?0, 'proceso_id' : ?1 }", sort = "{ 'numero_revision' : -1, 'fecha' : -1 }")
    fun findByEgresadoIdAndProcesoIdOrderByNumeroRevisionDesc(egresadoId: ObjectId, procesoId: ObjectId): List<Revision>

    @Query(value = "{ 'egresado_id' : ?0, 'proceso_id' : ?1, 'enviado_al_egresado' : true }", sort = "{ 'fecha_envio_egresado' : -1, 'fecha' : -1 }")
    fun findEnviadasAlEgresadoPorProceso(egresadoId: ObjectId, procesoId: ObjectId): List<Revision>

    @Query("{ '_id' : ?0, 'egresado_id' : ?1 }")
    fun findByIdAndEgresadoId(id: ObjectId, egresadoId: ObjectId): Revision?

    @Query("{ 'egresado_id' : { \$in: ?0 } }")
    fun findByEgresadoIdIn(egresadoIds: Set<ObjectId>): List<Revision>

    fun countByEgresadoIdAndProcesoId(egresadoId: ObjectId, procesoId: ObjectId): Long
}
