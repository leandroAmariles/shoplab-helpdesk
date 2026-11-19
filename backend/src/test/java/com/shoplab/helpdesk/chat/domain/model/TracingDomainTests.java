package com.shoplab.helpdesk.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.domain.model.enums.FailureStage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TracingDomainTests {

    @Test
    void anUnknownUsageIsNeverZero() {
        TokenUsage unknown = TokenUsage.unknown();

        assertThat(unknown.isKnown()).isFalse();
        assertThat(unknown.inputTokens()).isNull();
        assertThat(unknown.outputTokens()).isNull();
        assertThat(unknown.total()).isEmpty();
    }

    @Test
    void aKnownUsageAddsUpItsTotal() {
        TokenUsage usage = new TokenUsage(11, 7);

        assertThat(usage.isKnown()).isTrue();
        assertThat(usage.total()).hasValue(18);
    }

    @Test
    void zeroTokensIsAKnownValueWhenInformed() {
        assertThat(new TokenUsage(0, 0).isKnown()).isTrue();
    }

    @Test
    void aUsageMissingOneSideHasNoTotal() {
        TokenUsage partial = new TokenUsage(5, null);

        assertThat(partial.isKnown()).isFalse();
        assertThat(partial.total()).isEmpty();
    }

    @Test
    void tokensMustNotBeNegative() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenUsage(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundsOfTheToolLoopAreAddedUp() {
        TokenUsage total = new TokenUsage(10, 2).plus(new TokenUsage(30, 8));

        assertThat(total.inputTokens()).isEqualTo(40);
        assertThat(total.outputTokens()).isEqualTo(10);
    }

    @Test
    void addingUnknownKeepsWhatWasInformed() {
        assertThat(TokenUsage.unknown().plus(new TokenUsage(3, 4))).isEqualTo(new TokenUsage(3, 4));
        assertThat(new TokenUsage(3, 4).plus(TokenUsage.unknown())).isEqualTo(new TokenUsage(3, 4));
        assertThat(TokenUsage.unknown().plus(TokenUsage.unknown()).isKnown()).isFalse();
    }

    @Test
    void aPromptMessageHasAKnownRoleAndContent() {
        assertThat(new PromptMessage("system", "rules").role()).isEqualTo("system");
        assertThat(new PromptMessage("user", "hi").content()).isEqualTo("hi");
        assertThat(new PromptMessage("assistant", "hello").role()).isEqualTo("assistant");
        assertThatThrownBy(() -> new PromptMessage("tool", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptMessage("user", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetrievalTraceKeepsAnImmutableCopyOfWhatWasDelivered() {
        List<ContextFragment> delivered = new ArrayList<>();
        delivered.add(new ContextFragment("a.md", "Policy", "Returns", 0, "text", 0.8));

        RetrievalTrace trace = new RetrievalTrace("question", 3, delivered, false);
        delivered.clear();

        assertThat(trace.delivered()).hasSize(1);
        assertThatThrownBy(() -> trace.delivered().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRetrievalTraceNeedsTextAndNonNegativeCandidates() {
        assertThatThrownBy(() -> new RetrievalTrace("q", -1, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalTrace(null, 0, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aToolCallNeedsANameAndKeepsEmptyArgumentsAsEmptyText() {
        ToolCallTrace call = new ToolCallTrace("lookup", null, "ok", false, null);

        assertThat(call.arguments()).isEmpty();
        assertThatThrownBy(() -> new ToolCallTrace(" ", "{}", "ok", false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailureNeedsACodeAndAStage() {
        assertThat(new TurnFailure("CHAT_STREAM_FAILED", FailureStage.GENERATION).stage())
                .isEqualTo(FailureStage.GENERATION);
        assertThatThrownBy(() -> new TurnFailure(null, FailureStage.TOOL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnFailure("X", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
