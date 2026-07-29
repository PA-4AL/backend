# ADR-0007 — Séparer erreurs métier et codes HTTP

- **Date** : 2026-07-29
- **Statut** : **proposé** — écart connu, non encore corrigé
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

Cible retenue, **non encore appliquée** :

1. une hiérarchie d'exceptions métier dans le domaine
   (`TournamentNotFound`, `MatchAlreadyFinished`, `DrawNotAllowed`…), sans aucune
   dépendance à Spring Web ;
2. un `@ControllerAdvice` unique qui traduit chaque type en statut HTTP et en
   corps d'erreur ;
3. suppression de l'import `org.springframework.http` de `service/`.

## Conséquences

- le domaine deviendrait réutilisable hors contexte web et testable sans Spring
- les correspondances statut ↔ erreur seraient centralisées en un seul endroit
- le chantier touche 54 emplacements dans six services, dont certains évoluent en
  parallèle : à mener en une passe dédiée, service par service, en commençant par
  `BracketService` (le plus critique)
- **en attendant, l'écart est assumé et documenté** — c'est précisément l'objet de
  cet ADR : ne pas laisser croire que la décision a été prise par ignorance
