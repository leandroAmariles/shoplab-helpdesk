package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DerivedTraceIdGeneratorTests {

    private static String sha256Prefix(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 32);
    }

    @Test
    void theTraceIdIsTheFirst32HexCharactersOfTheSha256OfTheRequestId() throws Exception {
        assertThat(DerivedTraceIdGenerator.traceIdOf("demo-0001")).isEqualTo(sha256Prefix("demo-0001"));
        assertThat(DerivedTraceIdGenerator.traceIdOf("demo-0001")).matches("[0-9a-f]{32}");
    }

    @Test
    void theSameRequestAlwaysGivesTheSameTraceAndDifferentRequestsDiffer() {
        assertThat(DerivedTraceIdGenerator.traceIdOf("a")).isEqualTo(DerivedTraceIdGenerator.traceIdOf("a"));
        assertThat(DerivedTraceIdGenerator.traceIdOf("a")).isNotEqualTo(DerivedTraceIdGenerator.traceIdOf("b"));
    }

    @Test
    void thePreparedIdIsGivenOnceAndThenTheGeneratorFallsBackToRandomValidIds() {
        DerivedTraceIdGenerator generator = new DerivedTraceIdGenerator();
        String prepared = DerivedTraceIdGenerator.traceIdOf("req-1");

        generator.prepare(prepared);

        assertThat(generator.generateTraceId()).isEqualTo(prepared);
        String fallback = generator.generateTraceId();
        assertThat(fallback).matches("[0-9a-f]{32}").isNotEqualTo(prepared).isNotEqualTo("0".repeat(32));
    }

    @Test
    void theSpanIdsAreRandomAndValid() {
        DerivedTraceIdGenerator generator = new DerivedTraceIdGenerator();

        assertThat(generator.generateSpanId()).matches("[0-9a-f]{16}").isNotEqualTo("0".repeat(16));
    }

    @Test
    void clearingDropsAPreparedIdThatWasNeverUsed() {
        DerivedTraceIdGenerator generator = new DerivedTraceIdGenerator();
        String prepared = DerivedTraceIdGenerator.traceIdOf("req-1");

        generator.prepare(prepared);
        generator.clear();

        assertThat(generator.generateTraceId()).isNotEqualTo(prepared);
    }

    @Test
    void aPreparedIdBelongsToTheThreadThatPreparedIt() throws Exception {
        DerivedTraceIdGenerator generator = new DerivedTraceIdGenerator();
        String prepared = DerivedTraceIdGenerator.traceIdOf("req-1");
        generator.prepare(prepared);

        String fromAnotherThread = CompletableFuture.supplyAsync(generator::generateTraceId).get();

        assertThat(fromAnotherThread).isNotEqualTo(prepared);
        assertThat(generator.generateTraceId()).isEqualTo(prepared);
    }
}
