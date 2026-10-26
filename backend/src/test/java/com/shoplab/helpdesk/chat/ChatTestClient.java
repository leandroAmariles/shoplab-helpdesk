package com.shoplab.helpdesk.chat;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** Calls {@code POST /api/chat} like a real client, keeping the arrival time of every event. */
public final class ChatTestClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };

    private final WebTestClient client;

    public ChatTestClient(int port) {
        this.client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    public Result post(String jsonBody) {
        return post(jsonBody, "text/event-stream", null);
    }

    public Result post(String jsonBody, String accept, String requestId) {
        long start = System.nanoTime();
        WebTestClient.RequestBodySpec request = client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.parseMediaType(accept));
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        WebTestClient.ResponseSpec response = request.bodyValue(jsonBody).exchange();
        MediaType contentType = response.returnResult(String.class).getResponseHeaders().getContentType();
        if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            FluxExchangeResult<ServerSentEvent<String>> result = response.returnResult(SSE);
            List<Event> events = result.getResponseBody()
                    .map(event -> new Event(event.event(), event.data(), System.nanoTime() - start))
                    .collectList().block(Duration.ofSeconds(30));
            return new Result(result.getStatus().value(), result.getResponseHeaders(), events, "");
        }
        FluxExchangeResult<String> result = response.returnResult(String.class);
        String body = result.getResponseBody().collect(Collectors.joining()).block(Duration.ofSeconds(30));
        return new Result(result.getStatus().value(), result.getResponseHeaders(), List.of(), body);
    }

    /** The open event stream, to be consumed (and cancelled) by the caller. */
    public Flux<ServerSentEvent<String>> open(String jsonBody) {
        return client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(jsonBody).exchange()
                .expectStatus().isOk()
                .returnResult(SSE).getResponseBody();
    }

    /** One parsed event; {@code at} is the arrival instant in nanoseconds since the request started. */
    public record Event(String name, String data, long at) {

        /** The text of a {@code token} event, taken from its {@code {"content":"..."}} data. */
        public String content() {
            return data.replaceFirst("^\\{\"content\":\"", "").replaceFirst("\"}$", "")
                    .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
    }

    /** Status, headers and events of one call; {@code body} is set for non-streaming (error) responses. */
    public record Result(int status, HttpHeaders headers, List<Event> events, String body) {

        public String text() {
            return events.stream().filter(e -> "token".equals(e.name())).map(Event::content)
                    .collect(Collectors.joining());
        }

        public Event last() {
            return events.get(events.size() - 1);
        }

        public String header(String name) {
            return headers.getFirst(name);
        }
    }
}
