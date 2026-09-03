package com.sit_titulacion.sit.domain

import java.text.Normalizer

/**
 * Única función de normalización de nombres de carrera/departamento — reemplaza
 * las 5 variantes que existían en el proyecto (algunas quitaban acentos, otras
 * no; una hacía matching por substring), fuente confirmada de resoluciones que
 * fallaban en silencio cuando el dato guardado tenía una tilde distinta a la
 * del catálogo. Réplica exacta en el frontend: `catalogo.service.ts` (necesaria
 * ahí porque el navegador no puede llamar a esta función directamente — no hay
 * forma de compartir código entre Kotlin y TypeScript sin un paso de build
 * dedicado, así que se documenta como la ÚNICA duplicación intencional).
 */
object NormalizacionTexto {
    fun normalizarClave(texto: String): String {
        val sinAcentos = Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return sinAcentos.replace(Regex("\\s+"), " ").lowercase()
    }
}
