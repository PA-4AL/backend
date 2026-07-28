package org.example.backend.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Versionnement de l'API — procédure complète dans `docs/API-VERSIONING.md`.
 *
 * Deux réglages complémentaires :
 * - le préfixe `/api/{version}` est ajouté à tous les controllers du paquet
 *   `controller` (donc `controller.v1` et les futurs `controller.v2`), ce qui évite
 *   de répéter la version dans chaque `@RequestMapping` ;
 * - la version est extraite du 2e segment de chemin (`/api/v1/…` → `v1`, parsé en
 *   `1.0.0` : le parseur sémantique de Spring ignore les caractères non numériques
 *   de tête) et confrontée à l'attribut `version` des `@RequestMapping`.
 *
 * Les controllers déclarent `version = "1+"` (*baseline*) : ils continuent de
 * répondre aux versions supérieures tant qu'aucun controller d'une version plus
 * haute ne prend la main sur la même route.
 */
@Configuration
class WebMvcConfig : WebMvcConfigurer {

    override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
        configurer
            .usePathSegment(1)
            .addSupportedVersions("1")
    }

    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.addPathPrefix(
            "/api/{version}",
            HandlerTypePredicate.forBasePackage("org.example.backend.controller"),
        )
    }
}
