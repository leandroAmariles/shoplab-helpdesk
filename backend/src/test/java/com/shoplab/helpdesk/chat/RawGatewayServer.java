package com.shoplab.helpdesk.chat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * A minimal fake of the model gateway that sends some streaming chunks and then drops the connection without the final
 * chunk, which WireMock cannot do. Used to test failures in the middle of an answer.
 */
public final class RawGatewayServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> lines;
    private final long pauseMillis;

    public RawGatewayServer(List<String> lines, long pauseMillis) {
        this.lines = lines;
        this.pauseMillis = pauseMillis;
        try {
            this.server = new ServerSocket(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofVirtual().name("raw-gateway").start(this::acceptLoop);
    }

    /** The base URL of the OpenAI-compatible API, with the {@code /v1} the client appends its paths to. */
    public String baseUrl() {
        return "http://localhost:" + server.getLocalPort() + "/v1";
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            consumeRequest(in);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String line : lines) {
                byte[] data = ("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8);
                out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(data);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(pauseMillis);
            }
        } catch (IOException e) {
            // the client may hang up first; nothing to report
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads the request line, headers and body so closing the socket later does not reset the connection. */
    private void consumeRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            head.write(b);
        }
        String headers = head.toString(StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int index = headers.indexOf("content-length:");
        if (index >= 0) {
            int end = headers.indexOf("\r\n", index);
            int length = Integer.parseInt(headers.substring(index + "content-length:".length(), end).trim());
            in.readNBytes(length);
        } else if (headers.contains("transfer-encoding: chunked")) {
            ByteArrayOutputStream tail = new ByteArrayOutputStream();
            while (!tail.toString(StandardCharsets.ISO_8859_1).endsWith("0\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                tail.write(b);
            }
        }
    }
}
