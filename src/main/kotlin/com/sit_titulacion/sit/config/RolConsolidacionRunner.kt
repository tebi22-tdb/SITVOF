package com.sit_titulacion.sit.config

import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/**
 * Migración de una sola vez (sep-2026): consolida los 8 roles legados en los
 * 7 de [com.sit_titulacion.sit.domain.Rol] actuales.
 * - `"COORDINADOR"` -> `"ADMINISTRADOR"` (mismo acceso pleno, solo cambia el nombre).
 * - `"ADMINISTRADOR"` (el viejo, restringido a cuentas) y
 *   `"DIVISION_ESTUDIOS_PROF_ADMIN"` -> `"DEP"` (fusionados).
 *
 * Ojo con el orden: el nombre `"ADMINISTRADOR"` se reutiliza con un significado
 * distinto (antes = solo cuentas, ahora = acceso pleno). Si este runner corriera
 * en cada arranque sin marcarse, la segunda vez movería a DEP también a los
 * usuarios que legítimamente ya son el nuevo ADMINISTRADOR (ex-coordinador) —
 * una regresión de privilegios silenciosa. Por eso queda un documento marcador
 * en `_migraciones` y el runner no vuelve a tocar `usuarios` una vez que existe.
 *
 * Corre después de [RolMigrationRunner] (que ya deja todo en nombres de enum
 * canónicos) y antes de cualquier Seed*Runner.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RolConsolidacionRunner(
    private val mongoTemplate: MongoTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(RolConsolidacionRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val marcadores = mongoTemplate.getCollection("_migraciones")
        val col = mongoTemplate.getCollection("usuarios")
        val yaEjecutada = marcadores.find(Document("_id", MARCADOR_ID)).first() != null

        if (!yaEjecutada) {
            // Orden estricto: primero vaciar el nombre "ADMINISTRADOR" hacia DEP,
            // y solo después reocuparlo con los ex-coordinador.
            val viejoAdminADep = col.updateMany(Document("rol", "ADMINISTRADOR"), Document("\$set", Document("rol", "DEP"))).modifiedCount
            val divisionADep = col.updateMany(Document("rol", "DIVISION_ESTUDIOS_PROF_ADMIN"), Document("\$set", Document("rol", "DEP"))).modifiedCount
            val coordinadorAAdmin = col.updateMany(Document("rol", "COORDINADOR"), Document("\$set", Document("rol", "ADMINISTRADOR"))).modifiedCount

            marcadores.insertOne(Document("_id", MARCADOR_ID).append("ejecutada", java.time.Instant.now().toString()))
            log.info(
                "RolConsolidacionRunner: {} administrador(viejo)->dep, {} division_estudios_prof_admin->dep, {} coordinador->administrador.",
                viejoAdminADep, divisionADep, coordinadorAAdmin,
            )
        } else {
            log.debug("RolConsolidacionRunner: consolidación principal ya marcada; solo se limpian rezagos.")
        }

        // Rezagos en minúsculas o intermedios que quedaron si la 1ª migración falló a medias.
        val rezagoCoord = col.updateMany(Document("rol", "coordinador"), Document("\$set", Document("rol", "ADMINISTRADOR"))).modifiedCount +
            col.updateMany(Document("rol", "COORDINADOR"), Document("\$set", Document("rol", "ADMINISTRADOR"))).modifiedCount
        val rezagoDiv = col.updateMany(Document("rol", "division_estudios_prof_admin"), Document("\$set", Document("rol", "DEP"))).modifiedCount +
            col.updateMany(Document("rol", "DIVISION_ESTUDIOS_PROF_ADMIN"), Document("\$set", Document("rol", "DEP"))).modifiedCount
        if (rezagoCoord > 0 || rezagoDiv > 0) {
            log.info("RolConsolidacionRunner: rezagos corregidos — coordinador->admin={}, division->dep={}.", rezagoCoord, rezagoDiv)
        }
    }

    private companion object {
        const val MARCADOR_ID = "rol-consolidacion-administrador-dep"
    }
}
