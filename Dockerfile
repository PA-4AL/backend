# =============================================================================
# Backend Kotlin/Spring Boot — build Gradle puis JRE seule, en couches.
# Contexte de build : la racine de ce repo (`docker build .`).
#
# Choix des images (justifications : infra/docs/DOCKER.md) :
#   - eclipse-temurin:21-jdk               → JDK d'Adoptium (Eclipse Foundation),
#     étage de build jeté à la fin. On utilise le wrapper Gradle du repo
#     (Gradle 9.3) plutôt que l'image `gradle:*` : garantie d'avoir exactement la
#     même version de Gradle qu'en local et en CI.
#   - eclipse-temurin:21.0.10_7-jre-alpine → JRE seule (pas de compilateur),
#     base Alpine, tag épinglé au patch. Java 21 LTS car la toolchain du projet
#     est en 21 (Kotlin 2.2 ne cible pas au-delà de 24).
#
# Les tests ne sont PAS joués ici : c'est le job `test` de la CI qui s'en charge
# (retours plus lisibles, cache Gradle partagé entre les jobs).
# =============================================================================

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 1) Wrapper Gradle : télécharge la distribution une fois pour toutes.
COPY gradlew ./
COPY gradle ./gradle
RUN ./gradlew --version --no-daemon

# 2) Scripts de build : résout et met en cache les dépendances. Couche
#    invalidée seulement si build.gradle / settings.gradle changent.
COPY settings.gradle build.gradle ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath

# 3) Sources : seule cette couche est reconstruite au quotidien.
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
 && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)" app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted


FROM eclipse-temurin:21.0.10_7-jre-alpine AS runtime
WORKDIR /app

# Utilisateur non privilégié (l'image Temurin tourne en root par défaut).
RUN addgroup -S spring && adduser -S -G spring spring

# Couches ordonnées du plus stable au plus volatil : un changement de code ne
# réexpédie que la couche `application` (quelques centaines de Ko au lieu du jar
# complet de ~60 Mo).
COPY --from=build --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /app/extracted/application/ ./

USER spring

# Cloud Run plafonne la mémoire du conteneur : la JVM doit s'y adapter.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
