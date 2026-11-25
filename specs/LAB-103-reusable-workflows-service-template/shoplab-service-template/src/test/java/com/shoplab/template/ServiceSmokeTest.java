package com.shoplab.template;

import com.shoplab.template.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The service as it comes out of the template: it starts, migrates, reports its health and asks for no token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceSmokeTest {

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabase.instance().getJdbcUrl());
        registry.add("spring.datasource.username", () -> TestDatabase.instance().getUsername());
        registry.add("spring.datasource.password", () -> TestDatabase.instance().getPassword());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void reportsLivenessAndReadiness() {
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void doesNotAskForATokenWhileThereIsNoLogin() {
        client().get().uri("/api/anything").exchange().expectStatus().isNotFound();
    }

    @Test
    void keepsTheCorrelationIdOfTheRequestAndCreatesOneWhenMissing() {
        client().get().uri("/api/anything").header("X-Request-Id", "abc-123").exchange()
                .expectHeader().valueEquals("X-Request-Id", "abc-123");
        client().get().uri("/api/anything").exchange().expectHeader().exists("X-Request-Id");
    }
}
