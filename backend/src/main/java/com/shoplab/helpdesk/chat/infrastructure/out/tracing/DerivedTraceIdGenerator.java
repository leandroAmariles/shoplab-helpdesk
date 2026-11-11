package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import io.opentelemetry.sdk.trace.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Gives the root span of a turn a trace id that anyone can recompute from the request id: the first 32 hexadecimal
 * characters of its SHA-256 (specs/LAB-64, FR-020). The SDK offers no way to choose the id of a span, so the id is
 * prepared in a thread-local right before the root span is started on the same thread; every other id is random.
 * It is not {@code SecureRandom}: that reads {@code /dev/urandom}, a blocking call the event loop must not make.
 */
public class DerivedTraceIdGenerator implements IdGenerator {

    private static final int TRACE_ID_CHARACTERS = 32;

    private final ThreadLocal<String> prepared = new ThreadLocal<>();
    private final IdGenerator random = IdGenerator.random();

    /** The trace id of a request: stable, 32 lower-case hexadecimal characters. */
    public static String traceIdOf(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, TRACE_ID_CHARACTERS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** The next trace id generated on this thread will be this one. */
    public void prepare(String traceId) {
        prepared.set(traceId);
    }

    /** Drops a prepared id that was not used. */
    public void clear() {
        prepared.remove();
    }

    @Override
    public String generateTraceId() {
        String traceId = prepared.get();
        if (traceId == null) {
            return random.generateTraceId();
        }
        prepared.remove();
        return traceId;
    }

    @Override
    public String generateSpanId() {
        return random.generateSpanId();
    }
}
