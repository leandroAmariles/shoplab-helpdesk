package com.shoplab.helpdesk.shared.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Copies the {@code requestId} that the request filter puts in the Reactor context into the MDC of whichever thread
 * runs each operator, so every log line of a request carries its correlation id (needs
 * {@code spring.reactor.context-propagation=auto}).
 */
@Configuration
public class ReactorContextConfig {

    private static final String REQUEST_ID_KEY = "requestId";

    @PostConstruct
    public void registerMdcAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor(REQUEST_ID_KEY));
    }
}
