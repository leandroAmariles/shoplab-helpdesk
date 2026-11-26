package com.shoplab.helpdesk.eval.config;

import com.shoplab.contracts.chat.client.AssistantSettingsApi;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

/** Generated reactive HTTP interface for {@code chat-service} assistant settings (LAB-59). */
@Configuration
@EnableConfigurationProperties(ChatSettingsClientProperties.class)
public class ChatSettingsClientConfig {

    private static final String TRACEPARENT = "traceparent";

    @Bean
    public AssistantSettingsApi chatSettingsApi(ObjectProvider<WebClient.Builder> builderProvider,
            ChatSettingsClientProperties properties) {
        return chatSettingsApi(builderProvider.getIfAvailable(WebClient::builder), properties);
    }

    public AssistantSettingsApi chatSettingsApi(WebClient.Builder builder, ChatSettingsClientProperties properties) {
        WebClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .filter(propagateRequest())
                .build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client)).build()
                .createClient(AssistantSettingsApi.class);
    }

    /** Forwards correlation id and W3C traceparent from the reactive context. */
    public static ExchangeFilterFunction propagateRequest() {
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
