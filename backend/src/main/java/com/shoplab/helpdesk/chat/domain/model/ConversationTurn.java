package com.shoplab.helpdesk.chat.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One exchange of a conversation: what the customer asked, the whole answer, and optional metadata persisted for
 * feedback and golden-case promotion (delivered document identifiers and Langfuse trace identifier).
 *
 * @param question           the customer's message, as sent
 * @param answer             the complete answer of the assistant, as it was streamed
 * @param deliveredDocuments document identifiers delivered as context in this turn
 * @param traceId            32-hex Langfuse trace identifier, or {@code null}
 */
public record ConversationTurn(String question, String answer, List<String> deliveredDocuments, String traceId) {

    private static final int TRACE_ID_CHARACTERS = 32;

    public ConversationTurn {
        if (question == null || question.isEmpty() || answer == null || answer.isEmpty()) {
            throw new IllegalArgumentException("A conversation turn needs a question and an answer");
        }
        deliveredDocuments = deliveredDocuments == null ? List.of() : List.copyOf(deliveredDocuments);
    }

    public ConversationTurn(String question, String answer) {
        this(question, answer, List.of(), null);
    }

    /** Computes the deterministic 32-hex Langfuse trace id from the request id, or {@code null} when blank. */
    public static String traceIdOf(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, TRACE_ID_CHARACTERS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** Extracts distinct document ids (without {@code .md} or {@code .pdf} extension) in delivery order. */
    public static List<String> documentIdsOf(List<ContextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        return fragments.stream()
                .map(ContextFragment::source)
                .filter(Objects::nonNull)
                .map(ConversationTurn::stripDocumentExtension)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    private static String stripDocumentExtension(String source) {
        String trimmed = source.strip();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String baseName = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        String lower = baseName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) {
            return baseName.substring(0, baseName.length() - 3);
        }
        if (lower.endsWith(".pdf")) {
            return baseName.substring(0, baseName.length() - 4);
        }
        return baseName;
    }
}
