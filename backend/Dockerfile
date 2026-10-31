# syntax=docker/dockerfile:1
# Base images are pinned by digest; Dependabot (docker ecosystem) keeps them current.

FROM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src src
# Tests, Checkstyle, PMD and JaCoCo run in CI (bound to `verify`), not when packaging the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/helpdesk-backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
# /usr/bin/pebble (ACME test server written in Go) ships in the base image, is unused by the app and
# carries Go stdlib CVEs; drop it instead of suppressing the findings.
RUN rm -f /usr/bin/pebble \
    && groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid 10001 --no-create-home app
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
