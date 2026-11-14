# syntax=docker/dockerfile:1
# Build context is the repo root (the parent pom.xml is needed), see docker-compose.yml.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ingest-static -am -DskipTests package

FROM eclipse-temurin:21-jre
# curl is used by the docker-compose healthcheck (/actuator/health)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/ingest-static/target/ingest-static-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
