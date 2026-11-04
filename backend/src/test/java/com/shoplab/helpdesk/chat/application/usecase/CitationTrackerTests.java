package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The vectors are the table of specs/LAB-23-source-citations/contracts/sources-event.md §3, with four sources. */
class CitationTrackerTests {

    private static final int SOURCES = 4;

    private static CitationTracker.Summary summarize(String... tokens) {
        CitationTracker tracker = new CitationTracker();
        for (String token : tokens) {
            tracker.append(token);
        }
        return tracker.summarize(SOURCES);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Plazo [1].|1|0|VALID",
            "Plazo [1][2].|2|0|VALID",
            "Plazo [1, 2].|2|0|VALID",
            "Plazo [1,2].|2|0|VALID",
            "Plazo [1-3].|3|0|VALID",
            "Plazo [ 2 ].|1|0|VALID",
            "Plazo [1] y [1].|2|0|VALID",
            "Plazo [7].|0|1|INVALID",
            "Plazo [1, 9].|1|1|INVALID",
            "Plazo [1] y [9].|1|1|INVALID",
            "Plazo [1-9].|4|5|INVALID",
            "Sin citas.|0|0|NONE",
            "Plazo [0].|0|0|NONE",
            "Plazo [].|0|0|NONE",
            "Plazo [abc].|0|0|NONE",
            "Plazo [1a].|0|0|NONE",
            "Plazo [2024].|0|0|NONE",
            "Plazo (1).|0|0|NONE",
            "Plazo [3-1].|0|0|NONE",
            "Plazo [1-30].|0|0|NONE"})
    void classifiesTheCitationsOfAnAnswer(String text, int valid, int invalid, CitationOutcome outcome) {
        CitationTracker.Summary summary = summarize(text);

        assertThat(summary.valid()).isEqualTo(valid);
        assertThat(summary.invalid()).isEqualTo(invalid);
        assertThat(summary.outcome()).isEqualTo(outcome);
    }

    @Test
    void aMarkerSplitBetweenTokensIsRecognisedOnceItIsComplete() {
        assertThat(summarize("Plazo [", "1", "]", ".").outcome()).isEqualTo(CitationOutcome.VALID);
        assertThat(summarize("Plazo [1", ", 2]").valid()).isEqualTo(2);
        assertThat(summarize("Plazo [1", "-", "3]").valid()).isEqualTo(3);
        assertThat(summarize("Plazo [", "9", "]").outcome()).isEqualTo(CitationOutcome.INVALID);
    }

    @Test
    void anUnfinishedMarkerAtTheEndIsNotACitation() {
        assertThat(summarize("Plazo [1").outcome()).isEqualTo(CitationOutcome.NONE);
        assertThat(summarize("Plazo [").outcome()).isEqualTo(CitationOutcome.NONE);
    }

    @Test
    void withoutSourcesEveryCitationIsInvalid() {
        CitationTracker tracker = new CitationTracker();
        tracker.append("Plazo [1].");

        assertThat(tracker.summarize(0).outcome()).isEqualTo(CitationOutcome.INVALID);
    }

    @Test
    void knowsWhetherItHasSeenAnyText() {
        CitationTracker tracker = new CitationTracker();
        assertThat(tracker.isEmpty()).isTrue();

        tracker.append("");
        assertThat(tracker.isEmpty()).isTrue();

        tracker.append("hola");
        assertThat(tracker.isEmpty()).isFalse();
    }

    @Test
    void theSummaryNeverExposesTheText() {
        assertThat(summarize("secreto del cliente [1]").toString()).doesNotContain("secreto");
    }
}
