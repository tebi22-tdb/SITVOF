package com.sit_titulacion.sit.config

import com.sit_titulacion.sit.security.JwtAuthenticationFilter
import com.sit_titulacion.sit.security.LoginRateLimitFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val loginRateLimitFilter: LoginRateLimitFilter,
) {

    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager =
        configuration.authenticationManager

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .headers { headers ->
                headers
                    .frameOptions { it.deny() }
                    .contentTypeOptions { }
                    .httpStrictTransportSecurity { hsts ->
                        hsts.includeSubDomains(true).maxAgeInSeconds(31536000)
                    }
                    .contentSecurityPolicy { csp ->
                        csp.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'")
                    }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/recuperar-password").permitAll()
                    .requestMatchers("/api/auth/hash").permitAll()
                    .requestMatchers("/api/verificar/**").permitAll()
                    .requestMatchers("/api/repositorio/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/catalogos/carreras").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/catalogos/niveles").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/catalogos/modalidades").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/catalogos/departamentos").permitAll()
                    .requestMatchers("/dev/**").permitAll()
                    .requestMatchers("/api/auth/me").authenticated()
                    .requestMatchers("/api/auth/logout").authenticated()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
            }
            .logout { logout ->
                logout.logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler { _, response, _ ->
                        response.contentType = "application/json;charset=UTF-8"
                        response.characterEncoding = "UTF-8"
                        response.writer.write("""{"ok":true}""")
                    }
            }
            .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowCredentials = true
            allowedOrigins = listOf(
                "http://localhost:4200",
                "https://sitvo.net",
                "https://www.sitvo.net",
            )
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "Authorization")
            exposedHeaders = listOf("Authorization", "X-Sinodales-Notificados")
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
