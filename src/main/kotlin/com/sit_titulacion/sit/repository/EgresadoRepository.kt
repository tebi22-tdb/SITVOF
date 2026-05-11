package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Egresado
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Meta
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface EgresadoRepository : MongoRepository<Egresado, ObjectId> {

    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ '_id' : ?0 }")
    fun findByObjectIdConTimeout(id: ObjectId): Egresado?

    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'numero_control' : ?0 }")
    fun findByNumeroControl(numeroControl: String): Egresado?

    @Meta(maxExecutionTimeMs = 5000)
    @Query(
        value = "{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }",
        sort = "{ 'fecha_creacion' : -1 }",
    )
    fun listAllByNumeroControlExactCaseInsensitive(anchoredPattern: String): List<Egresado>

    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }")
    fun listByNumeroControlRegexAnchoredCaseInsensitive(anchoredPattern: String): List<Egresado>

    @Query("{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }")
    fun findByNumeroControlContaining(numeroControl: String): List<Egresado>

    /** Citas que se solapan con el intervalo solicitado (busca en cualquier proceso del array). */
    @Query(value = "{ 'procesos.fecha_agenda_acto_9_3' : { \$gt: ?0, \$lt: ?1 } }")
    fun findByFechaAgendaActo93Solapando(inicioExclusivo: Instant, finExclusivo: Instant): List<Egresado>

    /** Fechas agendadas en una ventana acotada (busca en cualquier proceso del array). */
    @Meta(maxExecutionTimeMs = 4000)
    @Query(
        value = "{ 'procesos.fecha_agenda_acto_9_3' : { \$gte: ?0, \$lt: ?1 } }",
        sort = "{ 'fecha_actualizacion' : 1 }",
    )
    fun findActo93AgendadoEnRango(inicioInclusivo: Instant, finExclusivo: Instant): List<Egresado>

    /** Verificación pública: busca por cert_uuid en cualquier proceso del array. */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'procesos.cert_uuid' : ?0 }")
    fun findByCertUuid(certUuid: String): Egresado?

    /** Egresados titulados (proceso activo con estado_general = "titulado"). */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'procesos.estado_general' : ?0 }")
    fun findByEstadoGeneral(estado: String): List<Egresado>
}
