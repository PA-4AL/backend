# ADR-0007 — Séparer erreurs métier et codes HTTP

- **Date** : 2026-07-29
- **Statut** : **accepté** — appliqué le 2026-07-29
- **Portée** : backend

## Contexte

Le brief demande de séparer les erreurs métier des erreurs techniques et, en
particulier, de **ne pas renvoyer de code HTTP depuis le domaine**. Or la couche
`service/` lève aujourd'hui des `ResponseStatusException(HttpStatus.X, "message")`
— 54 occurrences réparties sur six services. Le domaine importe donc
`org.springframework.http`.

À titre de comparaison, le worker fait ce qu'il faut : un enum `WorkerError`
métier, dont les codes fonctionnels (`INVALID_SCHEMA`, `PARSE_ERROR`) ne sont
traduits qu'à la frontière.

## Décision

Appliquée :

1. une hiérarchie d'exceptions métier dans le domaine
   (`TournamentNotFound`, `MatchAlreadyFinished`, `DrawNotAllowed`…), sans aucune
   dépendance à Spring Web ;
2. un `@ControllerAdvice` unique qui traduit chaque type en statut HTTP et en
   corps d'erreur ;
3. suppression de l'import `org.springframework.http` de `service/`.

## Conséquences

- `service/` n'importe plus `org.springframework.http` : **50 exceptions HTTP
  remplacées** par des erreurs du domaine, dans les six services et le publieur
  Pub/Sub
- les correspondances statut ↔ erreur sont centralisées dans un seul fichier
- les tests du domaine assertent désormais la **sémantique métier**
  (`ErreurMetier.Conflit` et son message) et non plus un code HTTP
- **effet de bord bénéfique** : le message d'erreur parvient enfin au client.
  `ResponseStatusException` ne l'incluait pas dans la réponse sans
  `server.error.include-message`, donc le frontend affichait des erreurs muettes
  alors qu'il sait lire le champ `message`
- un code fonctionnel stable (`CONFLIT`, `INTROUVABLE`…) accompagne chaque
  réponse, exploitable côté client indépendamment du statut
- **piège rencontré en cours de route** : un filet de sécurité sur `Exception`
  attrape aussi les exceptions de Spring. Un refus de `@PreAuthorize` devenait un
  500 au lieu d'un 403, et un JSON illisible un 500 au lieu d'un 400. D'où
  l'héritage de `ResponseEntityExceptionHandler` et un gestionnaire dédié à
  `AccessDeniedException`, tous deux couverts par un test
