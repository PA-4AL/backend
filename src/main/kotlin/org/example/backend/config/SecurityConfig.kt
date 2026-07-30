package org.example.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * API stateless sécurisée par les JWT du realm Keycloak (spec §4.5) :
 * - lecture publique des tournois (rôle Visiteur)
 * - écriture authentifiée ; les rôles realm (player/organizer/admin)
 *   sont exposés en autorités ROLE_* utilisées par les @PreAuthorize.
 *
 * Deux chaînes cohabitent : `/internal` accepte les jetons OIDC **de Google**
 * (livraisons push Pub/Sub), tout le reste les jetons **de Keycloak**. Les deux
 * émetteurs sont distincts, ils ne doivent donc pas partager de décodeur.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // active les @PreAuthorize des contrôleurs
class SecurityConfig(@param:Value("\${app.cors.allowed-origins}") private val allowedOrigins: List<String>) {

    @Bean
    @Order(1)
    fun internalFilterChain(
        http: HttpSecurity,
        @Value("\${app.pubsub.push-audience:}") pushAudience: String,
    ): SecurityFilterChain {
        http
            .securityMatcher("/internal/**")
            .csrf { it.disable() }
            .cors { it.disable() } // appels serveur à serveur uniquement
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.decoder(googleJwtDecoder(pushAudience)) }
            }
        return http.build()
    }

    /**
     * Décodeur des jetons signés par Google pour les livraisons push : émetteur
     * accounts.google.com et audience égale à l'URL du callback (configurée dans
     * l'abonnement). L'identité exacte de l'appelant est vérifiée par le
     * contrôleur.
     */
    private fun googleJwtDecoder(audience: String): JwtDecoder {
        val decoder = NimbusJwtDecoder
            .withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .build()

        val validators = mutableListOf<OAuth2TokenValidator<Jwt>>(
            JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),
        )
        if (audience.isNotBlank()) {
            validators += JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud ->
                aud != null && aud.contains(audience)
            }
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    @Bean
    @Order(2)
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors(Customizer.withDefaults())
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    // Sondes de disponibilité : lues par Cloud Run (startup /
                    // liveness probes) et par le smoke test de la pipeline de
                    // déploiement — donc avant toute authentification.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // Flux d'annonces en direct. Non authentifié à dessein : un
                    // navigateur ne peut pas poser d'en-tête sur une WebSocket, et
                    // passer le jeton en paramètre d'URL le ferait apparaître dans
                    // les journaux d'accès. Le canal ne transporte que ce qui est
                    // déjà public — des résultats de matchs lisibles sur le bracket.
                    // Le ciblage par destinataire se fait sur l'API authentifiée
                    // (`/api/v1/announcements`), pas ici.
                    .requestMatchers("/ws/**").permitAll()
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
