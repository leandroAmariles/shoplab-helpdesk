---
name: migracion
description: End-to-end Spring Boot 4 migration orchestrator. Use whenever the user simply asks to migrate a Java/Maven service to Spring Boot 4, Spring 7, Jackson 3 or the BAC parent ("migra este repo", "haz la migración", "migrate to Spring Boot 4"). Collects the missing inputs, installs the tooling if needed, then runs code-context-builder followed by spring-boot-4-migration, gating each step on the previous one.
---

# migracion — Spring Boot 4 migration orchestrator

One entry point for the pipeline. It does not duplicate the rules of the two skills it drives; it
collects inputs, verifies prerequisites, runs them in order, and stops at each gate. Reply in the
user's language.

Sequence: **inputs → prerequisites → code-context-builder → gate → spring-boot-4-migration → report**.

## Step 1 — Collect inputs (ask only what is missing)
- Maven reactor root to migrate.
- Approved parent POM.
- Maven profiles.
- Modules to exclude.

## Step 2 — Verify prerequisites
1. `migration-tools/code-context-builder/pom.xml` exists.
2. `mvn -version` on JDK 17+.

## Step 3 — Generate context
Invoke `code-context-builder`.

## Step 4 — Migrate
Invoke `spring-boot-4-migration`.

## Step 5 — Final report
