# ADR-0002 — Versionner l'API par segment de chemin

- **Date** : 2026-07-25
- **Statut** : accepté
- **Portée** : backend, frontend

## Contexte

Le brief exige une procédure de gestion des changements incompatibles. Le
frontend consomme une API dont les DTOs sont taillés pour ses écrans : un
changement de contrat casse l'interface.

## Décision

Versionner par **segment de chemin** (`/api/v1/…`), le préfixe étant appliqué
globalement par `config/WebMvcConfig.kt` à tous les controllers du paquet
`controller`. Un paquet par version (`controller/v1`), classes suffixées `V1`,
et `@RequestMapping(version = "1+")` pour qu'un controller continue de répondre
aux versions supérieures tant qu'aucun controller plus récent ne prend la main.

Écartés : **en-tête `Accept` versionné** (invisible dans les logs et les outils,
plus difficile à tester à la main) ; **paramètre de requête** (facile à oublier).

Procédure complète : `docs/API-VERSIONING.md`.

## Conséquences

- une route qui passe en v2 n'oblige pas les autres à bouger
- côté frontend, la version est choisie **appel par appel** via un helper `v1()`
- l'accès public doit être déclaré **version par version** dans la configuration
  de sécurité : une nouvelle version n'hérite pas de l'anonymat
- **piège constaté** : la résolution de version s'applique à *toutes* les routes
  du DispatcherServlet, pas seulement à celles sous `/api` (voir ADR-0004)
