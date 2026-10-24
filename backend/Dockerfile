# syntax=docker/dockerfile:1
# Base images are pinned by digest; Dependabot (docker ecosystem) keeps them current.

FROM eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src src
# Tests, Checkstyle, PMD and JaCoCo run in CI (bound to `verify`), not when packaging the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/helpdesk-backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
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
