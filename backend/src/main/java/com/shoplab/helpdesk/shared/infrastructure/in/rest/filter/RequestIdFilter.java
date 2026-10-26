package com.shoplab.helpdesk.shared.infrastructure.in.rest.filter;

import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));
        exchange.getAttributes().put(REQUEST_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange).contextWrite(context -> context
                .put(MDC_KEY, requestId)
                .put(Slf4jThreadLocalAccessor.KEY, Map.of(MDC_KEY, requestId)));
    }

    private String resolve(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
