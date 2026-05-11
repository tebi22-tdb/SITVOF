package com.sit_titulacion.sit.service

import com.sit_titulacion.sit.domain.Usuario
import com.sit_titulacion.sit.repository.UsuarioRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom

@Service
class UsuarioService(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    private val log = LoggerFactory.getLogger(UsuarioService::class.java)

    private val random = SecureRandom()

    /** Genera una contraseña aleatoria de 6 dígitos. */
    fun generarPasswordSegura(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$"
        return (1..12).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Crea un usuario egresado: genera contraseña, la hashea, guarda en usuarios.
     * @return Pair(username, passwordPlana) para enviar por correo
     */
    /** True si ya hay un usuario (cualquier rol) con ese nombre de inicio de sesión. */
    fun existeUsuarioConUsername(username: String): Boolean =
        usuarioRepository.existsByUsername(username.trim())

    /** Solo puede reutilizarse el username si no existe o si pertenece a un usuario egresado. */
    fun puedeReutilizarUsernameParaEgresado(username: String): Boolean {
        val existente = usuarioRepository.findByUsername(username.trim()) ?: return true
        return existente.rol.trim().equals("egresado", ignoreCase = true)
    }

    fun crearUsuarioEgresado(numeroControl: String, egresadoId: ObjectId): Pair<String, String> {
        if (usuarioRepository.existsByUsername(numeroControl)) {
            throw IllegalArgumentException("Ya existe un usuario con número de control: $numeroControl")
        }
        val passwordPlana = generarPasswordSegura()
        val passwordHash = passwordEncoder.encode(passwordPlana) ?: ""
        val usuario = Usuario(
            username = numeroControl,
            passwordHash = passwordHash,
            rol = "egresado",
            egresadoId = egresadoId,
            activo = true,
        )
        usuarioRepository.save(usuario)
        log.info("Usuario creado para egresado: username={}, egresadoId={}", numeroControl, egresadoId)
        return Pair(numeroControl, passwordPlana)
    }

    /**
     * Crea el usuario egresado o actualiza el existente con egresadoId (para que el seguimiento cargue).
     * Si el usuario ya existía, también regenera contraseña para reenviar credenciales.
     * @return Pair(username, passwordPlana)
     */
    fun crearOVincularUsuarioEgresado(numeroControl: String, egresadoId: ObjectId): Pair<String, String> {
        val trimmed = numeroControl.trim()
        val existente = usuarioRepository.findByUsername(trimmed)
        return if (existente != null) {
            val passwordPlana = generarPasswordSegura()
            val passwordHash = passwordEncoder.encode(passwordPlana) ?: ""
            val actualizado = existente.copy(
                egresadoId = egresadoId,
                passwordHash = passwordHash,
                fechaActualizacion = java.time.Instant.now(),
            )
            usuarioRepository.save(actualizado)
            log.info("Usuario egresado actualizado con egresadoId y contraseña renovada: username={}", trimmed)
            Pair(trimmed, passwordPlana)
        } else {
            crearUsuarioEgresado(trimmed, egresadoId)
        }
    }

    /**
     * Crea un usuario de personal (coordinador, académico, servicios escolares).
     * Permite repetir correo; el username de acceso siempre se vuelve único.
     */
    fun crearUsuarioStaff(
        nombre: String,
        usernameLogin: String,
        rol: String,
        correoElectronico: String,
        curp: String,
        segmentoAcademico: String? = null,
        carrerasAsignadas: List<String> = emptyList(),
    ): Pair<String, String> {
        val userTrim = usernameLogin.trim()
        val correoTrim = correoElectronico.trim()
        val curpNorm = curp.trim().uppercase()
        if (userTrim.isBlank()) throw IllegalArgumentException("El usuario (para iniciar sesión) es obligatorio.")
        if (correoTrim.isBlank()) throw IllegalArgumentException("El correo electrónico es obligatorio.")
        if (curpNorm.isBlank()) throw IllegalArgumentException("La CURP es obligatoria.")
        val usernameUnico = generarUsernameStaffUnico(userTrim)
        val passwordPlana = generarPasswordSegura()
        val passwordHash = passwordEncoder.encode(passwordPlana) ?: ""
        val usuario = Usuario(
            username = usernameUnico,
            passwordHash = passwordHash,
            rol = rol,
            egresadoId = null,
            nombre = nombre.trim().takeIf { it.isNotBlank() },
            curp = curpNorm,
            correoElectronico = correoTrim,
            segmentoAcademico = segmentoAcademico,
            carrerasAsignadas = carrerasAsignadas,
            activo = true,
        )
        usuarioRepository.save(usuario)
        log.info("Usuario staff creado: username={}, rol={}", usernameUnico, rol)
        return Pair(usernameUnico, passwordPlana)
    }

    fun listarUsuariosStaff(): List<Usuario> =
        usuarioRepository.findAll()
            .filter { !it.rol.trim().equals("egresado", ignoreCase = true) }
            .sortedByDescending { it.fechaCreacion }

    private fun generarUsernameStaffUnico(baseUsername: String): String {
        if (!usuarioRepository.existsByUsername(baseUsername)) return baseUsername
        var intento = 2
        while (true) {
            val candidato = "$baseUsername+$intento"
            if (!usuarioRepository.existsByUsername(candidato)) return candidato
            intento++
        }
    }
}
