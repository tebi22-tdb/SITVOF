package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.repository.UsuarioRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class UsuarioDetailsService(
    private val usuarioRepository: UsuarioRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val usuario = usuarioRepository.findByUsername(username)
            ?: throw UsernameNotFoundException("Usuario no encontrado: $username")
        if (!usuario.activo) {
            throw UsernameNotFoundException("Usuario desactivado: $username")
        }
        return UsuarioPrincipal(usuario)
    }
}
