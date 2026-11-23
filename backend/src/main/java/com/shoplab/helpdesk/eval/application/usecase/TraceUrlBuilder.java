package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.config.EvalHistoryProperties;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds the address of a Langfuse trace for the screen from a template of the environment (specs/LAB-32, research
 * R6). The template is checked when the application starts. An id that does not look like a trace id gets no link, so
 * nothing can be added to the path of the address.
 */
@Component
public class TraceUrlBuilder {

    private static final String PLACEHOLDER = "{traceId}";
    private static final String PROPERTY = "shoplab.eval.history.trace-url-template";

    private final String template;

    public TraceUrlBuilder(EvalHistoryProperties properties) {
        this.template = check(properties.traceUrlTemplate());
    }

    /** The link to the trace, or empty when there is no template or the id is not a trace id. */
    public Optional<String> urlFor(String traceId) {
        if (template.isEmpty() || traceId == null || !EvalConstants.TRACE_ID.matcher(traceId).matches()) {
            return Optional.empty();
        }
        return Optional.of(template.replace(PLACEHOLDER, traceId));
    }

    private static String check(String template) {
        if (template.isEmpty()) {
            return template;
        }
        int first = template.indexOf(PLACEHOLDER);
        boolean onePlaceholder = first >= 0 && template.indexOf(PLACEHOLDER, first + 1) < 0;
        if (!onePlaceholder || !isHttpAddress(template.replace(PLACEHOLDER, "trace"))) {
            throw new IllegalStateException(PROPERTY + " must be an http(s) address with {traceId} exactly once");
        }
        return template;
    }

    private static boolean isHttpAddress(String address) {
        try {
            URI uri = new URI(address);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
