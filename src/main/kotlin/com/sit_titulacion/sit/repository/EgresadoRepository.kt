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

    /** Búsqueda puntual por _id con límite de tiempo de ejecución en Mongo. */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ '_id' : ?0 }")
    fun findByObjectIdConTimeout(id: ObjectId): Egresado?

    /** Búsqueda por número de control con límite de tiempo. */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'numero_control' : ?0 }")
    fun findByNumeroControl(numeroControl: String): Egresado?

    /** Historial completo por número de control (coincidencia exacta ignorando mayúsculas), más reciente primero. */
    @Meta(maxExecutionTimeMs = 5000)
    @Query(
        value = "{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }",
        sort = "{ 'fecha_creacion' : -1 }",
    )
    fun listAllByNumeroControlExactCaseInsensitive(anchoredPattern: String): List<Egresado>

    /**
     * Coincidencia exacta ignorando mayúsculas (pasar patrón anclado, p. ej. `^${Regex.escape(control)}$`).
     * No usar nombre derivado `findByNumeroControlIgnoreCase`: el dominio usa `numero_control`, no `numeroControl`.
     */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }")
    fun listByNumeroControlRegexAnchoredCaseInsensitive(anchoredPattern: String): List<Egresado>

    /** Buscar por número de control (contiene el texto, sin distinguir mayúsculas). */
    @Query("{ 'numero_control' : { \$regex: ?0, \$options: 'i' } }")
    fun findByNumeroControlContaining(numeroControl: String): List<Egresado>

    /** Buscar por rango de fecha de Anexo XXXI (documentos.anexo_xxxi.fecha_registro). */
    @Query(value = "{ 'documentos.anexo_xxxi.fecha_registro' : { \$gte: ?0, \$lte: ?1 } }", sort = "{ '_id' : -1 }")
    fun findByAnexoXxxiFechaRegistroBetweenOrderByIdDesc(fechaDesde: Instant, fechaHasta: Instant): List<Egresado>

    /** Buscar por número de control Y rango de fecha de Anexo XXXI. */
    @Query(value = "{ 'numero_control' : { \$regex: ?0, \$options: 'i' }, 'documentos.anexo_xxxi.fecha_registro' : { \$gte: ?1, \$lte: ?2 } }", sort = "{ '_id' : -1 }")
    fun findByNumeroControlContainingAndAnexoXxxiFechaRegistroBetween(numeroControl: String, fechaDesde: Instant, fechaHasta: Instant): List<Egresado>

    /** Buscar por rango de fecha de Constancia (documentos.constancia_no_inconveniencia.fecha_expedicion). */
    @Query(value = "{ 'documentos.constancia_no_inconveniencia.fecha_expedicion' : { \$gte: ?0, \$lte: ?1 } }", sort = "{ '_id' : -1 }")
    fun findByConstanciaFechaExpedicionBetweenOrderByIdDesc(fechaDesde: Instant, fechaHasta: Instant): List<Egresado>

    /** Buscar por número de control Y rango de fecha de Constancia. */
    @Query(value = "{ 'numero_control' : { \$regex: ?0, \$options: 'i' }, 'documentos.constancia_no_inconveniencia.fecha_expedicion' : { \$gte: ?1, \$lte: ?2 } }", sort = "{ '_id' : -1 }")
    fun findByNumeroControlContainingAndConstanciaFechaExpedicionBetween(numeroControl: String, fechaDesde: Instant, fechaHasta: Instant): List<Egresado>

    /** Enviados al departamento académico que aún no tienen registro y liberación recibido (pendientes). */
    @Query(value = "{ 'fecha_enviado_departamento_academico' : { \$exists: true, \$ne: null }, 'fecha_recibido_registro_liberacion' : null }", sort = "{ 'fecha_actualizacion' : -1 }")
    fun findByEnviadoDepartamentoPendientes(): List<Egresado>

    /** Enviados al departamento que ya tienen registro y liberación recibido (aprobados). */
    @Query(value = "{ 'fecha_recibido_registro_liberacion' : { \$exists: true, \$ne: null } }", sort = "{ 'fecha_actualizacion' : -1 }")
    fun findByRecibidoRegistroLiberacion(): List<Egresado>

    /** Todos los enviados al departamento (tienen fecha_enviado_departamento_academico). */
    @Query(value = "{ 'fecha_enviado_departamento_academico' : { \$exists: true, \$ne: null } }", sort = "{ 'fecha_actualizacion' : -1 }")
    fun findByEnviadoDepartamentoTodos(): List<Egresado>

    /** Citas que se solapan con el intervalo solicitado para acto 9.3. */
    @Query(value = "{ 'fecha_agenda_acto_9_3' : { \$gt: ?0, \$lt: ?1 } }")
    fun findByFechaAgendaActo93Solapando(inicioExclusivo: Instant, finExclusivo: Instant): List<Egresado>

    /** Todas las fechas ya agendadas del acto 9.3 (para pintar ocupados en frontend). */
    @Query(value = "{ 'fecha_agenda_acto_9_3' : { \$exists: true, \$ne: null } }", sort = "{ 'fecha_agenda_acto_9_3' : 1 }")
    fun findConActo93Agendado(): List<Egresado>

    /** Fechas agendadas en una ventana acotada para el calendario (solo campo necesario, con timeout). */
    @Meta(maxExecutionTimeMs = 4000)
    @Query(
        value = "{ 'fecha_agenda_acto_9_3' : { \$gte: ?0, \$lt: ?1 } }",
        fields = "{ 'fecha_agenda_acto_9_3' : 1 }",
        sort = "{ 'fecha_agenda_acto_9_3' : 1 }",
    )
    fun findActo93AgendadoEnRango(inicioInclusivo: Instant, finExclusivo: Instant): List<Egresado>

    /** Buscar por UUID de certificación para verificación pública. */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'cert_uuid' : ?0 }")
    fun findByCertUuid(certUuid: String): Egresado?

    /** Repositorio público: egresados por estado_general (p. ej. "titulado"). */
    @Meta(maxExecutionTimeMs = 5000)
    @Query("{ 'estado_general' : ?0 }")
    fun findByEstadoGeneral(estado: String): List<Egresado>
}
