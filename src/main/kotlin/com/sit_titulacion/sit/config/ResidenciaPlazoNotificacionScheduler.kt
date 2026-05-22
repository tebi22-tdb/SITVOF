package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.service.NoResidenciaPlazoNotificacionService
import com.sit_titulacion.sit.service.ResidenciaPlazoNotificacionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Revisa diariamente expedientes y envía avisos de plazo por correo (residencia y tesis/monografía). */
@Component
class ResidenciaPlazoNotificacionScheduler(
    private val residenciaPlazoNotificacionService: ResidenciaPlazoNotificacionService,
    private val noResidenciaPlazoNotificacionService: NoResidenciaPlazoNotificacionService,
) {
    private val log = LoggerFactory.getLogger(ResidenciaPlazoNotificacionScheduler::class.java)

    /** 08:00 hora del servidor (ajustar zona del host o usar cron con TZ institucional). */
    @Scheduled(cron = "\${sit.residencia.notificaciones.cron:0 0 8 * * *}")
    fun ejecutarAvisosPlazoResidencia() {
        log.debug("Inicio tarea: notificaciones de plazo residencia")
        residenciaPlazoNotificacionService.procesarTodosLosExpedientesResidenciaActivos()
    }

    @Scheduled(cron = "\${sit.plazo.no-res.notificaciones.cron:0 0 8 * * *}")
    fun ejecutarAvisosPlazoNoResidencia() {
        log.debug("Inicio tarea: notificaciones de plazo tesis/monografía")
        noResidenciaPlazoNotificacionService.procesarTodosLosExpedientesNoResidenciaActivos()
    }
}
