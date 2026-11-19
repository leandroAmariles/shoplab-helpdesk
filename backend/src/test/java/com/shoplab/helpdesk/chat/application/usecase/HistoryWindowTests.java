package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.HistorySelection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HistoryWindowTests {

    private static final int PLENTY = 100_000;

    private final HistoryWindow window = new HistoryWindow();

    private static ConversationTurn turn(int number) {
        return new ConversationTurn("question " + number, "answer " + number);
    }

    private static List<ConversationTurn> turns(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(HistoryWindowTests::turn).toList();
    }

    @Test
    void keepsTheMostRecentTurnsOldestFirst() {
        HistorySelection selection = window.select(turns(4), 3, PLENTY);

        assertThat(selection.turns()).containsExactly(turn(2), turn(3), turn(4));
        assertThat(selection.trimmed()).isTrue();
    }

    @Test
    void whenEverythingFitsNothingIsTrimmed() {
        HistorySelection selection = window.select(turns(3), 3, PLENTY);

        assertThat(selection.turns()).containsExactlyElementsOf(turns(3));
        assertThat(selection.trimmed()).isFalse();
    }

    @Test
    void dropsTheOldestWholeTurnsUntilTheCharactersFit() {
        // each turn is "question n" (10) + "answer n" (8) = 18 characters
        HistorySelection selection = window.select(turns(4), 4, 40);

        assertThat(selection.turns()).containsExactly(turn(3), turn(4));
        assertThat(selection.trimmed()).isTrue();
    }

    @Test
    void neverCutsATextInHalf() {
        ConversationTurn longTurn = new ConversationTurn("q".repeat(300), "a".repeat(300));

        HistorySelection selection = window.select(List.of(turn(1), longTurn), 5, 200);

        assertThat(selection.turns()).isEmpty();
        assertThat(selection.trimmed()).isTrue();
    }

    @Test
    void ifEvenTheLastTurnDoesNotFitTheHistoryIsEmpty() {
        HistorySelection selection = window.select(List.of(new ConversationTurn("q".repeat(150), "a".repeat(150))),
                5, 200);

        assertThat(selection.turns()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Treinta dias [1].            | Treinta dias.
            Si, [1][2] se puede          | Si, se puede
            Plazos [1, 2] y mas [1-3].   | Plazos y mas.
            Hola [opcional] mundo        | Hola [opcional] mundo
            Sin citas                    | Sin citas
            """)
    void theCitationMarksOfThePastAnswersAreRemoved(String answer, String expected) {
        HistorySelection selection = window.select(List.of(new ConversationTurn("q", answer)), 5, PLENTY);

        assertThat(selection.turns()).containsExactly(new ConversationTurn("q", expected));
    }

    @Test
    void anAnswerThatWasOnlyACitationKeepsItsText() {
        HistorySelection selection = window.select(List.of(new ConversationTurn("q", "[1]")), 5, PLENTY);

        assertThat(selection.turns()).containsExactly(new ConversationTurn("q", "[1]"));
    }

    @Test
    void theQuestionsAreNeverChanged() {
        ConversationTurn bracketed = new ConversationTurn("Cuanto cuesta [2024] o [1]?", "Nada");

        HistorySelection selection = window.select(List.of(bracketed), 5, PLENTY);

        assertThat(selection.turns().get(0).question()).isEqualTo("Cuanto cuesta [2024] o [1]?");
    }

    @Test
    void zeroTurnsOrNoHistoryGiveAnEmptySelection() {
        assertThat(window.select(turns(3), 0, PLENTY).turns()).isEmpty();
        assertThat(window.select(List.of(), 5, PLENTY)).isEqualTo(HistorySelection.empty());
    }
}
