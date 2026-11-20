package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.exception.PromptRenderException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the variables of a prompt template: every {@code {name}} is replaced by its value. It is the only place that
 * renders a template, so what the validator accepts is exactly what the adapter can render.
 *
 * <p>The replacement is done in one pass, so a value that contains {@code {query}} stays as written (a customer cannot
 * make a variable of the template appear through the text of a fragment). Braces that do not make a variable name, such
 * as {@code {"a": 1}} or {@code { }}, are literal text.
 */
public final class PromptRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private PromptRenderer() {
    }

    /**
     * @throws PromptRenderException when the template names a variable that has no value
     */
    public static String render(String template, Map<String, String> values) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new PromptRenderException(matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
