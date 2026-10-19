package com.sit_titulacion.sit.domain

/**
 * Roles de acceso reales del sistema.
 *
 * Reemplaza el `String` libre que tenía `Usuario.rol` — antes cualquier texto
 * se guardaba tal cual, sin que el compilador avisara de un typo ni existiera
 * una lista fija de qué roles hay. Un solo rol por usuario: ya no se soportan
 * roles "compuestos" tipo "Coordinador - Administrador" (ver RolMigrationRunner
 * para la migración de los que ya existan en BD con ese formato).
 *
 * Consolidación de roles (sep-2026, ver RolConsolidacionRunner): antes había
 * 8 roles y 2 de ellos casi duplicados (`administrador` y
 * `division_estudios_prof_admin`, ambos "solo gestión de cuentas" con
 * inconsistencias entre sí). Quedaron 7:
 * - `coordinador` → renombrado a [ADMINISTRADOR] (mismo acceso pleno de siempre).
 * - `administrador` (el viejo, restringido) y `division_estudios_prof_admin`
 *   → fusionados en [DEP]: solo Inicio del proceso y Seguimiento del proceso.
 *
 * `jefe_division` no está aquí: no es un rol de acceso, es un dato de firma
 * dentro de ConfiguracionInstitucional.
 */
enum class Rol(val valorBd: String) {
    ADMINISTRADOR("administrador"),
    DEP("dep"),
    ACADEMICO("academico"),
    EGRESADO("egresado"),
    APOYO_TITULACION("apoyo_titulacion"),
    SERVICIOS_ESCOLARES("servicios_escolares"),
    SUBDIRECCION_ACADEMICA("subdireccion_academica");

    /** Azúcar para los checks de "¿tiene alguno de estos roles?" que antes usaban RolSoporte.tieneAlgunRol. */
    fun esAlgunoDe(vararg roles: Rol): Boolean = this in roles

    companion object {
        /** Busca el Rol cuyo valorBd coincida con [valor] (ignora mayúsculas/espacios). Null si no matchea ninguno. */
        fun desdeBd(valor: String?): Rol? {
            val normalizado = valor?.trim()?.lowercase()
            if (normalizado.isNullOrEmpty()) return null
            return entries.firstOrNull { it.valorBd == normalizado }
        }
    }
}
