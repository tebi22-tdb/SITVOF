package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Catalogo
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CatalogoRepository : MongoRepository<Catalogo, ObjectId> {
    fun findByTipoAndActivoTrue(tipo: String): List<Catalogo>

    /**
     * Un solo documento aunque haya duplicados históricos en BD (mismo tipo+slug).
     * [findByTipoAndSlug] fallaba con IncorrectResultSizeDataAccessException si había >1.
     */
    fun findFirstByTipoAndSlug(tipo: String, slug: String): Catalogo?

    fun existsByTipoAndNombreIgnoreCase(tipo: String, nombre: String): Boolean

    /** Cuenta documentos (nunca lanza por “non unique” aunque haya duplicados). */
    fun countByTipoAndSlug(tipo: String, slug: String): Long
}
