package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

class FollowUpQueryTests {

    private static final int PLENTY = 10_000;

    private final FollowUpQuery followUp = new FollowUpQuery();

    private static ConversationTurn turn(String question) {
        return new ConversationTurn(question, "an answer that is never used");
    }

    @Test
    void withoutHistoryTheQueryIsTheMessage() {
        assertThat(followUp.compose("¿y si es internacional?", List.of(), 1, PLENTY))
                .isEqualTo("¿y si es internacional?");
    }

    @Test
    void withZeroRetrievalTurnsTheQueryIsTheMessage() {
        assertThat(followUp.compose("¿y si es internacional?", List.of(turn("¿cuánto tarda el envío?")), 0, PLENTY))
                .isEqualTo("¿y si es internacional?");
    }

    @Test
    void theLastQuestionGoesBeforeTheMessage() {
        List<ConversationTurn> history = List.of(turn("primera"), turn("¿cuánto tarda el envío?"));

        assertThat(followUp.compose("¿y si es internacional?", history, 1, PLENTY))
                .isEqualTo("¿cuánto tarda el envío?\n¿y si es internacional?");
    }

    @Test
    void twoRetrievalTurnsAddTheLastTwoQuestionsInOrder() {
        List<ConversationTurn> history = List.of(turn("primera"), turn("segunda"), turn("tercera"));

        assertThat(followUp.compose("actual", history, 2, PLENTY)).isEqualTo("segunda\ntercera\nactual");
    }

    @Test
    void moreRetrievalTurnsThanHistoryUseAllTheHistory() {
        assertThat(followUp.compose("actual", List.of(turn("única")), 5, PLENTY)).isEqualTo("única\nactual");
    }

    @Test
    void theOldestQuestionsAreDroppedFirstToFitTheLimit() {
        List<ConversationTurn> history = List.of(turn("a".repeat(30)), turn("b".repeat(30)));

        String query = followUp.compose("actual", history, 2, 45);

        assertThat(query).isEqualTo("b".repeat(30) + "\nactual").hasSizeLessThanOrEqualTo(45);
    }

    @Test
    void theCurrentMessageIsNeverCutEvenIfItAloneExceedsTheLimit() {
        String longMessage = "m".repeat(500);

        assertThat(followUp.compose(longMessage, List.of(turn("previa")), 1, 100)).isEqualTo(longMessage);
    }

    @Test
    void theAnswersOfTheHistoryAreNotUsed() {
        List<ConversationTurn> history = List.of(new ConversationTurn("la pregunta", "LA RESPUESTA SECRETA"));

        assertThat(followUp.compose("actual", history, 1, PLENTY)).doesNotContain("LA RESPUESTA SECRETA");
    }
}
