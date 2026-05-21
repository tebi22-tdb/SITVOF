package com.sit_titulacion.sit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
open class SitApplication

fun main(args: Array<String>) {
	runApplication<SitApplication>(*args)
}
