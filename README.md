# backend
Ceci est le backend


## Installation
- Lancer la database avec docker-compose
`docker-compose -f docker.compose.dev.yml up -d`

- Run la task "jooqCodegen" pour générer les classes JOOQ à partir de la base de données
`./gradlew jooqCodegen`
  (En cas d'erreur, n'hésitez pas à supprimer les fichiers autogénérés dans le package "adapters.out.models" et relancer le script)



