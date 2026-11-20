package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.config.PromptProperties;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and publishes prompts through the REST API of Langfuse (contract: {@code langfuse-prompts-api.md}). It never
 * fails: every outcome, including a Langfuse that is down, is a {@link FetchResult}, so the callers decide what each
 * one means. The keys travel only in the authorization header, which no message, log or {@code toString} shows.
 */
public final class LangfusePromptClient {

    private static final String PROMPTS_PATH = "/api/public/v2/prompts";
    private static final String NAME_PATH = PROMPTS_PATH + "/{name}";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String CREATED_BY_COMMIT_MESSAGE = "Publicación inicial desde el repositorio (LAB-85)";

    /** The label Langfuse puts on the newest version of every prompt: asking for it says whether the prompt exists. */
    public static final String LATEST_LABEL = "latest";

    /** What Langfuse said to a read. */
    public enum FetchOutcome {
        /** A version came back. */
        FOUND,
        /** Langfuse answered that the prompt, the label or the version does not exist. */
        MISSING,
        /** No usable answer: Langfuse is down, slow, refused the keys or answered something unreadable. */
        FAILED
    }

    /**
     * The result of a read.
     *
     * @param outcome what happened
     * @param type    {@code text} or {@code chat}; only when found
     * @param text    the text of the version when it is a text prompt; {@code null} otherwise
     * @param version the number of the version; only when found
     * @param detail  a short word that says why it failed ({@code timeout}, {@code connection}, {@code http-503}...);
     *                never a text of Langfuse, an address or a key
     */
    public record FetchResult(FetchOutcome outcome, String type, String text, Integer version, String detail) {

        static FetchResult found(String type, String text, int version) {
            return new FetchResult(FetchOutcome.FOUND, type, text, version, null);
        }

        static FetchResult missing() {
            return new FetchResult(FetchOutcome.MISSING, null, null, null, null);
        }

        static FetchResult failed(String detail) {
            return new FetchResult(FetchOutcome.FAILED, null, null, null, detail);
        }
    }

    /** The part of the answer of Langfuse the backend reads; the text is a string only for {@code text} prompts. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptDto(String name, Integer version, String type, Object prompt) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        // The first reading builds the deserializers; do it here, once, instead of on the first request.
        MAPPER.readValue("{\"version\":1,\"type\":\"text\",\"prompt\":\"\"}", PromptDto.class);
        MAPPER.readValue("{\"version\":1,\"type\":\"chat\",\"prompt\":[{\"role\":\"system\"}]}", PromptDto.class);
    }

    private final WebClient webClient;
    private final Duration requestTimeout;

    public LangfusePromptClient(WebClient.Builder builder, PromptProperties properties) {
        String credentials = properties.publicKey() + ":" + properties.secretKey();
        this.webClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        this.requestTimeout = properties.requestTimeout();
    }

    /** The version of a prompt that carries a label. */
    public Mono<FetchResult> fetchByLabel(String name, String label) {
        return fetch(name, "label", label);
    }

    /** A given version of a prompt. */
    public Mono<FetchResult> fetchByVersion(String name, int version) {
        return fetch(name, "version", version);
    }

    /**
     * Creates a prompt of type text with its first version, labelled {@code label}.
     *
     * @return emits {@code true} when Langfuse created it and {@code false} in any other case (it is not retried here)
     */
    public Mono<Boolean> create(String name, String text, String label) {
        Map<String, Object> body = Map.of("name", name, "type", PromptValidator.TEXT_TYPE, "prompt", text,
                "labels", List.of(label), "commitMessage", CREATED_BY_COMMIT_MESSAGE);
        return webClient.post()
                .uri(PROMPTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().is2xxSuccessful()))
                .timeout(requestTimeout)
                .onErrorReturn(false);
    }

    private Mono<FetchResult> fetch(String name, String parameter, Object value) {
        return webClient.get()
                .uri(builder -> builder.path(NAME_PATH).queryParam(parameter, value).build(name))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.bodyToMono(String.class)
                                .map(LangfusePromptClient::parse)
                                .defaultIfEmpty(FetchResult.failed("empty-body"))
                                .onErrorReturn(FetchResult.failed("unreadable-body"));
                    }
                    Mono<Void> released = response.releaseBody();
                    return released.thenReturn(status == 404 ? FetchResult.missing()
                            : FetchResult.failed("http-" + status));
                })
                .timeout(requestTimeout)
                .onErrorResume(error -> Mono.just(FetchResult.failed(describe(error))));
    }

    /** Reads the answer with a mapper of its own, warmed up when the class loads: no first-use cost on the network. */
    private static FetchResult parse(String body) {
        try {
            return toResult(MAPPER.readValue(body, PromptDto.class));
        } catch (RuntimeException e) {
            return FetchResult.failed("unreadable-body");
        }
    }

    private static FetchResult toResult(PromptDto dto) {
        if (dto.version() == null || dto.version() < 1) {
            return FetchResult.failed("unreadable-body");
        }
        String text = dto.prompt() instanceof String value ? value : null;
        return FetchResult.found(dto.type(), text, dto.version());
    }

    /** A word for the failure, so a warning can say what happened without the address of Langfuse or any text. */
    private static String describe(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException) {
                return "connection";
            }
        }
        return "connection";
    }

    @Override
    public String toString() {
        return "LangfusePromptClient[timeout=" + requestTimeout + "]";
    }
}
