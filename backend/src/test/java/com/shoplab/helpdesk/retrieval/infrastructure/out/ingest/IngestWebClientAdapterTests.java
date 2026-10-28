package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import com.shoplab.helpdesk.support.PostgresTest;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/** The adapter against a simulated ingest service (its contract, ingest/openapi.json, is checked elsewhere). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class IngestWebClientAdapterTests {

    private static final byte[] CONTENT = "# Politica\n\nTexto.".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ingestUrl(DynamicPropertyRegistry registry) {
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
        registry.add("shoplab.retrieval.ingest.timeout", () -> "1s");
    }

    @Autowired
    private IngestWebClientAdapter adapter;

    @BeforeEach
    void resetStubs() {
        ingest.resetAll();
    }

    private static String answer(int chunks) {
        String items = IntStream.range(0, chunks)
                .mapToObj(index -> "{\"text\":\"Texto del chunk " + index + " con acentos: año, niños.\","
                        + "\"metadata\":{\"document\":\"politica.md\",\"section\":\"Politica > Plazos\","
                        + "\"position\":" + index + "}}")
                .collect(Collectors.joining(","));
        return "{\"document\":{\"name\":\"politica.md\",\"title\":\"Politica\",\"format\":\"markdown\"},"
                + "\"chunks\":[" + items + "]}";
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Test
    void sendsTheDocumentAsAMultipartFileNamedAfterIt() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, answer(1))));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectNextCount(1).verifyComplete();

        ingest.verify(postRequestedFor(urlEqualTo("/ingest"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart().withName("file")
                        .withHeader("Content-Disposition", containing("filename=\"politica.md\""))
                        .withBody(equalTo("# Politica\n\nTexto.")).build()));
    }

    @Test
    void turnsTheAnswerIntoAParsedDocument() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, answer(3))));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).assertNext((ParsedDocument parsed) -> {
            assertThat(parsed.name()).isEqualTo("politica.md");
            assertThat(parsed.title()).isEqualTo("Politica");
            assertThat(parsed.format()).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(parsed.chunks()).hasSize(3);
            assertThat(parsed.chunks().get(2).position()).isEqualTo(2);
            assertThat(parsed.chunks().get(2).text()).contains("año, niños");
            assertThat(parsed.chunks().get(2).section()).isEqualTo("Politica > Plazos");
        }).verifyComplete();
    }

    @Test
    void anAnswerBiggerThanTheDefaultCodecBufferIsRead() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, answer(3000))));

        StepVerifier.create(adapter.parse("politica.md", CONTENT))
                .assertNext(parsed -> assertThat(parsed.chunks()).hasSize(3000))
                .verifyComplete();
    }

    @Test
    void forwardsTheCorrelationIdOfTheRequest() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, answer(1))));

        StepVerifier.create(adapter.parse("politica.md", CONTENT).contextWrite(context -> context.put("requestId",
                "req-123"))).expectNextCount(1).verifyComplete();

        ingest.verify(postRequestedFor(urlEqualTo("/ingest")).withHeader("X-Request-Id", equalTo("req-123")));
    }

    @ParameterizedTest
    @CsvSource({
        "415, UNSUPPORTED_FORMAT, DOCUMENT_UNSUPPORTED_FORMAT",
        "413, DOCUMENT_TOO_LARGE, DOCUMENT_TOO_LARGE",
        "422, EMPTY_DOCUMENT, DOCUMENT_INVALID",
        "422, NO_EXTRACTABLE_TEXT, DOCUMENT_INVALID",
        "422, INVALID_DOCUMENT, DOCUMENT_INVALID",
        "422, INVALID_CHUNKING_CONFIG, DOCUMENT_INVALID",
        "400, INVALID_DOCUMENT, DOCUMENT_INVALID"
    })
    void aDocumentTheServiceRejectsIsABusinessError(int status, String code, ResponseCodeEnum expected) {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(
                json(status, "{\"code\":\"" + code + "\",\"message\":\"no\"}")));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(BusinessException.class);
            assertThat(((HelpdeskException) error).getResponseCode()).isEqualTo(expected);
        }).verify();
    }

    @Test
    void aRejectionWithoutReadableBodyIsStillABusinessError() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(422).withBody("<html>")));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error ->
                assertThat(((HelpdeskException) error).getResponseCode()).isEqualTo(ResponseCodeEnum.DOCUMENT_INVALID))
                .verify();
    }

    @Test
    void aServerErrorIsATechnicalFailureOfTheService() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(
                json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Unexpected error\"}")));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TechnicalException.class);
            assertThat(((HelpdeskException) error).getResponseCode())
                    .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_FAILED);
        }).verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"document\":{\"name\":\"p.md\",\"title\":\"P\",\"format\":\"markdown\"},\"chunks\":[]}",
        "{\"document\":{\"name\":\"p.md\",\"title\":\"P\",\"format\":\"docx\"},\"chunks\":[{\"text\":\"a\","
                + "\"metadata\":{\"document\":\"p.md\",\"section\":\"\",\"position\":0}}]}",
        "{\"document\":{\"name\":\"p.md\",\"title\":\"P\",\"format\":\"pdf\"},\"chunks\":[{\"text\":\"a\","
                + "\"metadata\":{\"document\":\"p.md\",\"section\":\"\",\"position\":5}}]}",
        "{\"document\":{\"name\":\"p.md\",\"title\":\"P\",\"format\":\"pdf\"},\"chunks\":[{\"text\":\"  \","
                + "\"metadata\":{\"document\":\"p.md\",\"section\":\"\",\"position\":0}}]}",
        "not json at all"
    })
    void anAnswerThatBreaksTheContractIsAFailureOfTheService(String body) {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, body)));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TechnicalException.class);
            assertThat(((HelpdeskException) error).getResponseCode())
                    .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_FAILED);
        }).verify();
    }

    @Test
    void anEmptyAnswerIsAFailureOfTheService() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error ->
                assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_FAILED)).verify();
    }

    @Test
    void aConnectionThatIsCutIsAnUnavailableService() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TechnicalException.class);
            assertThat(((HelpdeskException) error).getResponseCode())
                    .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE);
        }).verify();
    }

    @Test
    void noAnswerInTimeIsAnUnavailableService() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(json(200, answer(1)).withFixedDelay(2500)));

        StepVerifier.create(adapter.parse("politica.md", CONTENT)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TechnicalException.class);
            assertThat(((HelpdeskException) error).getResponseCode())
                    .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE);
        }).verify();
    }
}
