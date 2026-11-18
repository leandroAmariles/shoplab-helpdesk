package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.smallTalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.HistorySelection;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** A courtesy turn does not pollute the text the knowledge base is searched with (LAB-114, FR-014). */
class ConversationHistorySearchTextTests {

    private static final String SHIPPING = "¿cuánto tarda un envío?";
    private static final String FOLLOW_UP = "¿y para tarjetas?";

    private ConversationHistory history(boolean smallTalkEnabled, int retrievalTurns) {
        return new ConversationHistory(mock(ConversationMemoryPort.class), new HistoryWindow(), new FollowUpQuery(),
                new MemoryProperties(null, null, retrievalTurns), mock(ChatMetricsPort.class),
                smallTalk(smallTalkEnabled, message -> Flux.empty()));
    }

    private static HistorySelection selection(String... questions) {
        return new HistorySelection(Arrays.stream(questions)
                .map(question -> new ConversationTurn(question, "una respuesta")).toList(), false);
    }

    @Test
    void theSearchUsesTheLastRealQuestionNotTheGreetingAfterIt() {
        String text = history(true, 1).searchText(FOLLOW_UP, selection(SHIPPING, "hola"));

        assertThat(text).isEqualTo(SHIPPING + "\n" + FOLLOW_UP);
    }

    @Test
    void severalCourtesiesInARowAreAllSkipped() {
        String text = history(true, 1).searchText(FOLLOW_UP, selection(SHIPPING, "gracias", "hola", "adiós"));

        assertThat(text).isEqualTo(SHIPPING + "\n" + FOLLOW_UP);
    }

    @Test
    void aMixedQuestionIsNotACourtesyAndIsKept() {
        String mixed = "hola, ¿cuánto tarda un envío?";

        assertThat(history(true, 1).searchText(FOLLOW_UP, selection(mixed))).isEqualTo(mixed + "\n" + FOLLOW_UP);
    }

    @Test
    void aHistoryOfOnlyCourtesiesSearchesWithTheMessageAlone() {
        assertThat(history(true, 1).searchText(SHIPPING, selection("hola", "gracias"))).isEqualTo(SHIPPING);
    }

    @Test
    void withThePathDisabledNothingIsFilteredAsBeforeLab114() {
        String text = history(false, 1).searchText(FOLLOW_UP, selection(SHIPPING, "hola"));

        assertThat(text).isEqualTo("hola\n" + FOLLOW_UP);
    }

    @Test
    void theHistoryTheModelReceivesKeepsTheCourtesyTurns() {
        HistorySelection selection = selection(SHIPPING, "hola");

        history(true, 1).searchText(FOLLOW_UP, selection);

        assertThat(selection.turns()).extracting(ConversationTurn::question).containsExactly(SHIPPING, "hola");
    }
}
