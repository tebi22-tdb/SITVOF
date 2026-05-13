package com.sit_titulacion.sit.config

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory

/** Configuración de MongoDB: conexión a sit_titulacion y log al arrancar. */
@Configuration
class MongoConfig(private val env: Environment) {

    private val log = LoggerFactory.getLogger(MongoConfig::class.java)

    companion object {
        const val BASE_DATOS = "sit_titulacion"
    }

    @Bean
    @Primary
    fun mongoClient(): MongoClient {
        val uri = env.getProperty("spring.data.mongodb.uri")
            ?: "mongodb://77.37.74.122:27017/sit_titulacion?connectTimeoutMS=10000&serverSelectionTimeoutMS=5000&socketTimeoutMS=5000"
        return MongoClients.create(uri)
    }

    @Bean
    @Primary
    fun mongoDatabaseFactory(mongoClient: MongoClient) =
        SimpleMongoClientDatabaseFactory(mongoClient, BASE_DATOS)

    @EventListener(ApplicationReadyEvent::class)
    fun logConexion() {
        val uri = env.getProperty("spring.data.mongodb.uri") ?: "(no configurado)"
        log.info("=== MongoDB SITVO === Base: {} | Colecciones: registro, usuarios ===", BASE_DATOS)
        log.info("URI: {}", uri.take(60))
    }
}
