package org.example.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * API stateless sécurisée par les JWT du realm Keycloak (spec §4.5) :
 * - lecture publique des tournois (rôle Visiteur)
 * - écriture authentifiée ; les rôles realm (player/organizer/admin)
 *   sont exposés en autorités ROLE_* pour les @PreAuthorize à venir.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @param:Value("\${app.cors.allowed-origins}") private val allowedOrigins: List<String>,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    // Une ligne par version : une nouvelle version d'API ne doit pas
                    // hériter silencieusement de l'accès public (docs/API-VERSIONING.md).
                    .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { it.jwtAuthenticationConverter(keycloakJwtConverter()) }
            }
        return http.build()
    }

    /** Extrait realm_access.roles du token Keycloak en ROLE_player, ROLE_organizer… */
    private fun keycloakJwtConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
            val realmAccess = jwt.getClaimAsMap("realm_access") ?: emptyMap<String, Any>()
            @Suppress("UNCHECKED_CAST")
            val roles = realmAccess["roles"] as? Collection<String> ?: emptyList()
            roles.map { SimpleGrantedAuthority("ROLE_$it") as GrantedAuthority }
        }
        return converter
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
        }
    }
}
