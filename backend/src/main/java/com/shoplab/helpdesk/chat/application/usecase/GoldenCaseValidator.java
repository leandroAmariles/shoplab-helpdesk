package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.exception.InvalidGoldenCaseException;
import com.shoplab.helpdesk.chat.domain.model.GoldenCaseDraft;
import com.shoplab.helpdesk.chat.domain.model.constants.FeedbackConstants;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Validates a proposed golden dataset case against the single-case rules of LAB-27 ({@code G-ID}, {@code G-CATEGORY},
 * {@code G-QUESTION-LENGTH}, {@code G-ANSWER-LENGTH}, {@code G-DOC-COUNT}, {@code G-CATEGORY-TOPIC},
 * {@code G-NOANSWER-DIGITS}, {@code G-PII}) and serializes the canonical single-line JSONL entry for
 * {@code eval/golden.jsonl}.
 */
@Component
public class GoldenCaseValidator {

    public String validateAndSerialize(GoldenCaseDraft draft) {
        if (draft == null) {
            throw new InvalidGoldenCaseException("G-ID: el caso golden es obligatorio");
        }
        String caseId = validateCaseId(draft.caseId());
        String category = validateCategory(draft.category());
        String question = validateQuestionLength(draft.question(), category);
        String referenceAnswer = validateAnswerLength(draft.referenceAnswer());
        List<String> expectedDocuments = validateDocuments(draft.expectedDocuments(), category);
        validateNoAnswerDigits(referenceAnswer, expectedDocuments);
        validateNoPersonalData(question, referenceAnswer);
        return serializeJsonl(caseId, question, referenceAnswer, expectedDocuments, category);
    }

    private static String validateCaseId(String rawCaseId) {
        String caseId = rawCaseId == null ? "" : rawCaseId.strip();
        if (!FeedbackConstants.CASE_ID_PATTERN.matcher(caseId).matches()) {
            throw new InvalidGoldenCaseException(
                    "G-ID: el identificador debe tener el formato G seguido de tres dígitos (p. ej. G121)");
        }
        return caseId;
    }

    private static String validateCategory(String rawCategory) {
        String category = rawCategory == null ? "" : rawCategory.strip();
        if (!FeedbackConstants.ALL_CATEGORIES.contains(category)) {
            throw new InvalidGoldenCaseException(
                    "G-CATEGORY: la categoría no pertenece al catálogo permitido de LAB-27");
        }
        return category;
    }

    private static String validateQuestionLength(String rawQuestion, String category) {
        String question = normalizeInlineText(rawQuestion);
        int min = FeedbackConstants.SHORT_QUESTION_CATEGORIES.contains(category)
                ? FeedbackConstants.MIN_CONVERSATION_QUESTION_LENGTH
                : FeedbackConstants.MIN_KNOWLEDGE_QUESTION_LENGTH;
        int max = FeedbackConstants.MAX_QUESTION_LENGTH;
        if (question.length() < min || question.length() > max) {
            throw new InvalidGoldenCaseException(
                    "G-QUESTION-LENGTH: la pregunta debe tener entre " + min + " y " + max + " caracteres");
        }
        return question;
    }

    private static String validateAnswerLength(String rawAnswer) {
        String answer = normalizeInlineText(rawAnswer);
        int min = FeedbackConstants.MIN_ANSWER_LENGTH;
        int max = FeedbackConstants.MAX_ANSWER_LENGTH;
        if (answer.length() < min || answer.length() > max) {
            throw new InvalidGoldenCaseException(
                    "G-ANSWER-LENGTH: la respuesta de referencia debe tener entre "
                            + min + " y " + max + " caracteres");
        }
        return answer;
    }

    private static List<String> validateDocuments(List<String> rawDocuments, String category) {
        List<String> docs = rawDocuments == null
                ? List.of()
                : rawDocuments.stream().map(doc -> doc == null ? "" : doc.strip()).toList();
        if (docs.size() > FeedbackConstants.MAX_EXPECTED_DOCUMENTS) {
            throw new InvalidGoldenCaseException(
                    "G-DOC-COUNT: un caso golden admite como máximo 2 documentos esperados");
        }
        Set<String> seen = new HashSet<>();
        for (String doc : docs) {
            if (!FeedbackConstants.DOCUMENT_ID_PATTERN.matcher(doc).matches() || !seen.add(doc)) {
                throw new InvalidGoldenCaseException(
                        "G-DOC-COUNT: los documentos esperados deben ser identificadores válidos sin extensión ni "
                                + "duplicados");
            }
        }
        if (FeedbackConstants.CONVERSATION_CATEGORIES.contains(category) && !docs.isEmpty()) {
            throw new InvalidGoldenCaseException(
                    "G-CATEGORY-TOPIC: las categorías off_topic, small_talk e injection no admiten documentos "
                            + "esperados");
        }
        if (!docs.isEmpty() && FeedbackConstants.TOPIC_CATEGORIES.contains(category)) {
            String prefix = category + "-";
            boolean allMatch = docs.stream().allMatch(doc -> doc.startsWith(prefix));
            if (!allMatch) {
                throw new InvalidGoldenCaseException(
                        "G-CATEGORY-TOPIC: todos los documentos esperados deben pertenecer al tema de la categoría "
                                + "seleccionada");
            }
        }
        return docs;
    }

    private static void validateNoAnswerDigits(String referenceAnswer, List<String> expectedDocuments) {
        if (expectedDocuments.isEmpty() && FeedbackConstants.DIGITS_PATTERN.matcher(referenceAnswer).find()) {
            throw new InvalidGoldenCaseException(
                    "G-NOANSWER-DIGITS: la respuesta de referencia de un caso sin documentos esperados no puede "
                            + "contener cifras");
        }
    }

    private static void validateNoPersonalData(String question, String referenceAnswer) {
        if (hasPersonalData(question) || hasPersonalData(referenceAnswer)) {
            throw new InvalidGoldenCaseException(
                    "G-PII: el texto no puede contener secuencias de 8 o más dígitos ni correos ajenos a "
                            + FeedbackConstants.ALLOWED_EMAIL_DOMAIN);
        }
    }

    private static boolean hasPersonalData(String text) {
        if (FeedbackConstants.LONG_DIGITS_PATTERN.matcher(text).find()) {
            return true;
        }
        Matcher emailMatcher = FeedbackConstants.EMAIL_PATTERN.matcher(text);
        while (emailMatcher.find()) {
            String email = emailMatcher.group().toLowerCase(Locale.ROOT);
            if (!email.endsWith(FeedbackConstants.ALLOWED_EMAIL_DOMAIN)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeInlineText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static String serializeJsonl(
            String caseId, String question, String referenceAnswer, List<String> expectedDocuments, String category) {
        StringBuilder builder = new StringBuilder(256);
        builder.append("{\"id\": \"").append(escapeJson(caseId)).append("\", ");
        builder.append("\"question\": \"").append(escapeJson(question)).append("\", ");
        builder.append("\"reference_answer\": \"").append(escapeJson(referenceAnswer)).append("\", ");
        builder.append("\"expected_documents\": [");
        for (int index = 0; index < expectedDocuments.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append('"').append(escapeJson(expectedDocuments.get(index))).append('"');
        }
        builder.append("], ");
        builder.append("\"category\": \"").append(escapeJson(category)).append("\"}");
        return builder.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
