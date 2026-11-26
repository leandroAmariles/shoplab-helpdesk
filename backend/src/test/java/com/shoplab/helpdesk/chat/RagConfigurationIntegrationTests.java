package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.support.StubKnowledgeContext;
import com.shoplab.helpdesk.chat.support.StubKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The number of fragments that reach the model is the configured top-k, not a number in the code (US3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shoplab.chat.rag.top-k=2", "shoplab.chat.rag.similarity-threshold=0.0"})
@PostgresTest
@Import(StubKnowledgeContextConfig.class)
class RagConfigurationIntegrationTests {

    private static final String SOURCE = "rag-topk-test-policy.md";

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private StubKnowledgeContext knowledge;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("ok")));
    }

    @Test
    void withFiveCandidatesAndATopKOfTwoExactlyTwoFragmentsReachTheModelAndTheClosestIsOneOfThem() throws Exception {
        knowledge.answers(fragment("C", "tres"), fragment("A", "uno"), fragment("B", "dos"),
                fragment("D", "cuatro"), fragment("E", "cinco"));

        ChatTestClient.Result result = new ChatTestClient(port).post("{\"message\":\"Texto tres.\"}");

        assertThat(result.status()).isEqualTo(200);
        String requestBody = ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))).get(0)
                .getBodyAsString();
        Matcher fragments = Pattern.compile("\\[\\d+] Politica › [A-E]\\b").matcher(requestBody);
        int count = 0;
        while (fragments.find()) {
            count++;
        }
        assertThat(count).isEqualTo(2);
        assertThat(requestBody).contains("Texto tres.");
    }

    private static ContextFragment fragment(String section, String number) {
        return new ContextFragment(SOURCE, "Politica", section, 0, "Texto " + number + ".", 0.9);
    }
}
