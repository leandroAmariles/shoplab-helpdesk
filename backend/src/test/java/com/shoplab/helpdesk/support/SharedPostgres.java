package com.shoplab.helpdesk.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One throwaway PostgreSQL (same image as the local compose and dev) for the whole test JVM: every application
 * context points at it, so the suite pays the container start once instead of once per context (specs/LAB-105, D-4).
 * Testcontainers' reaper removes the container when the JVM exits.
 */
public final class SharedPostgres {

    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.7-pg16")
            .asCompatibleSubstituteFor("postgres");

    private static final int MAX_CONNECTIONS = 300;

    private static PostgreSQLContainer container;

    private SharedPostgres() {
    }

    public static synchronized PostgreSQLContainer instance() {
        if (container == null) {
            container = new PostgreSQLContainer(IMAGE);
            // Every cached application context keeps its own connection pool against this one server: the default
            // limit of 100 connections runs out once the suite has enough distinct contexts.
            container.withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);
            container.start();
        }
        return container;
    }

    /** Points {@code spring.datasource.*} of the context under test at the shared container. */
    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            PostgreSQLContainer postgres = instance();
            TestPropertyValues.of(
                    "spring.datasource.url=" + postgres.getJdbcUrl(),
                    "spring.datasource.username=" + postgres.getUsername(),
                    "spring.datasource.password=" + postgres.getPassword()).applyTo(context);
        }
    }
}
