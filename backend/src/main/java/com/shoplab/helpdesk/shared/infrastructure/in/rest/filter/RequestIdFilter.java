package com.shoplab.helpdesk.shared.infrastructure.in.rest.filter;

import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Correlation id of each request: taken from {@code X-Request-Id} when it is safe, generated otherwise. It goes to
 * an exchange attribute, to the response and to the Reactor context, which carries it to the MDC of whichever thread
 * runs each operator (a reactive chain hops threads, so a plain MDC would be lost).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE = "requestId";
    /** Reactor context key of the incoming {@code traceparent}, for the calls to other services (LAB-129). */
    public static final String TRACEPARENT_KEY = "traceparent";

    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final long UUID_VERSION_MASK = 0xF000L;
    private static final long UUID_VERSION_4 = 0x4000L;
    private static final long UUID_VARIANT_MASK = 0xC000000000000000L;
    private static final long UUID_VARIANT_RFC_4122 = 0x8000000000000000L;

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));
        exchange.getAttributes().put(REQUEST_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        String trace = exchange.getRequest().getHeaders().getFirst("traceparent");
        return chain.filter(exchange).contextWrite(context -> {
            var enriched = context
                    .put(MDC_KEY, requestId)
                    .put(Slf4jThreadLocalAccessor.KEY, Map.of(MDC_KEY, requestId));
            return trace != null && TRACEPARENT.matcher(trace).matches()
                    ? enriched.put(TRACEPARENT_KEY, trace) : enriched;
        });
    }

    private String resolve(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return newRequestId();
    }

    /**
     * A fresh UUID-shaped id. It is not {@code UUID.randomUUID()}: that reads {@code /dev/urandom} on Linux, a blocking
     * call that must not run on the event loop (BlockHound flags it). A correlation id does not need to be unguessable.
     */
    public static String newRequestId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificant = (random.nextLong() & ~UUID_VERSION_MASK) | UUID_VERSION_4;
        long leastSignificant = (random.nextLong() & ~UUID_VARIANT_MASK) | UUID_VARIANT_RFC_4122;
        return new UUID(mostSignificant, leastSignificant).toString();
    }
}
