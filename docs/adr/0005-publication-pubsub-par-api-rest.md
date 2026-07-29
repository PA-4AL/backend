# ADR-0005 — Publier sur Pub/Sub par son API REST

- **Date** : 2026-07-28
- **Statut** : accepté
- **Portée** : backend

## Contexte

Le backend doit publier des messages sur un topic Pub/Sub. Le client officiel
`com.google.cloud:google-cloud-pubsub` embarque toute la pile gRPC et Netty,
soit une quarantaine de mégaoctets de dépendances transitives, pour un unique
appel de publication.

## Décision

Publier via l'**API REST de Pub/Sub** (`RestClient` de Spring), en signant les
appels avec un jeton obtenu de `com.google.auth:google-auth-library-oauth2-http`
depuis les identifiants par défaut de l'environnement (ADC).

## Conséquences

- une seule petite dépendance externe ajoutée, sans gRPC ni Netty
- sur Cloud Run, l'authentification utilise l'identité d'exécution du service :
  aucune clé à gérer
- les identifiants sont résolus **paresseusement** pour que l'application démarre
  sur un poste sans ADC
- en contrepartie, on renonce au traitement par lots et au contrôle de flux du
  client officiel — acceptable au volume visé (un message par import ou export)
- si le volume augmentait fortement, cet ADR devrait être remplacé
