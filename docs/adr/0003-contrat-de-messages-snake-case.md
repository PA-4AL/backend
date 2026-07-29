# ADR-0003 — Contrat de messages avec le worker en snake_case

- **Date** : 2026-07-28
- **Statut** : accepté
- **Portée** : backend, worker

## Contexte

Le worker est écrit en Rust et sérialise naturellement en `snake_case`
(`task_id`, `task_type`). Le backend est en Kotlin et sérialise en `camelCase`.
Le contrat de messages traverse donc deux conventions de nommage.

## Décision

Le format **du fil est le snake_case**, porté par des annotations `@JsonProperty`
sur `WorkerRequest` et `WorkerResponse` — dans les **deux** sens, sérialisation
comme désérialisation. Le contrat est verrouillé par `WorkerContractTest`, qui
relit des messages réels du worker.

## Conséquences

- une seule source de vérité pour le contrat : les annotations du DTO
- aucune configuration globale de l'`ObjectMapper` : le reste de l'API garde le
  camelCase attendu par le frontend
- **cette décision naît d'un incident** : la conversion n'existait qu'à la
  publication. En réception, `task_id` arrivait à `null`, le callback répondait
  400 et Pub/Sub rejouait les messages jusqu'à la file de rebut. Le défaut n'a
  été visible qu'en production
