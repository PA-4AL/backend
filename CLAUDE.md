# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projet

Backend Kotlin/Spring Boot de **PA Tournament** (gestion de tournois esport). Ce dépôt est un des quatre du projet, prévus côte à côte : `backend/`, `frontend/` (React), `worker/` (Rust, import/export Excel), `infra/` (Docker Compose + Keycloak + Makefile).

Spécification fonctionnelle et schéma de BDD : `docs/PA-Tournament-Specs.md` — le code y renvoie souvent par des commentaires « spec §4.2 ». La lire avant toute modification métier.

Le code, les commentaires, les messages d'erreur HTTP et les libellés renvoyés au frontend sont **en français**. Garder cette convention.

## Stack

Kotlin 2.2.21 · Spring Boot 4.0.2 · Gradle 9.3 (wrapper) · toolchain Java **21** (contrainte : Kotlin 2.2 ne cible pas la JVM 25, et l'image de prod est en JRE 21) · PostgreSQL + jOOQ + Liquibase · Keycloak (OIDC, resource server JWT).

## Commandes

```bash
./gradlew bootRun          # lance l'API sur :8080
./gradlew build            # compile + tests
./gradlew test             # tests seuls
./gradlew test --tests "org.example.backend.service.JobServiceTest"       # un seul test
./gradlew ktlintCheck      # linter (bloquant en CI)
./gradlew jooqCodegen      # régénère le package database/ depuis la BDD live
```

`./gradlew ktlintCheck` (linter) et `./gradlew ktlintFormat` (correction
automatique) — joués par la CI, bloquants avant merge. Style `intellij_idea`
(`.editorconfig`) ; le code jOOQ généré est exclu.

Les tests sont des tests **unitaires sans base** : dépôts mockés avec MockK
(`BracketServiceTest`, `JobServiceTest`), contrat de sérialisation avec le worker
(`WorkerContractTest`), et helpers d'affichage. Le smoke test `@SpringBootTest`
d'origine a été retiré : il exigeait une base joignable et échouait donc en CI. Un
test d'intégration Testcontainers reste à écrire.

### Environnement local

L'infra réelle vit dans `../infra` :

```bash
cd ../infra
make infra     # PostgreSQL (:5432) + Keycloak (:8081) en Docker
make backend   # ce backend, avec les SPRING_DATASOURCE_* de l'infra
make dev       # infra + backend + worker + frontend
```

Base : `pa/pa@localhost:5432/pa` — valeurs codées en dur dans `application.yml` **et** dans le bloc `jooq {}` de `build.gradle`, surchargeables par `SPRING_DATASOURCE_*` (Makefile, Cloud Run).

⚠️ `docker-compose.dev.yml`, `db.sql` et le `README.md` sont des reliquats du démarrage du projet (base `devdb`/`devuser`, schéma obsolète `usersDb`/`tournamentDb`/`playerDb`). Ils ne correspondent plus au schéma réel ni aux identifiants utilisés — ne pas s'y fier ; le schéma de référence est `src/main/resources/db/changelog/`.

## Chaîne schéma → code

L'ordre compte, et c'est le point le plus facile à casser :

1. **Liquibase** applique les changesets de `src/main/resources/db/changelog/` au démarrage de Spring (`db.changelog-master.yaml` liste les includes explicitement — pas de `includeAll`).
2. **jOOQ** génère `src/main/kotlin/org/example/backend/database/**` en lisant la BDD **live**. Ce code est **commité** et ne doit jamais être édité à la main.

Donc : modifier le schéma = ajouter un changeset SQL numéroté (`000N-…sql`, format `--liquibase formatted sql`, avec `--preconditions onFail:MARK_RAN` comme les existants), l'ajouter au master, démarrer l'appli pour migrer, **puis** relancer `./gradlew jooqCodegen` et commiter le code généré. En cas d'échec de génération, supprimer le package `database/` et relancer.

Les enums PostgreSQL sont générés en enums Kotlin (`database/enums/`) dont la valeur SQL est exposée par `.literal` — c'est ce `literal` qui transite dans les DTOs, jamais `name`. Détail Liquibase complet dans `docs/LIQUIBASE.md`.

## Architecture

Découpage classique en trois couches, un fichier par domaine (tournaments, bracket, registrations, profile, teams) :

- `controller/v1/` — mapping HTTP uniquement, aucune logique. Reçoit `@AuthenticationPrincipal jwt: Jwt` et transmet `jwt.subject` / `preferred_username` / `email` au service. **Un paquet par version d'API** (`controller/v1/`, plus tard `controller/v2/`), classes suffixées `V1` : le préfixe `/api/v1` est appliqué globalement par `config/WebMvcConfig.kt`, les `@RequestMapping` déclarent des chemins nus (`/tournaments`) et la version via `version = "1+"`. Voir `docs/API-VERSIONING.md` avant d'ajouter ou de modifier une route.
- `internal/` — endpoints **hors API publique** (aujourd'hui le callback Pub/Sub).
  Deux contraintes, apprises en production : rester hors du paquet `controller`
  (sinon le préfixe `/api/{version}` s'applique) **et** placer une version en 2e
  segment (`/internal/v1/…`), car `usePathSegment(1)` valide la version sur toutes
  les routes du DispatcherServlet. Sans jeton, Spring Security masque le problème
  en répondant 401 avant la résolution de version : tester ces routes
  authentifié (`CallbackAuthenticatedTests`).
- `service/` — logique métier + **mise en forme pour le frontend**. Les writes sont `@Transactional`. Les erreurs sont levées en `ResponseStatusException(HttpStatus.X, "message français")` (pas de `@ControllerAdvice`).
- `repository/` — jOOQ `DSLContext` injecté, requêtes typées via `database/tables/references/*`. Renvoie soit des `*Record` jOOQ, soit de petites `data class` de projection déclarées en tête du fichier (`ParticipantRow`, `RegistrationInfo`…).
- `model/` — DTOs `data class` sérialisés tels quels en JSON, + l'objet `Display` (`BracketDtos.kt`) qui centralise couleurs, initiales et libellés de round.

Les DTOs sont **taillés pour les écrans React**, pas pour refléter les tables : ils contiennent des chaînes déjà formatées (`scheduleLabel` « démarré 14:30 », `code` « #A1B2C3 », statuts `done`/`live`/`scheduled`, couleurs hex). Toute la mise en forme — fuseau `Europe/Paris`, locale française — se fait côté backend. Ajouter un champ implique donc de savoir comment le frontend l'affiche.

### Documentation de l'API — `docs/ENDPOINTS.md`

`docs/ENDPOINTS.md` documente les 25 endpoints : route, auth, corps de requête, DTO
de réponse, codes et messages d'erreur, plus les DTOs et les endpoints attendus par
la spec mais absents. **C'est le contrat lu par le frontend : il doit être mis à
jour dans le même commit que le code.** Toute modification d'un `@RestController`
(endpoint ajouté ou supprimé, route/méthode renommée, DTO de requête ou de réponse
modifié, code ou message d'erreur changé) s'y répercute, ainsi que la ligne
« Dernière mise à jour » de l'en-tête. Le lire avant de toucher à `controller/` ou
`model/`.

`docs/API-VERSIONING.md` est la **procédure de gestion des breaking changes** exigée
par le brief de notation : mécanisme retenu, sémantique de `version = "N+"`, et la
marche à suivre pour livrer une v2 sans toucher au code v1. À lire avant tout
changement incompatible de contrat.

`docs/ETAT-DES-LIEUX.md` est le rapport d'écart entre le code et la spec (couverture
par module, dettes techniques, priorités) — instantané daté, pas un document vivant.

### Sécurité

`config/SecurityConfig.kt` : API stateless, CSRF désactivé, CORS restreint à `app.cors.allowed-origins`. `GET /api/v1/tournaments/**` est public (rôle Visiteur de la spec) — le matcher est déclaré version par version, une nouvelle version d'API n'hérite pas de l'accès anonyme ; tout le reste exige un JWT du realm Keycloak. Les rôles `realm_access.roles` sont convertis en autorités `ROLE_player` / `ROLE_organizer` / `ROLE_admin` — l'autorisation fine (`@PreAuthorize`) n'est **pas encore implémentée**, les contrôles de propriétaire/organisateur restent à écrire.

### Identité utilisateur

Il n'y a pas de synchronisation Keycloak → BDD. Chaque endpoint authentifié appelle `RegistrationRepository.upsertUserByKeycloak(keycloakId, pseudo, email)` qui fait un `INSERT … ON CONFLICT (keycloak_id) DO UPDATE` et renvoie le `users.id` local. C'est aussi le mécanisme de rattachement des « joueurs fantômes » importés par le worker (spec §6.1.3).

### Modèle métier — points structurants

- **`registrations` est l'unité de participation** : un match oppose deux `registration_id`, jamais un user ou une team directement. Le nom affiché est résolu par `COALESCE(teams.name, users.pseudo)`.
- **`phases`** porte le jeu et le format, pas le tournoi : un tournoi multi-jeu a une phase par jeu (`TournamentRepository.create` en crée une par entrée de `req.games`). La plupart des services ne lisent aujourd'hui que `findFirstPhase()`.
- **Bracket** (`BracketService`) : élimination simple **uniquement** en V1 — les autres `PhaseType` sont rejetés en 400. La génération crée les matchs de la finale vers le round 1 pour chaîner `next_match_id`, place les seeds par `seedSlots()` (1 vs n, 2 à l'opposé), et résout les byes immédiatement. `next_match_loser_id` (double élimination) existe en base mais n'est pas exploité.
- Saisie de score : `reportScore` propage le vainqueur via `fillSlot` (slot 1 si `position` impair) et fait basculer le tournoi en `ongoing`, ou `finished` quand le match n'a pas de `next_match_id`.
