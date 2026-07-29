# ADR-0006 — Tests unitaires sans base de données

- **Date** : 2026-07-28
- **Statut** : accepté
- **Portée** : backend

## Contexte

Le seul test existant était un `@SpringBootTest` de chargement de contexte. Il
démarrait Liquibase et le DataSource, donc **exigeait une base joignable** : il
échouait systématiquement en intégration continue, où aucune base n'existait.

## Décision

Écrire les tests du domaine en **tests unitaires purs**, dépôts mockés avec
**MockK** : refus du match nul, non-rejouabilité d'un match terminé, propagation
du vainqueur dans le bon slot, fin de tournoi sur la finale, contrat de messages.

Les tests qui chargent réellement le contexte (versionnement d'API, routes
internes) sont conservés, et la CI leur fournit **un service PostgreSQL**.

## Conséquences

- le domaine est testable sans infrastructure : les tests tournent en secondes
- la CI est verte de manière reproductible
- deux catégories de tests cohabitent, avec des besoins différents — le service
  PostgreSQL de la CI est nécessaire aux seconds
- il n'y a **pas** encore de test d'intégration sur les dépôts jOOQ : le SQL n'est
  vérifié qu'au démarrage de l'application. Piste : Testcontainers
