package com.shoplab.helpdesk.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Reads {@code POST /api/chat} like a real client, keeping the arrival time of every event. */
public final class SseTestClient {

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    public SseTestClient(int port) {
        this.port = port;
    }

    public Result post(String jsonBody) {
        return post(jsonBody, "text/event-stream", null);
    }

    public Result post(String jsonBody, String accept, String requestId) {
        HttpRequest.Builder builder = builder(jsonBody, accept).timeout(Duration.ofSeconds(30));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        long start = System.nanoTime();
        try {
            return read(client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()), start);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the chat", e);
        }
    }

    /** Opens the stream and returns the response without reading it, so the caller can close it early. */
    public HttpResponse<InputStream> open(String jsonBody) {
        try {
            return client.send(builder(jsonBody, "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the chat", e);
        }
    }

    private HttpRequest.Builder builder(String jsonBody, String accept) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    }

    private Result read(HttpResponse<InputStream> response, long start) throws IOException {
        List<Event> events = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        String name = null;
        String data = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                raw.append(line).append('\n');
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                } else if (line.isEmpty() && name != null) {
                    events.add(new Event(name, data, System.nanoTime() - start));
                    name = null;
                    data = null;
                }
                line = reader.readLine();
            }
        }
        return new Result(response.statusCode(), response, events, raw.toString());
    }

    /** One parsed event; {@code at} is the arrival instant in nanoseconds since the request started. */
    public record Event(String name, String data, long at) {

        /** The text of a {@code token} event, taken from its {@code {"content":"..."}} data. */
        public String content() {
            return data.replaceFirst("^\\{\"content\":\"", "").replaceFirst("\"}$", "")
                    .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
    }

    /** Status, headers and events of one call. */
    public record Result(int status, HttpResponse<?> response, List<Event> events, String body) {

        public String text() {
            return events.stream().filter(e -> "token".equals(e.name())).map(Event::content)
                    .collect(Collectors.joining());
        }

        public Event last() {
            return events.get(events.size() - 1);
        }

        public String header(String name) {
            return response.headers().firstValue(name).orElse(null);
        }
    }
}
