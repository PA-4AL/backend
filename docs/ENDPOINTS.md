# API PA Tournament — référence des endpoints

> **⚠ Fichier à maintenir.** Toute modification d'un `@RestController` du backend
> (endpoint ajouté, supprimé, route ou méthode renommée, version changée, DTO de
> requête ou de réponse modifié, code d'erreur changé) doit être répercutée ici **dans
> le même commit**. C'est le contrat lu par le frontend ; un écart ici est un bug côté
> client.
>
> **Source de vérité :** `src/main/kotlin/org/example/backend/controller/v1/`
> et `.../model/*Dtos.kt`.
> **Dernière mise à jour :** 29/07/2026 (27 endpoints en v1, + 1 endpoint interne, format d'erreur, formats de bracket)

Base URL : `http://localhost:8080` en local (`VITE_API_URL` côté frontend).
Tous les corps de requête et de réponse sont en `application/json`.

## Versionnement

Toutes les routes sont préfixées par **`/api/v1/`**. La version est un segment de
chemin, résolue par Spring et confrontée à l'attribut `version` des `@RequestMapping` ;
le préfixe est appliqué globalement par `config/WebMvcConfig.kt` et **n'apparaît donc
pas dans les controllers**, qui déclarent des chemins nus (`/tournaments`, `/me`…).

- Une seule version existe aujourd'hui : **v1**.
- Les routes **non versionnées** (`/api/tournaments`) et les versions inexistantes
  (`/api/v9/…`) renvoient **401** — Spring Security filtre avant le routage.
- Côté frontend, la version est choisie **appel par appel** via le helper `v1()` de
  `src/api/client.ts`, ce qui permettra de consommer v1 et v2 simultanément.

**Procédure pour introduire une version : `API-VERSIONING.md`.** C'est le document à
lire avant tout changement incompatible.

---

## Authentification

JWT du realm Keycloak `pa-tournament`, en-tête `Authorization: Bearer <token>`.
Configuration dans `src/main/kotlin/org/example/backend/config/SecurityConfig.kt`.

| Règle | Portée |
|---|---|
| `permitAll` | `GET /api/v1/tournaments/**` uniquement (rôle Visiteur, spec §3) |
| `authenticated` | tout le reste |

Le matcher public est déclaré **version par version** : une nouvelle version d'API
n'hérite pas de l'accès anonyme, elle doit ajouter sa propre ligne.

Les rôles `realm_access.roles` du token sont exposés en autorités
`ROLE_player` / `ROLE_organizer` / `ROLE_admin`, mais **aucun `@PreAuthorize`
n'est posé** : tout compte authentifié peut aujourd'hui appeler les endpoints
d'organisation (validation d'inscriptions, génération de bracket, saisie de score).
À corriger — voir `ETAT-DES-LIEUX.md` §7.

CORS restreint à `app.cors.allowed-origins`
(défaut `http://localhost:5173,http://localhost:3000`), méthodes
`GET POST PUT PATCH DELETE OPTIONS`, en-têtes `Authorization` et `Content-Type`.

---

## Table récapitulative

| # | Méthode | Route | Auth | Succès |
|---|---|---|---|---|
| 1 | GET | `/api/v1/tournaments` | public | 200 |
| 2 | GET | `/api/v1/tournaments/{id}` | public | 200 / 404 |
| 3 | POST | `/api/v1/tournaments` | JWT | 201 |
| 4 | GET | `/api/v1/tournaments/{id}/bracket` | public | 200 |
| 5 | POST | `/api/v1/tournaments/{id}/bracket/generate` | JWT | 200 |
| 6 | POST | `/api/v1/matches/{matchId}/score` | JWT | 200 |
| 7 | GET | `/api/v1/tournaments/{id}/participants` | public | 200 |
| 8 | POST | `/api/v1/tournaments/{id}/register` | JWT | 201 |
| 9 | POST | `/api/v1/tournaments/{id}/register-team` | JWT | 201 |
| 10 | POST | `/api/v1/tournaments/{id}/participants` | JWT | 201 |
| 11 | GET | `/api/v1/registrations/pending` | JWT | 200 |
| 12 | POST | `/api/v1/registrations/{id}/seed` | JWT | 204 |
| 13 | POST | `/api/v1/registrations/{id}/confirm` | JWT | 204 |
| 14 | POST | `/api/v1/registrations/{id}/reject` | JWT | 204 |
| 15 | GET | `/api/v1/me` | JWT | 200 |
| 16 | PATCH | `/api/v1/me` | JWT | 200 |
| 17 | POST | `/api/v1/me/game-accounts` | JWT | 201 |
| 18 | DELETE | `/api/v1/me/game-accounts/{id}` | JWT | 204 |
| 19 | GET | `/api/v1/teams/mine` | JWT | 200 |
| 20 | GET | `/api/v1/teams/{id}` | JWT | 200 |
| 21 | POST | `/api/v1/teams` | JWT | 201 |
| 22 | POST | `/api/v1/teams/{id}/members` | JWT | 200 |
| 23 | DELETE | `/api/v1/teams/{id}/members/{memberId}` | JWT | 200 |
| 24 | GET | `/api/v1/dashboard/kpis` | JWT | 200 |
| 25 | GET | `/api/v1/dashboard/activity` | JWT | 200 |
| 26 | POST | `/api/v1/teams/import` | JWT (organizer/admin) | 200 / 400 / 413 / 503 |
| 27 | GET | `/api/v1/jobs/{id}` | JWT | 200 / 404 |
| — | POST | `/internal/v1/jobs/callback` | jeton OIDC Google (Pub/Sub) | 204 / 400 / 403 |

---

## Tournois

`TournamentController` → `TournamentService`

### 1. `GET /api/v1/tournaments` — public

Liste tous les tournois, triés par `start_at` décroissant (NULL en dernier).

**200** → `TournamentSummaryDto[]`

> Aucun filtre n'est appliqué : les tournois `private` et les brouillons (`draft`)
> sont renvoyés à un appelant non authentifié. Écart connu vs spec §3/§4.1.

### 2. `GET /api/v1/tournaments/{id}` — public

**200** → `TournamentDetailDto`
**404** → corps vide (pas de message)

### 3. `POST /api/v1/tournaments` — JWT

```json
{
  "name": "APEX Invitational 2026",
  "description": "Tournoi invitationnel",
  "games": [{ "name": "Apex Legends", "bestOf": 3 }],
  "format": "single_elim",
  "teamSize": 1,
  "maxParticipants": 8,
  "visibility": "public",
  "startAt": "2026-06-15T18:00"
}
```

- `games` — obligatoire, au moins une entrée avec un `name` non vide. `bestOf` est
  ramené dans `[1, 5]`. Une **phase par jeu** est créée (spec §6.2).
- `format` — valeur inconnue : retombe silencieusement sur `single_elim`.
- `visibility` — valeur inconnue : retombe silencieusement sur `public`.
- `startAt` — ISO local **sans fuseau**, interprété en heure de Paris.
- Le tournoi est créé en statut `draft`, l'appelant est inséré comme `owner`.

**201** → `TournamentSummaryDto`, en-tête `Location: /api/v1/tournaments/{id}`
**400** — `"Au moins un jeu est requis"` · `"Date de début invalide"`

---

## Bracket & matchs

`BracketController` → `BracketService`

### 4. `GET /api/v1/tournaments/{id}/bracket` — public

**200** → `BracketDto`. Si le bracket n'a pas encore été généré :
`{ "rounds": [], "champion": null }`.
**404** — `"Tournoi sans phase"`

### 5. `POST /api/v1/tournaments/{id}/bracket/generate` — JWT

Corps **optionnel** : `{ "format": "single_elim" }`

Génère (ou régénère) l'arbre. Seeds manuels d'abord, puis les non-seedés mélangés ;
byes résolus immédiatement. Les matchs sont insérés puis câblés en une seconde passe
(`next_match_id`, `next_match_loser_id`).

**200** → `BracketDto`
**404** — `"Tournoi introuvable"`
**409** — `"Le tournoi a déjà démarré"` (statut hors `draft` / `registration` / `check_in`) · `"Le tournoi n'a aucune phase"` · `"Au moins 2 participants confirmés sont requis"`
**400** — `"Format inconnu : X"` · `"Le format suisse se génère tour par tour et n'est pas encore disponible"`

Formats disponibles : `single_elim`, `double_elim` (minimum 4 participants),
`round_robin`. Le `swiss` est refusé : ses appariements dépendent du classement
après chaque tour, il ne peut pas être pré-généré (`docs/adr/0008-formats-de-bracket.md`).

Les libellés de tour renvoyés dépendent du format : « Quarts de finale » en arbre,
« Journée 2 » en round robin, « Perdants — tour 1 » et « Grande finale » en
élimination double.

> Sans corps, le format **déjà porté par la phase** est utilisé. Fournir `format`
> change le type de la phase, de façon persistante.

### 6. `POST /api/v1/matches/{matchId}/score` — JWT

```json
{ "scoreA": 2, "scoreB": 1 }
```

Écrit le score, désigne le vainqueur, le propage dans `next_match_id`, et fait
basculer le tournoi en `ongoing` — ou `finished` si le match n'a pas de match
suivant (finale).

**200** → `BracketDto` complet du tournoi
**400** — `"Pas de match nul en élimination directe"`
**404** — `"Match introuvable"`
**409** — `"Match déjà terminé"` · `"Les deux participants ne sont pas encore connus"` · `"Phase orpheline"`

> Le score est stocké comme **une seule manche** (`match_games.game_number = 1`),
> quel que soit le `best_of` de la phase.

---

## Inscriptions & participants

`RegistrationController` → `RegistrationService`

### 7. `GET /api/v1/tournaments/{id}/participants` — public

Triés par `seed` croissant (NULL en dernier) puis date d'inscription.

**200** → `ParticipantDto[]`

### 8. `POST /api/v1/tournaments/{id}/register` — JWT

Inscription solo de l'appelant, sans corps de requête. L'utilisateur plateforme est
créé ou rattaché depuis le JWT (`upsertUserByKeycloak`).

**201** → `ParticipantDto`
**404** — `"Tournoi introuvable"`
**409** — `"Les inscriptions sont fermées"` · `"Ce tournoi se joue en équipe (5v5) — inscris ton équipe"` · `"Tu es déjà inscrit à ce tournoi"`

### 9. `POST /api/v1/tournaments/{id}/register-team` — JWT

```json
{ "teamId": "uuid" }
```

**201** → `ParticipantDto` (`name` = nom de l'équipe)
**403** — `"Seul le capitaine peut inscrire l'équipe"`
**404** — `"Tournoi introuvable"` · `"Équipe introuvable"`
**409** — `"Les inscriptions sont fermées"` · `"Ce tournoi se joue en solo"` · `"<Équipe> est déjà inscrite"` · `"Roster incomplet : N joueur(s) pour un format 5v5"` (les remplaçants ne comptent pas)

### 10. `POST /api/v1/tournaments/{id}/participants` — JWT

Ajout manuel par l'organisateur. Crée un **joueur fantôme** si le tournoi est solo,
une **équipe fantôme** sinon (spec §6.1.3).

```json
{ "name": "Nebula" }
```

**201** → `ParticipantDto`
**400** — `"Le nom est obligatoire"`
**404** — `"Tournoi introuvable"` · **409** — `"Les inscriptions sont fermées"`

### 11. `GET /api/v1/registrations/pending` — JWT

Inscriptions en statut `pending` ou `waitlist`, **tous tournois confondus**, triées
par date croissante.

**200** → `PendingRegistrationDto[]`

> En pratique seules les `waitlist` remontent : le statut `pending` n'est jamais
> posé à l'inscription (voir `ETAT-DES-LIEUX.md` §5).

### 12. `POST /api/v1/registrations/{id}/seed` — JWT

```json
{ "seed": 3 }
```

`seed: null` retire le seed manuel.

**204** — pas de corps
**400** — `"Le seed doit être ≥ 1"` · **404** — `"Inscription introuvable"`

### 13. `POST /api/v1/registrations/{id}/confirm` — JWT

Transition `pending` | `waitlist` → `confirmed`.

**204** · **404** — `"Inscription introuvable"` · **409** — `"Transition impossible depuis « X »"`

### 14. `POST /api/v1/registrations/{id}/reject` — JWT

Transition `pending` | `waitlist` | `confirmed` → `withdrawn`. Mêmes réponses que
`confirm`.

---

## Profil

`ProfileController` → `ProfileService`. Toutes les routes portent sur l'appelant,
identifié par `jwt.subject` — aucun identifiant utilisateur ne circule dans l'URL.

### 15. `GET /api/v1/me` — JWT

**200** → `ProfileDto` (profil + comptes de jeu + historique + stats)

> L'historique ne couvre que les inscriptions **solo** : les tournois joués en
> équipe n'y apparaissent pas.

### 16. `PATCH /api/v1/me` — JWT

```json
{ "pseudo": "Nebula", "avatarUrl": "data:image/png;base64,..." }
```

Les deux champs sont optionnels. `avatarUrl: ""` retire la photo.

**200** → `ProfileDto` rafraîchi
**400** — `"Le pseudo ne peut pas être vide"` · `"Image trop lourde (500 Ko max)"`

### 17. `POST /api/v1/me/game-accounts` — JWT

```json
{ "game": "valorant", "identifier": "Nebula#EUW" }
```

**201** → `GameAccountDto` · **400** — `"Jeu et identifiant sont obligatoires"`

### 18. `DELETE /api/v1/me/game-accounts/{id}` — JWT

Ne supprime que si le compte appartient à l'appelant.

**204** · **404** — `"Compte de jeu introuvable"`

---

## Équipes

`TeamController` → `TeamService`

### 19. `GET /api/v1/teams/mine` — JWT

Équipes dont l'appelant est membre (tout rôle), les plus récentes d'abord.

**200** → `TeamDto[]`

### 20. `GET /api/v1/teams/{id}` — JWT

**200** → `TeamDto` · **404** — `"Équipe introuvable"`

### 21. `POST /api/v1/teams` — JWT

```json
{ "name": "Team Nebula", "tag": "NBL" }
```

`tag` est tronqué à 8 caractères. Le créateur devient `captain`.

**201** → `TeamDto` · **400** — `"Le nom de l'équipe est obligatoire"`

### 22. `POST /api/v1/teams/{id}/members` — JWT (capitaine)

```json
{ "pseudo": "Vortex", "role": "member" }
```

`role` ∈ `member` | `substitute` (défaut `member`). Si le pseudo n'existe pas, un
**joueur fantôme** est créé. La recherche de pseudo est exacte, insensible à la
casse. Aucune invitation ni acceptation : l'ajout est immédiat.

**200** → `TeamDto` à jour
**400** — `"Le pseudo est obligatoire"` · `"Une équipe n'a qu'un capitaine"` (si `role: "captain"`)
**403** — `"Seul le capitaine peut gérer le roster"`
**404** — `"Équipe introuvable"` · **409** — `"<pseudo> est déjà dans l'équipe"`

### 23. `DELETE /api/v1/teams/{id}/members/{memberId}` — JWT (capitaine)

`memberId` est un **`users.id`**, pas un identifiant de ligne `team_members`.

**200** → `TeamDto` à jour
**400** — `"Le capitaine ne peut pas se retirer lui-même"`
**403** — `"Seul le capitaine peut gérer le roster"`
**404** — `"Équipe introuvable"` · `"Membre introuvable"`

---

## Tableau de bord

`DashboardController` → `DashboardService`

### 24. `GET /api/v1/dashboard/kpis` — JWT

Compteurs globaux, **non filtrés par utilisateur**.

**200** → `DashboardKpisDto`

> `liveMatches` vaut toujours 0 : aucun code ne pose `matches.status = 'ongoing'`.

### 25. `GET /api/v1/dashboard/activity` — JWT

Fil des 6 derniers événements (5 dernières inscriptions + 5 derniers tournois créés,
fusionnés et retriés).

**200** → `ActivityItemDto[]`

---

## DTOs

Définis dans `src/main/kotlin/org/example/backend/model/`, miroir côté
frontend dans `../../frontend/src/api/types.ts`. Les DTOs sont **taillés pour les écrans
React**, pas pour refléter les tables : ils contiennent des chaînes déjà formatées
(fuseau `Europe/Paris`, locale française) et des couleurs hex.

## Traitements asynchrones (import / export Excel)

`JobV1Controller` → `JobService` → Pub/Sub → worker Rust.
Architecture et contrat de messages : `../../infra/docs/ARCHITECTURE.md`.

### 26. `POST /api/v1/teams/import` — JWT (organizer/admin)

Soumet un fichier Excel de rosters. La réponse est **immédiate** : le traitement
est asynchrone, le client suit son avancement via l'endpoint 27.

```json
{ "tournamentType": "esport_5v5", "fileBase64": "UEsDBBQ…" }
```

`tournamentType` ∈ `esport_5v5` | `football_11v11` (schémas de colonnes reconnus
par le worker). `fileBase64` est le `.xlsx` encodé en base64.

**200** → `JobDto` (`status: "processing"`)
**400** type de tournoi inconnu, ou fichier absent
**413** fichier au-delà de la limite Pub/Sub (10 Mo par message, base64 comprise)
**503** messagerie non configurée (`app.pubsub.enabled=false`, cas du poste de dev)

### 27. `GET /api/v1/jobs/{id}` — JWT

État d'un traitement. À interroger en *polling* après l'endpoint 26.

**200** → `JobDto`
**404** traitement introuvable

### `POST /internal/v1/jobs/callback` — interne, hors versionnement

Cible de l'abonnement **push** Pub/Sub qui rapporte les réponses du worker. Cet
endpoint est délibérément **hors du paquet `controller`** : le préfixe
`/api/{version}` ne doit pas s'y appliquer, l'appelant étant Pub/Sub et non un
client de l'API.

Le chemin porte tout de même un segment `v1` en 2e position : la résolution de
version (`usePathSegment(1)`) s'applique à **toutes** les routes du
DispatcherServlet, et un 2e segment illisible comme version fait répondre 400
avant même d'atteindre le controller.

Authentification par jeton OIDC signé par Google (chaîne de sécurité dédiée dans
`SecurityConfig`), audience égale à l'URL du callback, et vérification du compte
de service appelant.

**204** réponse prise en compte (ou ignorée : job inconnu, ou déjà terminé —
Pub/Sub garantit *au moins* une livraison, le traitement est idempotent)
**400** message illisible ou `task_id` invalide → Pub/Sub abandonne le message
**403** appelant inattendu

### DTO — `JobDtos.kt`

```kotlin
data class JobDto(
    val id: UUID,
    val type: String,          // team_import | team_export
    val status: String,        // pending | processing | done | failed
    val error: String?,        // message du worker en cas d'échec
    val createdAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val result: Map<String, Any?>?,  // team_count, player_count, teams… ou file_base64
)
```

---

### Tournois — `TournamentDtos.kt`

```ts
TournamentSummaryDto {
  id, name: string
  code: string            // "#A1B2C3" — dérivé de l'UUID
  format: "single_elim" | "double_elim" | "round_robin" | "swiss"
  participants, maxParticipants: number
  status: "draft" | "registration" | "check_in" | "ongoing" | "finished" | "cancelled"
  scheduleLabel: string   // "démarré 14:30" | "15 juin 18:00" | "à planifier"
}

TournamentDetailDto {
  ...TournamentSummaryDto
  description: string
  game: string            // "Apex Legends (BO3) · Valorant (BO5)" — toutes les phases
  teamSize: number
  organizer: string       // pseudo du owner
  bestOf: number          // BO de la première phase
  checkInWindow: string   // "17:30 — 18:00" | "—"
  region: string          // toujours "—" (non modélisé)
  visibility: "public" | "private"
  cashPrize: string       // toujours "—" (non modélisé)
  currentPhaseLabel: string  // "Quarts de finale" | "Élimination simple"
  startedLabel: string    // "18:00" | "—"
  matchesPlayed, matchesTotal: number
  remainingTeams: TeamRefDto[]
  currentMatches: MatchRowDto[]
}

TeamRefDto  { code: string; name: string; color: string }   // code = initiales, color = hex
MatchRowDto { id, teamA, teamB, scoreA?, scoreB?, status: "live"|"done"|"scheduled", time? }
```

### Bracket — `BracketDtos.kt`

```ts
BracketDto      { rounds: BracketRoundDto[]; champion?: string }
BracketRoundDto { label: string;            // "Finale" | "Demi-finales" | "Quarts de finale" | "1/8 de finale"
                  matches: BracketMatchDto[] }
BracketMatchDto { id: string;               // code d'affichage "QF1", "SF2", "F1"
                  matchId: string;          // UUID réel, à utiliser pour la saisie de score
                  status: "done" | "live" | "scheduled" | "pending"
                  time?: string             // "14:30" — toujours null aujourd'hui (scheduled_at jamais écrit)
                  a: SlotDto; b: SlotDto }
SlotDto         { tbd?: boolean; name: string; seed?: number; code?: string;
                  color?: string; score?: number; win?: boolean }
```

Un slot vide renvoie `{ tbd: true, name: "Bye" }` au round 1,
`{ tbd: true, name: "À déterminer" }` ensuite.

### Inscriptions — `RegistrationDtos.kt`

```ts
ParticipantDto        { registrationId, name, status, seed?, registeredLabel }
PendingRegistrationDto{ registrationId, participant, tournamentId, tournamentName,
                        status, registeredLabel }
```

`status` ∈ `pending` | `confirmed` | `waitlist` | `checked_in` | `withdrawn` |
`disqualified`. `registeredLabel` est relatif : `"À l'instant"`, `"Il y a 12 min"`,
`"Il y a 3 h"`, `"Il y a 2 j"`.

### Profil — `ProfileDtos.kt`

```ts
ProfileDto       { pseudo, email?, avatarUrl?, gameAccounts: GameAccountDto[],
                   history: TournamentHistoryDto[], stats: ProfileStatsDto }
GameAccountDto   { id, game, identifier }
TournamentHistoryDto { tournamentId, name, game, status,
                       result: "champion" | "in_progress" | "eliminated" | "registered",
                       matchesWon, matchesPlayed }
ProfileStatsDto  { tournamentsPlayed, matchesPlayed, matchesWon, winrate }  // winrate en %
```

### Équipes — `TeamDtos.kt`

```ts
TeamDto       { id, name, tag?, members: TeamMemberDto[] }
TeamMemberDto { userId, pseudo, role: "captain" | "member" | "substitute" }
```

`logo_url` existe en base mais n'est exposé par aucun endpoint.

### Tableau de bord — `DashboardDtos.kt`

```ts
DashboardKpisDto { activeTournaments, activeTournamentsDelta: string,
                   liveMatches, participants, participantsDelta: string,
                   pendingValidations }
ActivityItemDto  { id, kind: "win"|"live"|"registration"|"dispute"|"finished",
                   html: string,        // contient du <b>…</b>, injecté tel quel côté React
                   time: string }       // relatif, "Il y a 12 min"
```

---

## Format des erreurs

Les services lèvent des `ResponseStatusException(HttpStatus.X, "message français")`.
Il n'y a **ni `@ControllerAdvice` ni `ProblemDetail` configuré** : le corps est celui
que Spring Boot produit par défaut.

⚠ **À vérifier avant de s'appuyer dessus côté frontend** : `server.error.include-message`
n'est pas configuré dans `application.yml`, et sa valeur par défaut est `never`.
Les messages français listés ci-dessus risquent donc de ne **pas** arriver au client,
alors que `../../frontend/src/api/client.ts` lit `body.message`. Deux corrections
possibles : poser `server.error.include-message: always`, ou introduire un
`@ControllerAdvice` — cette seconde option étant de toute façon requise par le brief
de notation (« ne pas renvoyer de codes HTTP depuis la couche domaine »).

Codes utilisés : `400` validation, `403` propriété (capitaine), `404` introuvable,
`409` conflit d'état métier, `413` charge trop volumineuse, `502`/`503` dépendance
externe. `401` est produit par Spring Security en l'absence de token valide.

### Format du corps d'erreur

Toute erreur renvoie un `ProblemDetail` enrichi de deux champs, produit par
`web/GestionnaireErreurs` :

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Match déjà terminé",
  "message": "Match déjà terminé",
  "code": "CONFLIT"
}
```

- **`message`** — destiné à l'utilisateur, en français. C'est le champ lu par
  `src/api/client.ts` côté frontend.
- **`code`** — code fonctionnel **stable**, indépendant du statut HTTP :
  `INTROUVABLE`, `INVALIDE`, `CONFLIT`, `NON_AUTORISE`, `TROP_VOLUMINEUX`,
  `SERVICE_INDISPONIBLE`, `DEPENDANCE_EN_ECHEC`, `ERREUR_INTERNE`. À préférer au
  statut pour un traitement conditionnel côté client.

Une erreur inattendue (500) ne renvoie **jamais** son message d'origine : il
pourrait exposer un détail d'implémentation. Le domaine, lui, ne connaît pas HTTP —
il lève des `ErreurMetier` ou `ErreurTechnique` (`docs/adr/0007-erreurs-metier-et-http.md`).

---

## Endpoints attendus par la spec mais absents

Pour éviter de les chercher — détail et priorisation dans
`ETAT-DES-LIEUX.md` :

- **Cycle de vie du tournoi** : aucune route pour passer `draft → registration →
  check_in`, ni pour annuler. Le tournoi ne bouge qu'en `ongoing` / `finished`, et
  seulement comme effet de bord d'une saisie de score.
- **Check-in** des participants (spec §4.3).
- **Co-organisateurs** : aucun ajout de `co_organizer` (spec §4.1).
- **Export Excel** : le worker sait le produire (`export_excel`) mais aucune route
  ne l'expose encore — `JobService.submitTournamentExport` est prêt, il manque le
  controller et la résolution des équipes/matchs du tournoi.
- **Litiges, forfaits, disqualifications, planification** des matchs (spec §4.4).
- **Notifications** configurables (spec §5).
- **Temps réel** (WebSocket / SSE) pour les brackets et scores (spec §5).
