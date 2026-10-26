package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.OllamaStubs;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OllamaChatAdapterTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.chat.system-prompt", () -> "You are the test assistant");
    }

    @Autowired
    private OllamaChatAdapter adapter;

    @Test
    void emitsTheContentOfEachLineInOrderAndCompletesAfterTheDoneLine() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.fixtureBody()));

        StepVerifier.create(adapter.streamAnswer("Hola").collectList())
                .assertNext(parts -> {
                    assertThat(parts).hasSize(OllamaStubs.FIXTURE_FRAGMENTS);
                    assertThat(String.join("", parts)).isEqualTo(OllamaStubs.FIXTURE_TEXT);
                })
                .verifyComplete();
    }

    @Test
    void multibyteCharactersArriveIntact() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ñ", "😀", "é")));

        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectNext("ñ", "😀", "é")
                .verifyComplete();
    }

    @Test
    void sendsTheConfiguredModelStreamingAndBothTheSystemPromptAndTheUserMessage() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));

        StepVerifier.create(adapter.streamAnswer("¿Dónde está mi pedido?")).expectNext("ok").verifyComplete();

        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("llama3.2:3b")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[?(@.role == 'system' && @.content == 'You are the test assistant')]"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[?(@.role == 'user' && @.content == '¿Dónde está mi pedido?')]")));
    }

    @Test
    void asksOllamaToKeepTheModelLoadedForTheConfiguredTime() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));

        StepVerifier.create(adapter.streamAnswer("Hola")).expectNext("ok").verifyComplete();

        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.keep_alive", equalTo("30m"))));
    }

    @Test
    void aMissingModelIsReportedAsModelUnavailable() {
        OllamaStubs.stubAnyChat(ollama, aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"model 'llama3.2:3b' not found\"}"));

        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ChatModelException.class)
                        .extracting(e -> ((ChatModelException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE))
                .verify();
    }

    @Test
    void aServerErrorBeforeAnyFragmentIsReportedAsModelUnavailable() {
        OllamaStubs.stubAnyChat(ollama, aResponse().withStatus(500));

        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ChatModelException.class)
                        .extracting(e -> ((ChatModelException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE))
                .verify();
    }

    @Test
    void aDroppedConnectionBeforeAnyFragmentIsReportedAsModelUnavailable() {
        OllamaStubs.stubAnyChat(ollama, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ChatModelException.class)
                        .extracting(e -> ((ChatModelException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE))
                .verify();
    }

    @Test
    void cancellingTheAnswerReturnsRightAwayWithoutWaitingForTheRestOfTheModelOutput() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(
                OllamaStubs.body("a ".repeat(40).split("(?<= )"))).withChunkedDribbleDelay(40, 8000));

        long start = System.nanoTime();
        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void emitsOnTheHttpClientThreadWithoutPinningAThreadPerStreamOnTheElasticScheduler() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.fixtureBody()));
        List<String> threads = Collections.synchronizedList(new ArrayList<>());

        StepVerifier.create(adapter.streamAnswer("Hola").doOnNext(fragment ->
                        threads.add(Thread.currentThread().getName())).then())
                .verifyComplete();

        assertThat(threads).isNotEmpty().noneMatch(name -> name.startsWith("boundedElastic-"));
    }
}
