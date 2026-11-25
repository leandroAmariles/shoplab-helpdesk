package com.shoplab.template.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One throwaway PostgreSQL for the whole test JVM, same image as the local compose and dev. */
public final class TestDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.7-pg16")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    public static synchronized PostgreSQLContainer instance() {
        if (container == null) {
            container = new PostgreSQLContainer(IMAGE);
            container.start();
        }
        return container;
    }
}
