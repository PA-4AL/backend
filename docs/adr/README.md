# Décisions d'architecture — backend

Format, règles et modèle : [`../../../infra/docs/adr/README.md`](https://github.com/PA-4AL/infra/blob/main/docs/adr/README.md).
Copier `template.md`, numéroter à la suite, rester bref.

Un ADR est attendu pour : un changement de modèle (schéma, contrat d'API,
contrat de messages), l'ajout d'une dépendance externe, un choix d'architecture.

| N° | Décision | Date | Statut |
|---|---|---|---|
| [0001](0001-jooq-et-liquibase.md) | jOOQ pour l'accès aux données, Liquibase pour le schéma | 2026-06-23 | accepté |
| [0002](0002-versionnement-api-par-segment.md) | Versionner l'API par segment de chemin | 2026-07-25 | accepté |
| [0003](0003-contrat-de-messages-snake-case.md) | Contrat de messages avec le worker en snake_case | 2026-07-28 | accepté |
| [0004](0004-callback-interne-hors-versionnement.md) | Endpoint interne hors du versionnement d'API | 2026-07-29 | accepté |
| [0005](0005-publication-pubsub-par-api-rest.md) | Publier sur Pub/Sub par son API REST | 2026-07-28 | accepté |
| [0006](0006-tests-unitaires-sans-base.md) | Tests unitaires sans base de données | 2026-07-28 | accepté |
| [0007](0007-erreurs-metier-et-http.md) | Séparer erreurs métier et codes HTTP | 2026-07-29 | **proposé — dette assumée** |
