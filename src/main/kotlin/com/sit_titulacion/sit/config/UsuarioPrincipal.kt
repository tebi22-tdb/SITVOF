package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.domain.Rol
import com.sit_titulacion.sit.domain.Usuario
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Adaptador de Usuario (MongoDB) a UserDetails para Spring Security.
 */
class UsuarioPrincipal(private val usuario: Usuario) : UserDetails {

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
        // Antes normalizaba el String a mano (con una lógica distinta a RolSoporte,
        // fuente de una inconsistencia ya detectada). El enum ya es consistente por construcción.
        return mutableListOf(SimpleGrantedAuthority("ROLE_${usuario.rol.name}"))
    }

    override fun getPassword(): String = usuario.passwordHash

    override fun getUsername(): String = usuario.username

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = usuario.activo

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = usuario.activo

    fun getRol(): Rol = usuario.rol

    fun getEgresadoId(): org.bson.types.ObjectId? = usuario.egresadoId

    fun getNombre(): String? = usuario.nombre

    fun getSegmentoAcademico(): String? = usuario.segmentoAcademico

    fun getCarrerasAsignadas(): List<String> = usuario.carrerasAsignadas
}
