package com.shoplab.helpdesk.chat.domain.model.constants;

import java.util.Set;
import java.util.regex.Pattern;

/** Constants and dataset validation rules for answer feedback and golden-case promotion (LAB-27, LAB-33). */
public final class FeedbackConstants {

    public static final int MAX_COMMENT_LENGTH = 500;
    public static final int DEFAULT_LIST_LIMIT = 100;
    public static final int MAX_LIST_LIMIT = 500;
    public static final int DEFAULT_BASE_GOLDEN_CASE_NUMBER = 120;

    public static final int MIN_KNOWLEDGE_QUESTION_LENGTH = 10;
    public static final int MIN_CONVERSATION_QUESTION_LENGTH = 2;
    public static final int MAX_QUESTION_LENGTH = 300;
    public static final int MIN_ANSWER_LENGTH = 20;
    public static final int MAX_ANSWER_LENGTH = 800;
    public static final int MAX_EXPECTED_DOCUMENTS = 2;

    public static final String ALLOWED_EMAIL_DOMAIN = "@shoplab.example";

    public static final Pattern CASE_ID_PATTERN = Pattern.compile("^G[0-9]{3}$");
    public static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    public static final Pattern DIGITS_PATTERN = Pattern.compile("[0-9]");
    public static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
    public static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    public static final Set<String> TOPIC_CATEGORIES =
            Set.of("payments", "privacy", "returns", "shipping", "warranty");
    public static final Set<String> CONVERSATION_CATEGORIES =
            Set.of("off_topic", "small_talk", "injection");
    public static final Set<String> SHORT_QUESTION_CATEGORIES =
            Set.of("small_talk", "injection");
    public static final Set<String> ALL_CATEGORIES = Set.of(
            "payments", "privacy", "returns", "shipping", "warranty", "off_topic", "small_talk", "injection");

    private FeedbackConstants() {
    }
}
