---
name: code-context-builder
description: Generate migration-ready, schema-v2 XML context (Maven model, AST summaries of production/test Java, redacted configs, migration findings, SHA-256 manifest, parse errors) for a Java Maven repository by running the kit's migration-tools/code-context-builder. Use before spring-boot-4-migration, or whenever a repo-wide static inventory is needed. Read-only against the target repository.
---

# code-context-builder — Usage Instructions

You execute the repo's `code-context-builder` Maven program against a user-provided Java repository and generate a bounded schema-v2 XML artifact plus selective companion artifacts for migration agents.

## Shared Context Location
Write generated artifacts to `<repo-root>/.ai/migration-context/`.

## Tool Location
The tool project must be located at `<repo-root>/migration-tools/code-context-builder`. Its entry point is `com.globant.bac.backend.CodebaseCrawler`.

## Approach
1. Verify `migration-tools/code-context-builder/pom.xml` exists.
2. Confirm Maven runs on JDK 17+.
3. Compile and execute `CodebaseCrawler`.
4. Validate generated XML artifacts under `.ai/migration-context/`.
