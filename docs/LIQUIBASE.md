# Documentation Liquibase — Guide Complet

> Stack ciblé : **Spring Boot 4 + PostgreSQL + Gradle + Kotlin**

---

## Table des matières

1. [Qu'est-ce que Liquibase ?](#1-quest-ce-que-liquibase-)
2. [Installation](#2-installation)
3. [Structure des fichiers](#3-structure-des-fichiers)
4. [Les formats de changelog](#4-les-formats-de-changelog)
5. [Le fichier racine](#5-le-fichier-racine-dbchangelog-masterxml)
6. [Configuration Spring Boot](#6-configuration-spring-boot)
7. [Concepts clés](#7-concepts-clés)
8. [La table DATABASECHANGELOG](#8-la-table-databasechangelog)
9. [Commandes Liquibase](#9-commandes-liquibase)
10. [Workflow quotidien](#10-workflow-quotidien)
11. [Migration depuis le db.sql existant](#11-migration-depuis-le-dbsql-existant)
12. [Intégration avec jOOQ](#12-intégration-avec-jooq)

---

## 1. Qu'est-ce que Liquibase ?

Liquibase est un outil de **migration de base de données** versionné. Il remplace les scripts SQL lancés à la main par un système déclaratif et traçable :

- Chaque modification du schéma est un **changeset** (unité atomique)
- Les changesets sont listés dans un **changelog** (fichier de référence)
- Liquibase crée une table `DATABASECHANGELOG` dans ta BDD pour savoir ce qui a déjà été appliqué
- Au démarrage de l'application, il applique automatiquement les changesets manquants

---

## 2. Installation

### 2.1 Dépendance Gradle

Dans `build.gradle`, ajouter :

```groovy
dependencies {
    implementation 'org.liquibase:liquibase-core'
    // le reste de tes dépendances existantes...
}
```

Spring Boot auto-configure Liquibase dès que `liquibase-core` est dans le classpath. Pas besoin de configuration supplémentaire pour démarrer.

---

## 3. Structure des fichiers

```
src/main/resources/
└── db/
    └── changelog/
        ├── db.changelog-master.xml   ← fichier racine (point d'entrée)
        ├── migrations/
        │   ├── V1__init.sql
        │   ├── V2__add_tournament_status.sql
        │   └── V3__add_player_elo.sql
        └── data/
            └── seed.sql              ← données initiales (optionnel)
```

Le fichier racine `db.changelog-master.xml` inclut les autres fichiers dans l'ordre.

---

## 4. Les formats de changelog

Liquibase supporte 4 formats : **SQL** (le plus simple), XML, YAML, JSON.

### Format SQL (recommandé pour ce projet)

Chaque fichier SQL doit commencer par `--liquibase formatted sql`.

```sql
--liquibase formatted sql

--changeset alex:1
CREATE TABLE users_db (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    role          VARCHAR(20) DEFAULT 'USER',
    created_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE users_db;

--changeset alex:2
CREATE TABLE tournament_db (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    start_date  DATE,
    end_date    DATE,
    max_players INTEGER,
    status      VARCHAR(20) DEFAULT 'PENDING'
);
--rollback DROP TABLE tournament_db;
```

**Règles importantes :**
- `--liquibase formatted sql` doit être sur la **première ligne**, sans espace avant
- Chaque changeset a un `author:id` unique dans le fichier
- `--rollback` définit comment annuler le changeset (obligatoire pour les rollbacks)
- Un seul changeset par unité logique de changement (une table, une colonne, etc.)

### Format XML (plus verbeux mais plus expressif)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="1" author="alex">
        <createTable tableName="users_db">
            <column name="id" type="BIGINT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="username" type="VARCHAR(50)">
                <constraints unique="true" nullable="false"/>
            </column>
        </createTable>
        <rollback>
            <dropTable tableName="users_db"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

---

## 5. Le fichier racine `db.changelog-master.xml`

Ce fichier inclut tous les autres dans l'ordre d'exécution :

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <include file="db/changelog/migrations/V1__init.sql"/>
    <include file="db/changelog/migrations/V2__add_tournament_status.sql"/>
    <include file="db/changelog/data/seed.sql"/>

</databaseChangeLog>
```

> Tu peux aussi utiliser `<includeAll path="db/changelog/migrations/"/>` pour inclure automatiquement tous les fichiers d'un dossier (dans l'ordre alphabétique).

---

## 6. Configuration Spring Boot

Dans `src/main/resources/application.yml` :

```yaml
spring:
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    enabled: true

  datasource:
    url: jdbc:postgresql://localhost:5432/devdb
    username: devuser
    password: devpassword
    driver-class-name: org.postgresql.Driver
```

### Propriétés complètes disponibles

| Propriété | Défaut | Description |
|---|---|---|
| `spring.liquibase.change-log` | `db/changelog/db.changelog-master.yaml` | Chemin vers le fichier racine |
| `spring.liquibase.enabled` | `true` | Activer/désactiver Liquibase |
| `spring.liquibase.contexts` | — | Contextes actifs (ex: `dev,test`) |
| `spring.liquibase.label-filter` | — | Labels à exécuter |
| `spring.liquibase.default-schema` | — | Schéma PostgreSQL cible |
| `spring.liquibase.url` | datasource URL | URL JDBC dédiée (si différente) |
| `spring.liquibase.user` | datasource user | User dédié Liquibase |
| `spring.liquibase.password` | datasource password | Password dédié Liquibase |
| `spring.liquibase.tag` | — | Tag appliqué après le dernier update |
| `spring.liquibase.rollback-file` | — | Fichier SQL de rollback généré |
| `spring.liquibase.test-rollback-on-update` | `false` | Teste update→rollback→update au démarrage |
| `spring.liquibase.show-summary` | `summary` | Résumé affiché (`off`, `summary`, `verbose`) |
| `spring.liquibase.drop-first` | `false` | Drops tout le schéma avant migration (**dangereux**) |
| `spring.liquibase.clear-checksums` | `false` | Recalcule les checksums (si modif d'un changeset) |

### Configuration par environnement

```yaml
# application-dev.yml
spring:
  liquibase:
    contexts: dev

# application-prod.yml
spring:
  liquibase:
    contexts: prod
```

---

## 7. Concepts clés

### 7.1 Le Changeset

Unité atomique d'une migration. Identifié par `author:id:filename` (triplet unique).

```sql
--changeset alex:3 context:prod
ALTER TABLE users_db ADD COLUMN email VARCHAR(255);
--rollback ALTER TABLE users_db DROP COLUMN email;
```

**Attributs disponibles :**

| Attribut | Description |
|---|---|
| `context` | N'exécute ce changeset que si le contexte correspond |
| `labels` | Filtrage par label au lancement |
| `dbms` | Restreint à un SGBD (`postgresql`, `mysql`, etc.) |
| `runOnChange` | Ré-exécute si le contenu change (utile pour les vues/fonctions) |
| `runAlways` | Exécute à chaque démarrage (utile pour les procédures stockées) |
| `failOnError` | `true` par défaut — arrête la migration en cas d'erreur |
| `ignore` | Ignore ce changeset comme s'il n'existait pas |
| `endDelimiter` | Délimiteur de fin de statement (défaut : `;`) |

### 7.2 Les Contexts

Permettent d'exécuter des changesets **seulement dans certains environnements** :

```sql
--changeset alex:seed-data context:dev
INSERT INTO users_db (id, username, password_hash) VALUES (1, 'admin', 'hash');
--rollback DELETE FROM users_db WHERE id = 1;
```

Activé dans `application.yml` :
```yaml
spring.liquibase.contexts: dev
```

### 7.3 Les Labels

Similaires aux contexts mais filtrés **côté déploiement** (pas côté changeset) :

```sql
--changeset alex:4 labels:release-2.0
ALTER TABLE tournament_db ADD COLUMN description TEXT;
--rollback ALTER TABLE tournament_db DROP COLUMN description;
```

Activé dans `application.yml` :
```yaml
spring.liquibase.label-filter: release-2.0
```

### 7.4 Les Préconditions

Vérifient l'état de la BDD avant d'appliquer un changeset :

```sql
--changeset alex:5
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name='users_db' AND column_name='email'
ALTER TABLE users_db ADD COLUMN email VARCHAR(255);
--rollback ALTER TABLE users_db DROP COLUMN email;
```

**Options `onFail`/`onError` :**

| Valeur | Comportement |
|---|---|
| `HALT` | Arrête tout (défaut) |
| `CONTINUE` | Ignore ce changeset et passe au suivant |
| `MARK_RAN` | Marque comme exécuté sans l'exécuter |
| `WARN` | Log un warning et continue |

### 7.5 Les Tags

Permettent de pointer un état précis du schéma pour les rollbacks :

```sql
--changeset alex:tag-v1.0 runAlways:false
--comment: Tag release v1.0
```

Ou via la commande :
```bash
liquibase tag v1.0 --url=... --username=... --password=...
```

Puis pour rollback jusqu'à ce tag :
```bash
liquibase rollback v1.0 --changelog-file=... --url=... --username=... --password=...
```

---

## 8. La table DATABASECHANGELOG

Créée automatiquement par Liquibase dans ta BDD. Elle trace chaque migration appliquée :

| Colonne | Description |
|---|---|
| `ID` | L'id du changeset |
| `AUTHOR` | L'auteur du changeset |
| `FILENAME` | Le fichier source |
| `DATEEXECUTED` | Date d'exécution |
| `ORDEREXECUTED` | Ordre d'exécution (utilisé pour les rollbacks) |
| `EXECTYPE` | `EXECUTED`, `FAILED`, `SKIPPED`, `RERAN`, `MARK_RAN` |
| `MD5SUM` | Checksum du changeset (Liquibase détecte les modifications non autorisées) |
| `DESCRIPTION` | Description auto-générée |
| `COMMENTS` | Commentaire `--comment:` du changeset |
| `TAG` | Tag associé |
| `CONTEXTS` | Contextes actifs au moment de l'exécution |
| `LABELS` | Labels actifs au moment de l'exécution |
| `DEPLOYMENT_ID` | Groupe tous les changesets d'un même démarrage |

> **Important :** ne jamais modifier manuellement un changeset déjà appliqué. Liquibase détecte le changement via le MD5SUM et bloque le démarrage. Si une correction est nécessaire, créer un nouveau changeset.

Une seconde table `DATABASECHANGELOGLOCK` est également créée pour gérer les accès concurrents (plusieurs instances de l'appli qui démarrent en même temps).

---

## 9. Commandes Liquibase

Toutes les commandes CLI nécessitent :
```
--changelog-file=src/main/resources/db/changelog/db.changelog-master.xml
--url=jdbc:postgresql://localhost:5432/devdb
--username=devuser
--password=devpassword
```

### 9.1 Commandes Update

| Commande | Description |
|---|---|
| `update` | Applique tous les changesets non encore exécutés |
| `update-sql` | Affiche le SQL qui serait exécuté sans l'appliquer |
| `update-count N` | Applique les N prochains changesets seulement |
| `update-count-sql N` | Aperçu SQL de `update-count` |
| `update-to-tag TAG` | Applique jusqu'au tag donné |
| `update-to-tag-sql TAG` | Aperçu SQL de `update-to-tag` |
| `update-testing-rollback` | Test complet : update → rollback → update |

### 9.2 Commandes Rollback

| Commande | Description |
|---|---|
| `rollback TAG` | Annule jusqu'au tag donné |
| `rollback-to-date DATE` | Annule jusqu'à une date (`YYYY-MM-DD` ou `YYYY-MM-DD HH:MM:SS`) |
| `rollback-count N` | Annule les N derniers changesets |
| `rollback-sql TAG` | Affiche le SQL de rollback sans l'exécuter |
| `rollback-to-date-sql DATE` | Aperçu SQL de `rollback-to-date` |
| `future-rollback-sql` | Génère le SQL pour annuler les changesets pas encore appliqués |

### 9.3 Commandes d'inspection

| Commande | Description |
|---|---|
| `status` | Liste les changesets non encore appliqués |
| `status --verbose` | Détail complet des changesets en attente |
| `validate` | Vérifie la syntaxe du changelog sans rien exécuter |
| `history` | Affiche tous les changesets déjà appliqués |
| `diff` | Compare deux BDD (utile pour détecter des dérives) |
| `snapshot` | Capture l'état actuel du schéma |
| `generate-changelog` | Génère un changelog depuis une BDD existante |
| `changelog-sync` | Marque tous les changesets comme déjà appliqués (sans les exécuter) |
| `changelog-sync-to-tag TAG` | Marque les changesets jusqu'au tag comme appliqués |

### 9.4 Avec Spring Boot Actuator

Si `spring-boot-starter-actuator` est ajouté, un endpoint est disponible :

```
GET /actuator/liquibase
```

Retourne la liste complète des changesets appliqués avec leurs métadonnées.

---

## 10. Workflow quotidien

### Ajouter une migration

1. Créer `src/main/resources/db/changelog/migrations/V4__ma_modification.sql`
2. Écrire le changeset avec son `--rollback`
3. Si tu n'utilises pas `includeAll`, ajouter l'include dans `db.changelog-master.xml`
4. Relancer l'appli — Liquibase applique automatiquement le nouveau changeset

### Annuler la dernière migration

```bash
liquibase rollback-count 1 \
  --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml \
  --url=jdbc:postgresql://localhost:5432/devdb \
  --username=devuser \
  --password=devpassword
```

### Vérifier ce qui va être appliqué

```bash
liquibase status --verbose \
  --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml \
  --url=jdbc:postgresql://localhost:5432/devdb \
  --username=devuser \
  --password=devpassword
```

### Prévisualiser le SQL sans l'exécuter

```bash
liquibase update-sql \
  --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml \
  --url=jdbc:postgresql://localhost:5432/devdb \
  --username=devuser \
  --password=devpassword
```

---

## 11. Migration depuis le `db.sql` existant

Pour intégrer le schéma actuel du projet dans Liquibase :

### Étape 1 — Créer `src/main/resources/db/changelog/migrations/V1__init.sql`

```sql
--liquibase formatted sql

--changeset alex:1
CREATE TABLE users_db (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    role          VARCHAR(20) DEFAULT 'USER',
    created_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE users_db;

--changeset alex:2
CREATE TABLE tournament_db (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    start_date  DATE,
    end_date    DATE,
    max_players INTEGER,
    status      VARCHAR(20) DEFAULT 'PENDING'
);
--rollback DROP TABLE tournament_db;

--changeset alex:3
CREATE TABLE player_db (
    id            BIGINT PRIMARY KEY,
    user_id       BIGINT REFERENCES player_db (id),
    tournament_id BIGINT       NOT NULL REFERENCES tournament_db (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    score         INTEGER   DEFAULT 0,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE player_db;

--changeset alex:4
CREATE TABLE match_db (
    id            BIGINT PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournament_db (id) ON DELETE CASCADE,
    player1_id    BIGINT NOT NULL REFERENCES player_db (id),
    player2_id    BIGINT NOT NULL REFERENCES player_db (id),
    winner_id     BIGINT REFERENCES player_db (id),
    score_player1 INTEGER   DEFAULT 0,
    score_player2 INTEGER   DEFAULT 0,
    played_at     TIMESTAMP,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tournament_id, player1_id, player2_id)
);
--rollback DROP TABLE match_db;
```

### Étape 2 — Créer `src/main/resources/db/changelog/db.changelog-master.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <includeAll path="db/changelog/migrations/"/>

</databaseChangeLog>
```

### Étape 3 — Mettre à jour `application.yml`

```yaml
spring:
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    enabled: true
```

### Étape 4 — Si la BDD existe déjà

Si le schéma est déjà en place en base et que tu ne veux pas le recréer, utilise `changelog-sync` pour marquer les changesets comme déjà appliqués :

```bash
liquibase changelog-sync \
  --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml \
  --url=jdbc:postgresql://localhost:5432/devdb \
  --username=devuser \
  --password=devpassword
```

---

## 12. Intégration avec jOOQ

Avec ce stack, l'ordre est important :

1. **Liquibase** s'exécute au démarrage de Spring Boot → le schéma est à jour
2. **jOOQ** génère son code à partir du schéma actuel (en développement)

Pour regénérer les classes jOOQ après une migration :

```bash
./gradlew jooqCodegen
```

En CI, s'assurer que la BDD est up et que Liquibase a tourné avant de lancer la génération jOOQ.

---

## Sources

- [Using Liquibase with Spring Boot](https://contribute.liquibase.com/extensions-integrations/directory/integration-docs/springboot/)
- [Configuring Liquibase with Spring Boot](https://contribute.liquibase.com/extensions-integrations/directory/integration-docs/springboot/configuration/)
- [Using Liquibase with PostgreSQL](https://docs.liquibase.com/start/tutorials/postgresql.html)
- [SQL Changelog Format](https://docs.liquibase.com/concepts/changelogs/sql-format.html)
- [Update Commands](https://docs.liquibase.com/commands/update/home.html)
- [Rollback Commands](https://docs.liquibase.com/commands/rollback/home.html)
- [DATABASECHANGELOG Table](https://docs.liquibase.com/concepts/tracking-tables/databasechangelog-table.html)
