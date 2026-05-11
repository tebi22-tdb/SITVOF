package com.sit_titulacion.sit.web

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class RootController {

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun index(): String = """
        <!DOCTYPE html>
        <html>
        <head><meta charset="UTF-8"><title>SITVO - API</title></head>
        <body style="font-family: sans-serif; max-width: 600px; margin: 2rem auto; padding: 0 1rem;">
            <h1>SITVO - Sistema Integral de Titulación</h1>
            <p>Este es el <strong>backend</strong> (API). Aquí no se muestra la aplicación.</p>
            <p>Para usar el sistema, abre el <strong>frontend</strong> en:</p>
            <p><a href="http://localhost:4200" style="font-size: 1.2rem;">http://localhost:4200</a></p>
            <p><small>Asegúrate de tener Angular corriendo con <code>npm start</code> en la carpeta <code>frontend</code>.</small></p>
        </body>
        </html>
    """.trimIndent()
}
