# État des lieux du backend

> **Date :** 25/07/2026
> **Commit analysé :** `728416e`
> **Périmètre :** dépôt `backend/` uniquement, hors code jOOQ généré (`src/main/kotlin/org/example/backend/database/`)
> **Référence fonctionnelle :** `docs/PA-Tournament-Specs.md`

Ce document décrit ce qui existe réellement dans le backend, comment le code est
découpé, et ce qui reste à faire par rapport à la spécification. Il est destiné à
servir de base aux ADR à écrire et à la planification de la suite.

---

## 1. Chiffres

| | |
|---|---|
| Code écrit à la main | **~2 270 lignes** de Kotlin, 24 fichiers |
| Code jOOQ généré (commité) | 14 tables + 13 enums + records / keys / indexes |
| Migrations Liquibase | 3 changesets, 238 lignes de SQL |
| Endpoints REST | **25** |
| Tests | **1** (`contextLoads`) |

Répartition du code métier : services 941 lignes, repositories 685, DTOs 256,
controllers 285, sécurité 72.

Stack effective : Kotlin 2.2.21, Spring Boot 4.0.2, toolchain JVM 21, jOOQ 3.19.29
(codegen Gradle), Liquibase, OAuth2 resource server. Conforme au `CLAUDE.md`.

---

## 2. Découpage

Une tranche verticale par domaine, un fichier par couche. Six domaines :
`Tournament`, `Bracket`, `Registration`, `Profile`, `Team`, `Dashboard`.

```
controller/XController.kt   → mapping HTTP + déballage du Jwt uniquement
service/XService.kt         → règles métier + mise en forme FR / Europe-Paris + @Transactional
repository/XRepository.kt   → jOOQ DSLContext, renvoie *Record ou data class de projection
model/XDtos.kt              → DTOs sérialisés tels quels, taillés pour les écrans React
config/SecurityConfig.kt    → seule classe de configuration
```

Le découpage est respecté partout **sauf un cas** : `DashboardService` injecte
directement `DSLContext` et écrit ses requêtes jOOQ dans le service — il n'y a pas
de `DashboardRepository`.

Projections locales déclarées en tête des repositories : `RegistrationInfo`,
`ParticipantRow`, `RegistrationRow`, `TeamRow`, `MemberRow`, `GameAccountRow`,
`HistoryRow`, `UserInfoRow`.

`model/BracketDtos.kt` contient l'objet `Display` (palette de 8 couleurs, calcul
des initiales, libellés de round), partagé par `BracketService` et
`TournamentService`.

---

## 3. Endpoints existants

| Méthode | Route | Auth | Domaine |
|---|---|---|---|
| GET | `/api/tournaments` | public | Tournament |
| GET | `/api/tournaments/{id}` | public | Tournament |
| POST | `/api/tournaments` | JWT | Tournament |
| GET | `/api/tournaments/{id}/bracket` | public | Bracket |
| POST | `/api/tournaments/{id}/bracket/generate` | JWT | Bracket |
| POST | `/api/matches/{id}/score` | JWT | Bracket |
| GET | `/api/tournaments/{id}/participants` | public | Registration |
| POST | `/api/tournaments/{id}/register` | JWT | Registration |
| POST | `/api/tournaments/{id}/register-team` | JWT | Registration |
| POST | `/api/tournaments/{id}/participants` | JWT | Registration (ajout manuel) |
| GET | `/api/registrations/pending` | JWT | Registration |
| POST | `/api/registrations/{id}/seed` | JWT | Registration |
| POST | `/api/registrations/{id}/confirm` | JWT | Registration |
| POST | `/api/registrations/{id}/reject` | JWT | Registration |
| GET | `/api/me` | JWT | Profile |
| PATCH | `/api/me` | JWT | Profile |
| POST | `/api/me/game-accounts` | JWT | Profile |
| DELETE | `/api/me/game-accounts/{id}` | JWT | Profile |
| GET | `/api/teams/mine` | JWT | Team |
| GET | `/api/teams/{id}` | JWT | Team |
| POST | `/api/teams` | JWT | Team |
| POST | `/api/teams/{id}/members` | JWT | Team |
| DELETE | `/api/teams/{id}/members/{memberId}` | JWT | Team |
| GET | `/api/dashboard/kpis` | JWT | Dashboard |
| GET | `/api/dashboard/activity` | JWT | Dashboard |

---

## 4. Ce qui fonctionne

