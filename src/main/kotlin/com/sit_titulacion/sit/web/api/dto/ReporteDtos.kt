package com.sit_titulacion.sit.web.api.dto

data class ReporteFiltrosAplicadosDto(
    val fecha_desde: String?,
    val fecha_hasta: String?,
    val modalidad: String?,
    val carrera: String?,
    /** Año calculado a partir de `fecha_creacion` del proceso — no existe como campo real en ningún modelo. */
    val generacion: String?,
)

data class ReporteConteoDto(
    val etiqueta: String,
    val cantidad: Int,
    val porcentaje: Double,
)

data class ReporteTiempoEtapaDto(
    /** Clave estable para el frontend, ej. "registro_a_envio_departamento". */
    val etapa: String,
    val etiqueta: String,
    /** null si ningún proceso filtrado tiene ambas fechas de la etapa. */
    val dias_promedio: Double?,
    /** Cuántos procesos entraron al promedio — importante para no malinterpretar un promedio con muestra chica. */
    val muestra: Int,
)

data class ReporteTasaAprobacionDto(
    val total_primeras_revisiones: Int,
    val aprobadas_en_primera_revision: Int,
    val porcentaje: Double,
)

/**
 * De los egresados del filtro (uno por persona — el proceso *activo* de cada
 * uno, nunca se suman los intentos previos), cuántos ya llegaron a
 * `estado_general = "titulado"`. Un egresado que venció su plazo y luego se
 * tituló con otra modalidad cuenta una sola vez, como titulado — el mismo
 * `Egresado` solo tiene un proceso activo a la vez (ver KDoc de
 * `ProcesoTitulacion`), así que no hace falta deduplicar por número de
 * control aparte.
 */
data class ReporteTasaTitulacionDto(
    val total_procesos: Int,
    val titulados: Int,
    val porcentaje: Double,
)

/** Un punto de la tendencia mensual — cuántos procesos se registraron ese mes. */
data class ReporteTendenciaMensualDto(
    /** "yyyy-MM", clave estable/ordenable para el frontend. */
    val mes: String,
    /** Ej. "Ene 2026", ya lista para mostrar. */
    val etiqueta: String,
    val cantidad: Int,
)

/**
 * Un hito del embudo de avance — cuántos procesos (del total filtrado) ya
 * tienen esa fecha llena. Cada hito se cuenta de forma independiente, no
 * depende de que el hito anterior también esté lleno.
 */
data class ReporteEmbudoEtapaDto(
    val clave: String,
    val etiqueta: String,
    val cantidad: Int,
)

data class ReporteResumenCoordinadorDto(
    val filtros: ReporteFiltrosAplicadosDto,
    val total_procesos: Int,
    /** "registrado" | "titulado" | "vencido" — los únicos 3 valores reales de estado_general. */
    val conteo_por_estado_general: Map<String, Int>,
    /** "en_tiempo" | "rezagado" | "vencido" | "concluido" — mismo cálculo que usa el dashboard (Fase 0.2). */
    val conteo_por_estado_plazo: Map<String, Int>,
    val distribucion_modalidad: List<ReporteConteoDto>,
    val distribucion_carrera: List<ReporteConteoDto>,
    val tiempos_promedio_etapa_dias: List<ReporteTiempoEtapaDto>,
    val tasa_aprobacion_primera_revision: ReporteTasaAprobacionDto,
    val tasa_titulacion: ReporteTasaTitulacionDto,
    /** Orden cronológico ascendente. */
    val tendencia_mensual: List<ReporteTendenciaMensualDto>,
    /** Orden = secuencia real del proceso (no por tamaño) — ver [ReporteEmbudoEtapaDto]. */
    val embudo_etapas: List<ReporteEmbudoEtapaDto>,
)

/**
 * Una fila del listado de egresados — el directorio consultable que
 * reemplaza los resúmenes agregados de "por departamento"/"servicios
 * escolares". `generacion_ingreso` es null cuando el número de control no
 * matchea el patrón esperado (ver `domain/NumeroControl`) — no se descarta
 * al egresado, solo no puede filtrarse/encontrarse por generación.
 */
data class ReporteEgresadoListadoDto(
    val id: String,
    val nombre_completo: String,
    val numero_control: String,
    val carrera: String,
    val departamento_nombre: String?,
    val generacion_ingreso: Int?,
    val modalidad: String,
    /** "registrado" | "titulado" | "vencido". */
    val estado_general: String,
    /** "en_tiempo" | "rezagado" | "vencido" | "concluido". */
    val estado_plazo: String,
    val fecha_titulacion: String?,
)
