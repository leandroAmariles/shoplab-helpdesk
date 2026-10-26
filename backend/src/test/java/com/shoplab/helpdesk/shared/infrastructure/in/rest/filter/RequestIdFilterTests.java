package com.shoplab.helpdesk.shared.infrastructure.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestIdFilterTests {

    private static final String HEADER = "X-Request-Id";

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAnIdWhenTheRequestHasNone() {
        MockServerWebExchange exchange = exchange(null);
        AtomicReference<String> seenInChain = new AtomicReference<>();

        run(exchange, seenInChain);

        String generated = exchange.getResponse().getHeaders().getFirst(HEADER);
        assertThat(generated).isNotBlank();
        assertThat(seenInChain.get()).isEqualTo(generated);
        assertThat(exchange.<String>getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(generated);
    }

    @Test
    void generatedIdsAreUniqueVersion4UuidsWithoutTheSecureRandom() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(RequestIdFilter.newRequestId());
        }

        assertThat(ids).hasSize(1000)
                .allSatisfy(id -> {
                    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
                    assertThat(UUID.fromString(id).version()).isEqualTo(4);
                    assertThat(UUID.fromString(id).variant()).isEqualTo(2);
                });
    }

    @Test
    void keepsAValidIdSentByTheClient() {
        MockServerWebExchange exchange = exchange("client-id-123");
        AtomicReference<String> seenInChain = new AtomicReference<>();

        run(exchange, seenInChain);

        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo("client-id-123");
        assertThat(seenInChain.get()).isEqualTo("client-id-123");
        assertThat(exchange.<String>getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE)).isEqualTo("client-id-123");
    }

    @Test
    void replacesAnIdThatIsTooLong() {
        MockServerWebExchange exchange = exchange("x".repeat(65));

        run(exchange, new AtomicReference<>());

        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER))
                .hasSizeLessThanOrEqualTo(64).isNotEqualTo("x".repeat(65));
    }

    @Test
    void keepsAnIdOfExactlyTheMaximumLength() {
        MockServerWebExchange exchange = exchange("x".repeat(64));

        run(exchange, new AtomicReference<>());

        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo("x".repeat(64));
    }

    @Test
    void replacesAnIdWithUnsafeCharacters() {
        MockServerWebExchange exchange = exchange("bad id/with spaces");

        run(exchange, new AtomicReference<>());

        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER))
                .isNotEqualTo("bad id/with spaces").doesNotContain(" ", "/");
    }

    @Test
    void theIdIsInTheReactorContextOfTheWholeChain() {
        MockServerWebExchange exchange = exchange("ctx-id-1");
        AtomicReference<Object> seenInChain = new AtomicReference<>();
        WebFilterChain chain = e -> Mono.deferContextual(context -> {
            seenInChain.set(context.getOrDefault(RequestIdFilter.MDC_KEY, null));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(seenInChain.get()).isEqualTo("ctx-id-1");
    }

    private void run(MockServerWebExchange exchange, AtomicReference<String> seenInChain) {
        WebFilterChain chain = e -> Mono.deferContextual(context -> {
            seenInChain.set(context.getOrDefault(RequestIdFilter.MDC_KEY, null));
            return Mono.empty();
        });
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    }

    private MockServerWebExchange exchange(String requestId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/probe");
        if (requestId != null) {
            request.header(HEADER, requestId);
        }
        return MockServerWebExchange.from(request);
    }
}
