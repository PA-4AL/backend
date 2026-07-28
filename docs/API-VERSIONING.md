# Versionnement de l'API et gestion des breaking changes

> **Statut :** en vigueur depuis le 25/07/2026 · **Version courante de l'API : v1**
> Ce fichier est **la procédure** exigée par le brief de notation (§4 « Gestion des
> breaking changes »). Il est versionné avec le code et doit être tenu à jour.

## Pourquoi

Le frontend et le backend sont considérés comme développés par deux équipes qui n'ont
ni le même rythme ni les mêmes priorités. Le backend doit donc pouvoir livrer un
changement incompatible **sans casser le frontend en production**, et il doit être
possible de démontrer que le frontend peut consommer l'une **ou** l'autre version
**sans redéployer le backend**.

Le mécanisme retenu répond à cette contrainte : les deux versions cohabitent dans le
même artefact déployé. Basculer d'une version à l'autre est une décision **du client**,
prise appel par appel.

## Note de vocabulaire

« V1 » désigne déjà, partout ailleurs dans la documentation, le **périmètre
fonctionnel** du MVP (« Formats supportés (V1) » dans la spec, « élimination simple
uniquement en V1 » dans `BracketService`). Ici, **v1 minuscule désigne la version du
contrat HTTP**. Les deux notions sont indépendantes : la V1 produit peut évoluer sans
changer la v1 d'API, et inversement.

## Mécanisme

Versionnement natif de Spring Framework 7, configuré dans
`src/main/kotlin/org/example/backend/config/WebMvcConfig.kt` :

| Élément | Choix |
|---|---|
| Emplacement de la version | 2ᵉ segment du chemin : `/api/v1/…` |
| Résolution | `ApiVersionConfigurer.usePathSegment(1)` |
| Préfixe des routes | `addPathPrefix("/api/{version}", forBasePackage("…controller"))` |
| Déclaration côté controller | `@RequestMapping(…, version = "1+")` |
| Organisation du code | un paquet par version : `controller/v1/`, `controller/v2/`… |
| Nommage des classes | suffixe de version : `TournamentV1Controller` |

Trois points à connaître :

1. **`v1` est bien parsé.** Le parseur sémantique de Spring ignore les caractères non
   numériques de tête : `v1` devient `1.0.0`. Pas besoin d'écrire `/api/1/`.
2. **`"1+"` est une *baseline*, pas une version fixe.** La route répond à la v1 **et à
   toutes les versions supérieures**, jusqu'à ce qu'un controller d'une version plus
   haute prenne la main sur la même route. C'est ce qui évite de recopier les 25 routes
   à chaque nouvelle version.
3. **La version déclarée au niveau de la classe suffit.** Spring combine les conditions
   en donnant la priorité au niveau méthode s'il en porte une, sinon il hérite de la
   classe. Une seule annotation par controller.

### Ce que ça donne concrètement

Avec `TournamentV1Controller` en `version = "1+"` et, plus tard, un
`TournamentV2Controller` en `version = "2+"` qui ne redéclare **que** `GET /tournaments` :

| Requête | Servie par | Pourquoi |
|---|---|---|
| `GET /api/v1/tournaments` | v1 | version demandée = 1 |
| `GET /api/v2/tournaments` | **v2** | la version la plus haute applicable gagne |
| `GET /api/v2/tournaments/{id}` | **v1** | jamais redéclarée en v2 → baseline `1+` |
| `GET /api/v2/teams/mine` | **v1** | idem |

Vérifié en conditions réelles le 25/07/2026 sur ce dépôt.

### Côté client

`frontend/src/api/client.ts` expose un helper par version :

```ts
export const v1 = (path: string): string => `/api/v1${path}`
// export const v2 = (path: string): string => `/api/v2${path}`
```

Les wrappers appellent `apiGet(v1('/tournaments'))`. **La version est donc choisie
appel par appel** : le frontend peut consommer des routes v1 et v2 simultanément, et
migrer une route à la fois. C'est volontaire — un préfixe global unique obligerait à
tout basculer d'un coup.

## Procédure : livrer un changement incompatible

Un changement est **incompatible** s'il retire ou renomme un champ de réponse, change
le type ou le sens d'un champ, rend obligatoire un champ de requête qui ne l'était pas,
modifie un code de statut attendu, ou change la sémantique d'une route à structure
identique. Ajouter un champ optionnel de requête ou un champ de réponse **n'est pas**
incompatible : cela reste dans la version courante.

1. **Créer le paquet de la nouvelle version** si besoin :
   `src/main/kotlin/org/example/backend/controller/v2/`.
