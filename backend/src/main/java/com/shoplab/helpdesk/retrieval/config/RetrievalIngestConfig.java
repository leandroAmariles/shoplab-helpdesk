package com.shoplab.helpdesk.retrieval.config;

import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(IngestClientProperties.class)
public class RetrievalIngestConfig {

    /** Client of the ingest service; it forwards the correlation id of the request that triggered the call. */
    @Bean
    public WebClient ingestWebClient(WebClient.Builder builder, IngestClientProperties properties) {
        return builder.baseUrl(properties.baseUrl())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.maxResponseBytes()))
                .filter(propagateRequestId())
                .build();
    }

    private static ExchangeFilterFunction propagateRequestId() {
        return (request, next) -> Mono.deferContextual(context -> {
            ClientRequest outgoing = context.<String>getOrEmpty(RequestIdFilter.MDC_KEY)
                    .map(requestId -> ClientRequest.from(request).header(RequestIdFilter.HEADER, requestId).build())
                    .orElse(request);
            return next.exchange(outgoing);
        });
    }
}