- **Cycle tournoi partiel** : création multi-jeu (une `phase` par jeu avec son BO),
  inscription solo ou par équipe, liste d'attente automatique quand
  `max_participants` est atteint, validation / refus par transition d'état contrôlée.
- **Bracket à élimination simple complet** : `seedSlots()` place les seeds selon la
  convention standard (1 contre le plus bas, 2 à l'opposé du tableau), les matchs
  sont créés de la finale vers le round 1 pour chaîner `next_match_id`, les byes
  sont résolus immédiatement, la saisie de score propage le vainqueur via
  `fillSlot` (slot 1 si la position est impaire) et fait basculer le tournoi en
  `ongoing` puis `finished`.
- **Équipes** : roster persistant, capitaine unique, joueurs fantômes créés à la
  volée si le pseudo n'existe pas, contrôle « roster complet » à l'inscription
  (remplaçants exclus du décompte).
- **Identité** : `upsertUserByKeycloak` rattache par email (spec §6.1.3) — un compte
  fantôme est récupéré plutôt que dupliqué.
- **Profil** : pseudo, avatar (data-URL, 500 Ko max), comptes de jeu, historique et
  statistiques (winrate).
- **Sécurité** : API stateless, CSRF désactivé, CORS restreint à
  `app.cors.allowed-origins`, rôles `realm_access.roles` convertis en autorités
  `ROLE_player` / `ROLE_organizer` / `ROLE_admin`.

---

## 5. Comparaison avec la spécification

### Vue d'ensemble

| Section de la spec | État | Couverture estimée |
|---|---|---|
| §6 Schéma de BDD | conforme colonne par colonne | 100 % |
| §4.5 Compte & profil | quasi complet | ~85 % |
| §4.2 Bracket | élimination simple complète, rien d'autre | ~60 % |
| §4.3 Participants / Équipes | solide hors Excel et check-in | ~60 % |
| §4.1 Gestion des tournois | création OK, cycle de vie absent | ~50 % |
| §4.4 Matchs & résultats | saisie de score brute uniquement | ~20 % |
| §5 Transverse | non commencé | ~10 % |

### §6 — Le schéma est le point fort

Comparaison ligne à ligne de `0001-initial-schema.sql` avec les 14 tableaux de
§6.3 : **aucun écart**. Les 13 enums correspondent, les contraintes annoncées en
prose sont bien présentes (`CHECK num_nonnulls(team_id, user_id) = 1`, unicités
`(tournament_id, team_id)` et `(tournament_id, user_id)`), les FK `next_match_id`
et `next_match_loser_id` sont auto-référentes comme décrit, et 5 index utiles ont
été ajoutés en plus. Seul ajout hors spec : `users.avatar_url` (changeset `0003`),
extension assumée.

Les choix structurants de §6.2 sont respectés dans le code : `registrations` est
bien l'unité de participation dans `matches`, `phases.game` porte le jeu,
`TournamentRepository.create` crée une phase par jeu.

**Mais quatre tables du schéma ne sont ni lues ni écrites** : `score_reports`,
`disputes`, `notification_settings` et `jobs`. Le schéma décrit un produit
sensiblement plus complet que le code.

### §4.1 — Cycle de vie : le trou le plus visible

La spec définit `draft → inscriptions ouvertes → check-in → en cours → terminé /
annulé`. Dans le code, **aucun endpoint ne fait de transition d'état**. Un tournoi
est créé en `draft` et y reste jusqu'à ce qu'une saisie de score le fasse basculer
en `ongoing` (`BracketService.reportScore` → `setTournamentStatus`). Les statuts
`registration`, `check_in` et `cancelled` ne sont posés par **aucune ligne de
code** — seul le seed de démo en contient. Il n'existe donc pas de moyen d'ouvrir
des inscriptions, ni d'annuler un tournoi.

Autres écarts :

- **Formats V1** : la spec exige élimination simple, **élimination double** et
  **hybride poules → arbre** en V1 (round robin et suisse étant explicitement
  post-V1). Seule l'élimination simple existe. C'est l'écart fonctionnel le plus
  lourd, d'autant que tout le chaînage `next_match_loser_id` est prévu en base.
- **Dates** : `CreateTournamentRequest` n'accepte que `startAt`.
  `registration_open_at`, `registration_close_at` et `end_at` ne sont jamais écrits.
- **Check-in obligatoire ou non** : `check_in_required` reste à `false` par défaut,
  non exposé.
- **BO par round** : la spec dit « format des matchs par round » ; l'implémentation
  a un BO par phase (`default_bo`), pas par round.
- **Co-organisateurs** : table et enum existent, seul le `owner` est inséré à la
  création, aucun endpoint pour ajouter un `co_organizer`.

### §4.2 — Bracket : conforme sur son périmètre, avec un angle mort

Demandé et fait : génération par le backend, seeding aléatoire **et** manuel
(`POST /api/registrations/{id}/seed`), gestion des byes pour un nombre de
participants qui n'est pas une puissance de 2, re-génération possible tant que le
tournoi n'a pas démarré.

Manquant : le **loser bracket**, donc `next_match_loser_id` reste NULL partout.

Point d'attention : `generate()` ne vérifie le type de la phase **que si** le
paramètre `format` est fourni. Appelé sans corps, il génère un bracket à
élimination simple sur une phase `round_robin` ou `double_elim` sans rien signaler
— cas exact des tournois 2 et 3 du seed de démo.

### §4.3 — Le module le mieux couvert après le profil

Fait : profil avec identifiants in-game (`game_accounts`), équipes persistantes
avec tag et roster, rôles capitaine / membre / remplaçant, remplaçants
correctement exclus du décompte de roster à l'inscription, joueurs fantômes,
inscription solo ou équipe selon `team_size`, liste d'attente automatique.

Manquant ou partiel :

- **Import / export Excel** : rien. Aucun endpoint, aucune écriture dans `jobs`.
  C'est la raison d'être du worker Rust d'après §2 et §7 ; le lien n'existe pas.
- **Check-in** : non implémenté. `checked_in` n'est jamais posé et le remplacement
  des no-shows par la liste d'attente n'existe pas.
- **Logo d'équipe** : colonne `logo_url` présente, absente de `TeamDto` et de tous
  les endpoints.
- **Invitations par lien** : non. Le capitaine ajoute directement un membre par
  pseudo, sans acceptation de l'intéressé. La « recherche » est un match exact
  insensible à la casse.
- **Validation manuelle des inscriptions** : les endpoints `confirm` / `reject`
  existent, mais `statusFor()` ne renvoie jamais `pending` — les inscriptions sont
  directement `confirmed` (ou `waitlist`). Il n'y a pas de drapeau « validation
  requise » sur le tournoi, donc `/api/registrations/pending` ne remonte en
  pratique que des listes d'attente.

### §4.4 — Le module le plus en retard

| Exigence | État |
|---|---|
| Saisie de score par l'organisateur | fonctionnelle, mais **aucun contrôle** que l'appelant est organisateur |
| Déclaration croisée par les deux capitaines | absent (`score_reports` inutilisée) |
| Litige : preuve + arbitrage | absent (`disputes` inutilisée) |
| Forfait / disqualification + propagation | absent (`forfeited` et `disqualified` lus, jamais écrits) |
| Planification horaire + stations | absent (`scheduled_at`, `station` jamais écrits → le champ `time` des DTOs est toujours `null`) |
| BO3 / BO5 avec manches | absent en pratique : `replaceScore` écrit une seule ligne `match_games` (`game_number = 1`) alors que §6.2 prévoit une manche par ligne |

### §4.5 — Presque complet

Authentification Keycloak OIDC en resource server, token validé à chaque appel.
Historique, palmarès et winrate présents. Le SSO fédéré Google / Discord relève de
la configuration Keycloak (dépôt `infra`), rien n'est attendu du backend.

Un bémol : `ProfileRepository.history` filtre sur `registrations.user_id`, donc
**les tournois joués en équipe n'apparaissent ni dans l'historique ni dans les
statistiques** — alors que §6.2 pose justement que l'inscription peut être une
équipe.

### §5 — Transverse : non commencé

- **Temps réel (WebSocket / SSE)** : aucune dépendance, aucun endpoint. Le frontend
  ne peut que re-poller.
- **Notifications configurables** : `notification_settings` est en base et générée
  en jOOQ, jamais touchée.
- **Page publique partageable** : couverte par la règle `GET /api/tournaments/**`
  publique.
- Écran spectateur et export image / PDF relèvent du frontend.

Écart de sécurité lié : la **visibilité `private` n'est jamais filtrée**.
`GET /api/tournaments` renvoie tous les tournois sans authentification, privés et
brouillons compris, alors que §3 limite le Visiteur à la « consultation publique ».

---

## 6. Contradiction spec / brief de notation, à trancher

§6.1.1 et §7 de la spec prévoient que la communication backend ↔ worker passe par
la **table `jobs` pollée par le worker**. Le brief de notation impose au contraire
un worker **isolé** : pas d'accès à la base, deux files (demandes / réponses),
politique de retry portée par le worker — et c'est ce modèle que le worker Rust
implémente déjà (Pub/Sub, aucun accès BDD).

Le backend n'implémente **ni l'un ni l'autre** : ni écriture dans `jobs`, ni
publisher / consumer Pub/Sub. C'est le seul point où la spec et le brief noté se
contredisent frontalement. Décision recommandée : retenir Pub/Sub (aligné avec le
brief et avec le worker existant) et acter l'abandon de §6.1.1 **dans un ADR** —
les ADR font partie des attendus non satisfaits.

---

## 7. Défauts techniques relevés

**Autorisation** — aucun `@PreAuthorize`. N'importe quel compte authentifié peut
appeler `POST /api/tournaments/{id}/participants`,
`/api/registrations/{id}/confirm`, `/reject`, `/seed`,
`/api/tournaments/{id}/bracket/generate` et `/api/matches/{id}/score` : rien ne
vérifie qu'il est organisateur du tournoi. Les seuls contrôles de propriété écrits
sont côté équipe (`isCaptain`) et compte de jeu (`deleteGameAccount` filtre sur
`user_id`).

**Erreurs métier** — les services lèvent des `ResponseStatusException(HttpStatus.…)`
(~30 occurrences), alors que le brief interdit de faire remonter des codes HTTP
depuis la couche domaine. Il n'existe ni `@ControllerAdvice` ni hiérarchie
d'exceptions métier.

**États jamais atteints** — aucun code ne pose `MatchStatus.ongoing` : le KPI
`liveMatches` vaut donc toujours 0 et le statut `live` du bracket est inatteignable.
`scheduled_at` n'étant jamais renseigné, `time` est toujours `null`.

**Duplication de l'upsert utilisateur** — `TournamentRepository.create` refait son
propre `INSERT … ON CONFLICT (keycloak_id)` au lieu d'appeler
`RegistrationRepository.upsertUserByKeycloak` : le rattachement par email y est
absent, les deux chemins peuvent donc diverger.

**Performances** — `TournamentService.list()` appelle `findFirstPhase` par tournoi
(N+1) ; `ProfileRepository.history` fait 3 requêtes de comptage par inscription.

**Champs en dur dans les DTOs** — `region = "—"` et `cashPrize = "—"` dans
`TournamentDetailDto`.

**Tests** — un seul `@SpringBootTest contextLoads`, qui nécessite une base
joignable. Les dépendances de test (`webmvc-test`, `security-test`, `jooq-test`)
sont déclarées mais inutilisées. Aucun test unitaire sur `seedSlots`, la
propagation du vainqueur ou les transitions d'inscription — pourtant les trois
endroits où la logique est réelle.

**Observabilité** — aucune corrélation d'identifiants, attendue par le brief sur la
chaîne backend → worker → backend.

**Reliquats à ne pas confondre avec le schéma réel** — `db.sql`,
`docker-compose.dev.yml`, `README.md`, et `docs/LIQUIBASE.md` (tutoriel générique
dont les chemins ne correspondent pas au disque). Par ailleurs
`0002-seed-demo.sql` est dans le changelog principal : les tournois de démo sont
créés dans **tous** les environnements, production incluse.

---

## 8. Priorités

1. **Cycle de vie des tournois + autorisation organisateur.** Sans transitions
   d'état ni vérification de propriété, §4.1 n'est pas démontrable et n'importe
   quel compte peut administrer le tournoi d'un autre. Les deux se traitent
   ensemble dans `TournamentService`.
2. **Le lien worker.** Trancher Pub/Sub contre `jobs`, écrire l'ADR, puis exposer
   l'import / export Excel de §4.3. C'est ce qui rend le worker existant utile et
   débloque une exigence d'architecture notée.
3. **Élimination double.** La spec la classe en V1 et le schéma est déjà prêt
   (`next_match_loser_id`, `bracket_type` avec `loser` et `grand_final`) : c'est le
   plus gros gain fonctionnel par rapport à l'effort.

Ensuite, par ordre décroissant de rapport valeur / effort : séparation des erreurs
métier des erreurs techniques (`@ControllerAdvice` + exceptions de domaine), tests
unitaires sur le bracket et les inscriptions, filtrage de la visibilité `private`,
check-in, puis §4.4 (litiges, forfaits, planification).
