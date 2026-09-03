package com.sit_titulacion.sit.repository

import com.sit_titulacion.sit.domain.Usuario
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface UsuarioRepository : MongoRepository<Usuario, ObjectId> {

    fun findByUsername(username: String): Usuario?

    fun existsByUsername(username: String): Boolean

    fun findByEgresadoId(egresadoId: ObjectId): Usuario?

    fun findByCorreoElectronico(correoElectronico: String): Usuario?
}
