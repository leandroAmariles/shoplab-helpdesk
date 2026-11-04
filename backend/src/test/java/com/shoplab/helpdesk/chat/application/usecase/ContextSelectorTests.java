package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextSelectorTests {

    private static final int BUDGET = 1000;

    private final ContextSelector selector = new ContextSelector();

    private static ContextFragment fragment(String text, double score) {
        return new ContextFragment("policy.md", "Title of policy.md", "Policy", 0, text, score);
    }

    @Test
    void keepsTheFragmentsInTheOrderReceived() {
        List<ContextFragment> candidates = List.of(fragment("first", 0.9), fragment("second", 0.8),
                fragment("third", 0.7));

        assertThat(selector.select(candidates, 0.0, BUDGET)).containsExactlyElementsOf(candidates);
    }

    @Test
    void anEmptyListOfCandidatesIsAnEmptyContext() {
        assertThat(selector.select(List.of(), 0.0, BUDGET)).isEmpty();
    }

    @Test
    void aThresholdOfZeroKeepsEverythingEvenNegativeScores() {
        List<ContextFragment> candidates = List.of(fragment("weak", -0.2));

        assertThat(selector.select(candidates, 0.0, BUDGET)).containsExactlyElementsOf(candidates);
    }

    @Test
    void dropsTheFragmentsBelowTheThresholdAndKeepsTheOnesAtIt() {
        ContextFragment at = fragment("at the threshold", 0.6);
        ContextFragment above = fragment("above", 0.9);

        assertThat(selector.select(List.of(above, at, fragment("below", 0.59)), 0.6, BUDGET))
                .containsExactly(above, at);
    }

    @Test
    void whenNoFragmentReachesTheThresholdTheContextIsEmpty() {
        assertThat(selector.select(List.of(fragment("a", 0.3), fragment("b", 0.2)), 0.8, BUDGET)).isEmpty();
    }

    @Test
    void repeatedTextIsHandedOverOnceIgnoringCaseAndWhitespace() {
        ContextFragment original = fragment("Seven days   to return.", 0.9);
        ContextFragment copy = new ContextFragment("copy.md", "Title of copy.md", "", 4, "seven days to return.\n", 0.7);

        assertThat(selector.select(List.of(original, fragment("Other text.", 0.8), copy), 0.0, BUDGET))
                .extracting(ContextFragment::text)
                .containsExactly("Seven days   to return.", "Other text.");
    }

    @Test
    void ofTwoCopiesTheBetterScoredIsKept() {
        ContextFragment worse = fragment("same text", 0.5);
        ContextFragment better = new ContextFragment("other.md", "Title of other.md", "", 1, "Same text", 0.9);

        assertThat(selector.select(List.of(worse, better), 0.0, BUDGET)).containsExactly(better);
    }

    @Test
    void addsWholeFragmentsWhileTheyFitAndStopsAtTheFirstThatDoesNot() {
        ContextFragment a = fragment("a".repeat(400), 0.9);
        ContextFragment b = fragment("b".repeat(500), 0.8);
        ContextFragment tooBig = fragment("c".repeat(200), 0.7);
        ContextFragment small = fragment("d", 0.6);

        assertThat(selector.select(List.of(a, b, tooBig, small), 0.0, BUDGET)).containsExactly(a, b);
    }

    @Test
    void aFragmentThatFillsTheBudgetExactlyIsKept() {
        ContextFragment exact = fragment("e".repeat(BUDGET), 0.9);

        assertThat(selector.select(List.of(exact), 0.0, BUDGET)).containsExactly(exact);
    }

    @Test
    void ifTheClosestFragmentAloneExceedsTheBudgetItIsHandedOverUncutAndAlone() {
        ContextFragment huge = fragment("h".repeat(BUDGET + 50), 0.9);

        assertThat(selector.select(List.of(huge, fragment("small", 0.8)), 0.0, BUDGET)).containsExactly(huge);
    }
}
