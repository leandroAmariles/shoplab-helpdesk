package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.eval.config.EvalHistoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceUrlBuilderTests {

    private static final String TEMPLATE = "http://localhost:3001/trace/{traceId}";
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static TraceUrlBuilder builder(String template) {
        return new TraceUrlBuilder(new EvalHistoryProperties(template));
    }

    @Test
    void theIdOfTheTraceGoesWhereTheTemplateSaysAndNowhereElse() {
        assertThat(builder(TEMPLATE).urlFor(TRACE))
                .contains("http://localhost:3001/trace/4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(builder("https://langfuse.example/project/p1/traces/{traceId}?peek=1").urlFor("abc_DEF-1"))
                .contains("https://langfuse.example/project/p1/traces/abc_DEF-1?peek=1");
    }

    @Test
    void withoutATemplateThereIsNoLink() {
        assertThat(builder("").urlFor(TRACE)).isEmpty();
        assertThat(builder(null).urlFor(TRACE)).isEmpty();
        assertThat(builder("   ").urlFor(TRACE)).isEmpty();
    }

    @Test
    void aMissingOrOddTraceIdGivesNoLink() {
        TraceUrlBuilder builder = builder(TEMPLATE);

        assertThat(builder.urlFor(null)).isEmpty();
        assertThat(builder.urlFor("")).isEmpty();
        assertThat(builder.urlFor("../admin")).isEmpty();
        assertThat(builder.urlFor("a b")).isEmpty();
        assertThat(builder.urlFor("x".repeat(65))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3001/trace/", "http://localhost/{traceId}/{traceId}",
            "ftp://localhost/{traceId}", "javascript:alert({traceId})", "localhost:3001/{traceId}",
            "http://local host/{traceId}", "/trace/{traceId}"})
    void aTemplateThatIsNotAnHttpAddressWithOneIdFailsAtStartWithoutShowingItsValue(String template) {
        assertThatThrownBy(() -> builder(template)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shoplab.eval.history.trace-url-template")
                .hasMessageNotContaining(template);
    }
}
