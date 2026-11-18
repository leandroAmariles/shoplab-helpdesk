package com.shoplab.helpdesk.chat.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * The prompt-injection attempts of the tests (LAB-114), read from {@code chat/injection-messages.properties}: they are
 * the same twelve as G103-G114 of eval/golden.jsonl, so the dataset and the tests cannot drift apart
 * ({@code GoldenDatasetClassifierTests} compares them). Adding an attempt is a line in that file, not a code change.
 */
public final class InjectionMessages {

    private static final String RESOURCE = "/chat/injection-messages.properties";

    public static final List<String> INJECTIONS = load();

    private InjectionMessages() {
    }

    private static List<String> load() {
        Properties properties = new Properties();
        try (InputStream stream = InjectionMessages.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The test resource " + RESOURCE + " is missing");
            }
            // Properties.load(InputStream) reads ISO-8859-1: the accents of the messages need a UTF-8 reader.
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.stringPropertyNames().stream().sorted().map(properties::getProperty).toList();
    }
}
