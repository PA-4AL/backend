# ADR-0001 — jOOQ pour l'accès aux données, Liquibase pour le schéma

- **Date** : 2026-06-23
- **Statut** : accepté
- **Portée** : backend

## Contexte

Le schéma de la spécification (§6) est riche en énumérations PostgreSQL et en
relations, et les écrans attendent des projections sur mesure plutôt que des
entités complètes. Il faut par ailleurs versionner l'évolution du schéma.

## Décision

**jOOQ** pour les requêtes, avec génération du code à partir de la base réelle,
et **Liquibase** pour les migrations, exécutées au démarrage de l'application.

Écarté : **JPA/Hibernate** — le mapping objet-relationnel apporte peu ici, où
presque toutes les lectures sont des projections destinées à un écran, et il
masque le SQL que l'on veut maîtriser (jointures de bracket, agrégats du tableau
de bord).

## Conséquences

- requêtes typées, vérifiées à la compilation contre le schéma réel
- les énumérations PostgreSQL deviennent des enums Kotlin dont la valeur SQL est
  exposée par `.literal` — c'est ce `literal` qui transite dans les DTOs
- **l'ordre compte** : appliquer un changeset Liquibase, puis régénérer le code
  jOOQ, puis commiter le code généré
- le code généré (`database/`) est versionné et ne doit jamais être édité à la
  main ; il est exclu du linter
- une migration en échec empêche le démarrage de la révision : Cloud Run
  conserve alors l'ancienne, ce qui est le comportement souhaité
