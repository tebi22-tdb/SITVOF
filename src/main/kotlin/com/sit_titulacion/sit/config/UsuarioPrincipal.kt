package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Usuario
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Adaptador de Usuario (MongoDB) a UserDetails para Spring Security.
 */
class UsuarioPrincipal(private val usuario: Usuario) : UserDetails {

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
        val rol = usuario.rol.uppercase().replace("-", "_")
        return mutableListOf(SimpleGrantedAuthority("ROLE_$rol"))
    }

    override fun getPassword(): String = usuario.passwordHash

    override fun getUsername(): String = usuario.username

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = usuario.activo

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = usuario.activo

    fun getRol(): String = usuario.rol

    fun getEgresadoId(): org.bson.types.ObjectId? = usuario.egresadoId

    fun getNombre(): String? = usuario.nombre

    fun getSegmentoAcademico(): String? = usuario.segmentoAcademico

    fun getCarrerasAsignadas(): List<String> = usuario.carrerasAsignadas
}
