package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text that does not come from us (the customer's message, the questions and answers of the history, the fragments of
 * the documents) is handed to the model as data. This class marks the customer's text with a delimiter and makes
 * inert whatever tries to pass for a delimiter or for a role of the chat: a mitigation, not a guarantee (LAB-22 D-9).
 * The inert form changes {@code <} and {@code >} into {@code ‹} and {@code ›}, so the text stays readable.
 */
final class PromptData {

    static final String OPEN_TAG = "<mensaje_del_cliente>";
    static final String CLOSE_TAG = "</mensaje_del_cliente>";

    private static final String SHORT_DASHES = "---";

    /** Our own delimiters and the role names the chat templates use, open or closed, in any case. */
    private static final Pattern ROLE_TAG = Pattern.compile(
            "<\\s*/?\\s*(?:mensaje_del_cliente|contexto|historial|sistema|system|assistant|user|human)\\s*/?\\s*>",
            Pattern.CASE_INSENSITIVE);

    /** Control tokens of chat templates: {@code <|im_start|>}, {@code [INST]}, {@code <<SYS>>}. */
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile(
            "<\\|[^\\n]*?\\|>|\\[\\s*/?\\s*INST\\s*]|<<\\s*/?\\s*SYS\\s*>>", Pattern.CASE_INSENSITIVE);

    /** A line of five or more dashes is the fence that closes the context block. */
    private static final Pattern DASH_LINE = Pattern.compile("(?m)^[ \\t]*-{5,}[ \\t]*$");

    private PromptData() {
    }

    /** The text with every look-alike delimiter made inert. Idempotent; {@code null} is the empty text. */
    static String neutralize(String text) {
        if (text == null) {
            return "";
        }
        String result = inert(ROLE_TAG, text);
        result = inert(TEMPLATE_TOKEN, result);
        return DASH_LINE.matcher(result).replaceAll(SHORT_DASHES);
    }

    /** The customer's text between the delimiter, neutralized, so that it cannot close the block itself. */
    static String wrap(String text) {
        return OPEN_TAG + "\n" + neutralize(text) + "\n" + CLOSE_TAG;
    }

    private static String inert(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group()
                .replace('<', '‹').replace('>', '›').replace('[', '‹').replace(']', '›')));
    }
}