2. **N'y écrire que les routes qui changent**, une classe suffixée `V2` :
   ```kotlin
   @RestController
   @RequestMapping("/tournaments", version = "2+")
   class TournamentV2Controller(private val service: TournamentService) { … }
   ```
   Le suffixe n'est pas cosmétique : le nom de bean Spring est le nom simple de la
   classe, deux `TournamentController` dans deux paquets entreraient en conflit.
3. **Ne pas toucher au code v1.** C'est la garantie de non-régression.
4. **Garder la logique dans les services.** Une version d'API est une couche de
   présentation : les `service/` et `repository/` restent communs. Si la règle métier
   elle-même change, ajouter une méthode au service plutôt que de dupliquer la logique
   dans le controller.
5. **Déclarer l'accès public éventuel** dans `SecurityConfig` — il y a **une ligne par
   version**, volontairement :
   ```kotlin
   .requestMatchers(HttpMethod.GET, "/api/v2/tournaments/**").permitAll()
   ```
   Une nouvelle version ne doit pas hériter silencieusement d'un accès anonyme.
6. **Rien à ajouter dans `WebMvcConfig`** : le préfixe couvre déjà tout le paquet
   `controller`, et les versions présentes dans les mappings sont détectées
   automatiquement (`detectSupportedVersions`, actif par défaut).
7. **Documenter** : mettre à jour `docs/ENDPOINTS.md` (la route existe désormais en
   deux versions, avec les deux formats de réponse) et la ligne « Version courante »
   en tête de ce fichier.
8. **Tester** : ajouter à `ApiVersioningTests` un cas qui vérifie que l'ancienne
   version répond toujours l'ancien format.
9. **Déployer le backend** — les deux versions sont désormais en ligne.
10. **Migrer le frontend quand il est prêt**, route par route : décommenter le helper
    `v2` et changer le seul appel concerné. Le backend n'est pas redéployé à cette
    étape : c'est exactement la démonstration attendue au rendu.

## Dépréciation d'une version

Spring fournit `StandardApiVersionDeprecationHandler`, qui ajoute les en-têtes
standards `Deprecation` et `Sunset` aux réponses d'une version obsolète. À brancher
dans `WebMvcConfig` au moment de déprécier :

```kotlin
override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
    val deprecations = StandardApiVersionDeprecationHandler().apply {
        configureVersion("1").setDeprecationDate(…).setSunsetDate(…)
    }
    configurer.usePathSegment(1).addSupportedVersions("1", "2").setDeprecationHandler(deprecations)
}
```

Règle retenue : une version dépréciée reste servie **au moins jusqu'à ce que le
frontend n'en dépende plus**, vérifiable par un `grep` de `v1(` dans
`frontend/src/api/`. Sa suppression consiste à retirer le paquet `controller/v1/`, sa
ligne de sécurité et son helper côté client.

## Comportements à connaître

- **Les routes non versionnées ne sont plus servies.** `/api/tournaments` renvoie
  **401**, pas 404 : la chaîne de filtres Spring Security s'exécute avant le routage
  et aucun matcher ne rend ce chemin public. Même chose pour une version inexistante
  (`/api/v9/tournaments`). Ce n'est pas un choix mais une conséquence de l'ordre des
  filtres ; c'est testé dans `ApiVersioningTests` pour éviter une régression silencieuse.
- **Le préfixe n'est jamais écrit dans les controllers.** Chercher `/api` dans
  `controller/` ne donne donc rien : la vue d'ensemble des routes complètes est dans
  `docs/ENDPOINTS.md`.
- **L'en-tête `Location`** de `POST /tournaments` est construit depuis la requête
  courante (`ServletUriComponentsBuilder.fromCurrentRequestUri()`), il porte donc
  automatiquement la bonne version.
- **CORS** est configuré sur `/api/**`, qui couvre toutes les versions — rien à
  ajouter.

## Ce que ce mécanisme ne couvre pas

Le brief cite trois options, dont deux ne sont pas mises en place ici :

- **Versionnement des schémas d'entrée** : non fait. Un changement incompatible sur un
  corps de requête passe aujourd'hui par une nouvelle version de route.
- **Feature flags** : non fait. Il n'y a aucun mécanisme d'activation à chaud ; la
  bascule est décidée par le client via l'URL appelée, ce qui remplit la même fonction
  pour le cas exigé (« le frontend peut utiliser l'une ou l'autre des versions »).
- **Versionnement du schéma de base** : couvert séparément par Liquibase
  (`docs/LIQUIBASE.md`), pas par ce document.
