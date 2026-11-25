package com.shoplab.helpdesk.chat.config;

import com.shoplab.contracts.retrieval.client.KnowledgeSearchApi;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

/** The generated interface of the search of {@code retrieval-service}, over a {@code WebClient} (LAB-129). */
@Configuration
@ConditionalOnProperty(name = "shoplab.retrieval.client.mode", havingValue = "http")
@EnableConfigurationProperties(RetrievalClientProperties.class)
public class RetrievalClientConfig {

    private static final String TRACEPARENT = "traceparent";

    @Bean
    public KnowledgeSearchApi retrievalSearchApi(WebClient.Builder builder, RetrievalClientProperties properties) {
        WebClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .filter(propagateRequest())
                .build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client)).build()
                .createClient(KnowledgeSearchApi.class);
    }

    /** Forwards the correlation id and the trace of the request that triggered the search. */
    private static ExchangeFilterFunction propagateRequest() {
        return (request, next) -> Mono.deferContextual(context -> {
            ClientRequest.Builder outgoing = ClientRequest.from(request);
            context.<String>getOrEmpty(RequestIdFilter.MDC_KEY)
                    .ifPresent(requestId -> outgoing.header(RequestIdFilter.HEADER, requestId));
            context.<String>getOrEmpty(RequestIdFilter.TRACEPARENT_KEY)
                    .ifPresent(trace -> outgoing.header(TRACEPARENT, trace));
            return next.exchange(outgoing.build());
        });
    }
}
