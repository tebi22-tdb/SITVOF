package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.service.ResidenciaPlazoNotificacionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Revisa diariamente expedientes de residencia y envía avisos de plazo por correo. */
@Component
class ResidenciaPlazoNotificacionScheduler(
    private val residenciaPlazoNotificacionService: ResidenciaPlazoNotificacionService,
) {
    private val log = LoggerFactory.getLogger(ResidenciaPlazoNotificacionScheduler::class.java)

    /** 08:00 hora del servidor (ajustar zona del host o usar cron con TZ institucional). */
    @Scheduled(cron = "\${sit.residencia.notificaciones.cron:0 0 8 * * *}")
    fun ejecutarAvisosPlazoResidencia() {
        log.debug("Inicio tarea: notificaciones de plazo residencia")
        residenciaPlazoNotificacionService.procesarTodosLosExpedientesResidenciaActivos()
    }
}
