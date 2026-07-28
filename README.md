# PA Tournament — Backend (Kotlin / Spring Boot)

[![CI](https://github.com/PA-4AL/backend/actions/workflows/ci.yml/badge.svg)](https://github.com/PA-4AL/backend/actions/workflows/ci.yml)

API REST de la plateforme de gestion de tournois : tournois, inscriptions,
brackets, matchs, profils. Sécurisée par les JWT du realm Keycloak.

**Déploiement et contribution** — flow git, pipelines et mise en production sont
documentés dans le repo `infra` :
[GIT-FLOW](https://github.com/PA-4AL/infra/blob/main/docs/GIT-FLOW.md) ·
[CI-CD](https://github.com/PA-4AL/infra/blob/main/docs/CI-CD.md) ·
[DEPLOY](https://github.com/PA-4AL/infra/blob/main/docs/DEPLOY.md) ·
[DOCKER](https://github.com/PA-4AL/infra/blob/main/docs/DOCKER.md)

## Qualité (jouée par la CI à chaque commit et chaque PR)

```bash
./gradlew ktlintCheck    # linter Kotlin
./gradlew test           # tests unitaires
./gradlew ktlintFormat   # correction automatique du style
```

## Image de production

```bash
docker build -t pa-backend .          # multi-stage, jar en couches, JRE Alpine non-root
```


## Installation
- Lancer la database avec docker-compose
```bash
  docker compose -f docker-compose.dev.yml up -d
```

- Run la task "jooqCodegen" pour générer les classes JOOQ à partir de la base de données
```bash
  ./gradlew jooqCodegen
```
En cas d'erreur, n'hésitez pas à supprimer les fichiers autogénérés dans le package "database" et relancer le script



