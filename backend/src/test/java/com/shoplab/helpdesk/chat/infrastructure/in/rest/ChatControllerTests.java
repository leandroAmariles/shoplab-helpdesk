package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@WebFluxTest(ChatController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class ChatControllerTests {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private StreamChatServicePort streamChat;

    @Test
    void streamsTokensAndDoneAsServerSentEvents() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new TokenEvent("Hola"), new TokenEvent(" mundo"),
                new DoneEvent(2)));

        FluxExchangeResult<ServerSentEvent<String>> result = chat("{\"message\":\"Hola\"}")
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(SSE);

        StepVerifier.create(result.getResponseBody())
                .expectNextMatches(event -> "token".equals(event.event())
                        && "{\"content\":\"Hola\"}".equals(event.data()))
                .expectNextMatches(event -> "token".equals(event.event())
                        && "{\"content\":\" mundo\"}".equals(event.data()))
                .expectNextMatches(event -> "done".equals(event.event())
                        && "{\"fragments\":2}".equals(event.data()))
                .verifyComplete();
    }

    @Test
    void disablesCachingAndProxyBufferingOfTheStream() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        chat("{\"message\":\"Hola\"}")
                .expectHeader().valueEquals("Cache-Control", "no-cache")
                .expectHeader().valueEquals("X-Accel-Buffering", "no");
    }

    @Test
    void buildsTheCommandWithTheMessageAndTheRequestId() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        webTestClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).header("X-Request-Id", "req-42")
                .bodyValue("{\"message\":\"¿Dónde está mi pedido?\"}").exchange()
                .expectHeader().valueEquals("X-Request-Id", "req-42");

        ArgumentCaptor<StreamChatCommand> captor = ArgumentCaptor.forClass(StreamChatCommand.class);
        verify(streamChat).stream(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new StreamChatCommand("¿Dónde está mi pedido?", "req-42"));
    }

    @Test
    void generatesARequestIdWhenTheClientSendsNone() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        String header = chat("{\"message\":\"Hola\"}").returnResult(SSE).getResponseHeaders()
                .getFirst(RequestIdFilter.HEADER);

        ArgumentCaptor<StreamChatCommand> captor = ArgumentCaptor.forClass(StreamChatCommand.class);
        verify(streamChat).stream(captor.capture());
        assertThat(captor.getValue().requestId()).isNotBlank().isEqualTo(header);
    }

    @Test
    void aBusinessErrorBeforeTheStreamStartsIsABadRequestWithJson() {
        when(streamChat.stream(any())).thenReturn(
                Flux.error(ExceptionUtil.business(ResponseCodeEnum.INVALID_CHAT_MESSAGE)));

        chat("{\"message\":\"   \"}")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00001");
    }

    @Test
    void aFailureBeforeTheFirstEventIsServiceUnavailableEvenForAnEventStreamClient() {
        when(streamChat.stream(any())).thenReturn(Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"))));

        chat("{\"message\":\"Hola\"}")
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00002");
    }

    @Test
    void anUnreadableBodyIsABadRequestWithTheGenericCode() {
        chat("{\"message\":")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_GENE_00002");
    }

    private WebTestClient.ResponseSpec chat(String json) {
        return webTestClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(json).exchange();
    }
}
