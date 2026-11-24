package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import com.shoplab.helpdesk.chat.application.port.in.GetAssistantSettingsServicePort;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import io.restassured.module.webtestclient.RestAssuredWebTestClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Base class for Spring Cloud Contract verification of {@code chat} HTTP endpoints. */
public abstract class ChatContractBase {

    @BeforeEach
    void setup() {
        GetAssistantSettingsServicePort settingsPort = mock(GetAssistantSettingsServicePort.class);
        when(settingsPort.current()).thenReturn(Mono.just(new AssistantSettings(
                5,
                0.45,
                6000,
                List.of(
                        new ServedPrompt("chat-system", 3, "LANGFUSE"),
                        new ServedPrompt("chat-small-talk", null, "DEFAULT")))));

        AssistantSettingsController controller = new AssistantSettingsController(settingsPort);
        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
        RestAssuredWebTestClient.webTestClient(client);
    }
}
