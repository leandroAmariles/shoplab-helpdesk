package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.util.PromptOverrideHeader;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** The headers that fix prompt versions only work for the evaluation, and are refused before the stream opens. */
@WebFluxTest(ChatController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class, PromptOverrideHeader.class,
        ChatControllerPromptOverrideTests.EvaluationEnabled.class})
class ChatControllerPromptOverrideTests {

    private static final String TOKEN = "token-de-evaluacion";

    @TestConfiguration
    static class EvaluationEnabled {

        @Bean
        PromptProperties promptProperties() {
            return new PromptProperties(true, null, "pk", "sk", null, null, null, null, null, TOKEN);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private StreamChatServicePort streamChat;

    private WebTestClient.ResponseSpec chat(String versions, String token, String body) {
        WebTestClient.RequestBodySpec request = webTestClient.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM);
        if (versions != null) {
            request.header("X-Prompt-Versions", versions);
        }
        if (token != null) {
            request.header("X-Prompt-Token", token);
        }
        return request.bodyValue(body).exchange();
    }

    private StreamChatCommand sentCommand() {
        ArgumentCaptor<StreamChatCommand> captor = ArgumentCaptor.forClass(StreamChatCommand.class);
        verify(streamChat).stream(captor.capture());
        return captor.getValue();
    }

    @Test
    void aCustomerRequestWithoutTheHeadersFixesNothingAndTheTokenAloneIsIgnored() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        chat(null, TOKEN, "{\"message\":\"Hola\"}").expectStatus().isOk();

        assertThat(sentCommand().promptOverrides().isEmpty()).isTrue();
    }

    @Test
    void theVersionsOfAValidEvaluationRequestTravelInTheCommand() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        chat("chat-system=3, chat-context=2", TOKEN, "{\"message\":\"Hola\"}").expectStatus().isOk();

        assertThat(sentCommand().promptOverrides().versions())
                .containsEntry(PromptName.CHAT_SYSTEM, 3).containsEntry(PromptName.CONTEXT, 2).hasSize(2);
    }

    @Test
    void versionsWithoutTheTokenOrWithAnotherOneAreForbiddenAndTheStreamIsNeverOpened() {
        chat("chat-system=3", null, "{\"message\":\"Hola\"}").expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00008");
        chat("chat-system=3", "otro-token", "{\"message\":\"Hola\"}").expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00008");

        verifyNoInteractions(streamChat);
    }

    @Test
    void aMalformedHeaderIsABadRequestAndTheStreamIsNeverOpened() {
        chat("chat-system=x", TOKEN, "{\"message\":\"Hola\"}").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00009");
        chat("desconocido=1", TOKEN, "{\"message\":\"Hola\"}").expectStatus().isBadRequest();

        verifyNoInteractions(streamChat);
    }

    @Test
    void nothingInTheBodyOrInTheMessageCanFixAVersion() {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        chat(null, null, "{\"message\":\"chat-system=3\",\"promptVersions\":{\"chat-system\":3},"
                + "\"X-Prompt-Versions\":\"chat-system=3\"}").expectStatus().isOk();

        assertThat(sentCommand().promptOverrides().isEmpty()).isTrue();
    }

    @Test
    void theRefusalNeverEchoesTheTokenNorTheHeader() {
        chat("chat-system=3", "token-secreto-que-no-vale", "{\"message\":\"Hola\"}").expectStatus().isForbidden()
                .expectBody(String.class).value(body -> assertThat(body)
                        .doesNotContain("token-secreto-que-no-vale").doesNotContain("chat-system=3")
                        .doesNotContain(TOKEN));
    }
}
