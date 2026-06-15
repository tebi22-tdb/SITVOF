package com.sit_titulacion.sit.config

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.sit_titulacion.sit.domain.Usuario
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/**
 * Elimina el índice legado `usuario_1` (campo `usuario`) que choca con el modelo actual (`username`).
 * Sin esto, Mongo solo permite un documento con `usuario: null` y falla el seed al arrancar.
 */
@Component
@Order(0)
class MongoUsuariosIndexInitializer(
    private val mongoTemplate: MongoTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(MongoUsuariosIndexInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        val indexOps = mongoTemplate.indexOps(Usuario::class.java)
        for (legacy in listOf("usuario_1", "usuario")) {
            try {
                indexOps.dropIndex(legacy)
                log.info("Índice obsoleto '{}' eliminado de usuarios", legacy)
            } catch (_: Exception) {
                // no existe
            }
        }

        migrarCampoUsuarioALegacyUsername()

        try {
            mongoTemplate.getCollection("usuarios").createIndex(
                Indexes.ascending("username"),
                IndexOptions().unique(true).name("username_1"),
            )
        } catch (ex: Exception) {
            log.warn("No se pudo asegurar índice username_1: {}", ex.message)
        }
    }

    private fun migrarCampoUsuarioALegacyUsername() {
        val col = mongoTemplate.getCollection("usuarios")
        val cursor = col.find(
            Document("usuario", Document("\$exists", true))
                .append("username", Document("\$exists", false)),
        )
        var migrados = 0
        cursor.forEach { doc ->
            val login = doc.getString("usuario")?.trim().orEmpty()
            if (login.isNotEmpty()) {
                col.updateOne(
                    Document("_id", doc.getObjectId("_id")),
                    Document("\$set", Document("username", login)),
                )
                migrados++
            }
        }
        if (migrados > 0) {
            log.info("Migrados {} usuarios: campo legacy 'usuario' → 'username'", migrados)
        }
    }
}
