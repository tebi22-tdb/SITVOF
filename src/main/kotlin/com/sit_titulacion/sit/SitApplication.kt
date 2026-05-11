package com.sit_titulacion.sit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class SitApplication

fun main(args: Array<String>) {
	runApplication<SitApplication>(*args)
}
