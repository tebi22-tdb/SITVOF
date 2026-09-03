package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Rol
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.text.Normalizer

/**
 * Migración de una sola vez: `usuarios.rol` guardaba texto libre en minúsculas
 * (a veces "compuesto", tipo "Coordinador - Administrador"). Ahora `Usuario.rol`
 * es un `Rol` (enum) — Spring Data lo persiste/lee por el NOMBRE de la
 * constante ("COORDINADOR"), no por `valorBd` ("coordinador"). Este runner
 * reescribe cada documento existente al nuevo formato operando sobre
 * `Document` crudo (sin pasar por `Usuario` tipado), porque cualquier lectura
 * tipada de un documento aún no migrado fallaría al deserializar.
 *
 * Por eso corre antes que TODO lo demás que toque `usuarios`
 * (Seed*Runner incluidos): [Ordered.HIGHEST_PRECEDENCE].
 *
 * Idempotente: si `rol` ya es el nombre de una constante válida, no la toca.
 * Si el valor legado venía compuesto, se queda con el primer token que
 * resuelva a un [Rol] (ya no se soportan roles compuestos por usuario). Un
 * valor que no resuelva a ningún rol se deja sin migrar y se loguea como
 * advertencia — no se pierde el documento, pero requiere revisión manual.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RolMigrationRunner(
    private val mongoTemplate: MongoTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(RolMigrationRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val col = mongoTemplate.getCollection("usuarios")
        var migrados = 0
        var sinResolver = 0

        col.find(Document("rol", Document("\$exists", true))).forEach { doc ->
            val valorLegado = doc.getString("rol") ?: return@forEach
            if (esCanonico(valorLegado)) return@forEach

            val rol = tokensRolLegado(valorLegado).firstNotNullOfOrNull { Rol.desdeBd(it) }
            if (rol == null) {
                sinResolver++
                log.warn("usuarios/{}: no se pudo resolver rol legado '{}'; se deja sin migrar.", doc.getObjectId("_id"), valorLegado)
                return@forEach
            }

            col.updateOne(Document("_id", doc.getObjectId("_id")), Document("\$set", Document("rol", rol.name)))
            migrados++
            log.info("usuarios/{}: rol '{}' -> '{}'.", doc.getObjectId("_id"), valorLegado, rol.name)
        }

        when {
            migrados > 0 || sinResolver > 0 -> log.info("RolMigrationRunner: {} usuarios migrados, {} sin resolver.", migrados, sinResolver)
            else -> log.debug("RolMigrationRunner: nada que migrar, todos los roles ya son canónicos.")
        }
    }

    private fun esCanonico(valor: String): Boolean = Rol.entries.any { it.name == valor }

    /**
     * Partes de un valor de rol legado, tolerando compuestos ("Coordinador - Administrador")
     * y guiones tipográficos de Word/Unicode. Lógica que antes vivía en `RolSoporte`
     * (ya eliminado) — solo sobrevive aquí porque esta migración es el único lugar
     * que todavía necesita entender el formato legado.
     *
     * Dos pasadas, igual que el `RolSoporte` original: una que solo corta por
     * "-"/","/"/" (deja intacto "servicios_escolares" como un solo token), y
     * otra más agresiva que también corta por "_" (por si el compuesto viniera
     * pegado con guión bajo). La unión de ambas evita que un rol simple de una
     * sola palabra-con-guión-bajo (como "servicios_escolares") se destroce en
     * fragmentos ("servicios", "escolares") que no matchean ningún [Rol].
     */
    private fun tokensRolLegado(rol: String): Set<String> {
        val normalizado = Normalizer.normalize(rol.trim().lowercase(), Normalizer.Form.NFKC)
            .map { ch -> if (ch in GUIONES_TIPOGRAFICOS) '-' else ch }
            .joinToString("")
        val porGuionComa = separadoresCompuestos.split(normalizado)
            .map { it.trim().replace(' ', '_') }
            .filter { it.isNotEmpty() }
        val porBloques = separadoresAgresivos.split(normalizado)
            .map { it.trim().replace(' ', '_') }
            .filter { it.isNotEmpty() }
        return (porGuionComa + porBloques).toSet()
            .ifEmpty { setOf(normalizado.replace(' ', '_')) }
    }

    private companion object {
        // Guiones "tipográficos" (Word, Unicode) que no coinciden con `-` ASCII.
        val GUIONES_TIPOGRAFICOS = setOf('‐', '‑', '‒', '–', '—', '−', '⁃')
        val separadoresCompuestos = Regex("""\s*-\s*|\s*,\s*|\s*/\s*""")
        val separadoresAgresivos = Regex("""[\s/,\-_.]+""")
    }
}
