package com.shoplab.helpdesk.chat.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** The turns of a conversation are stored whole and read newest-first by conversation (specs/LAB-24, FR-001). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcConversationMemoryAdapterTests {

    @Autowired
    private JdbcConversationMemoryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    private final ConversationId first = new ConversationId(UUID.randomUUID().toString());
    private final ConversationId second = new ConversationId(UUID.randomUUID().toString());

    @AfterEach
    void removeTestTurns() {
        jdbc.update("DELETE FROM chat.conversation_turn WHERE conversation_id IN (?, ?)",
                UUID.fromString(first.value()), UUID.fromString(second.value()));
    }

    private void save(ConversationId id, String question, String answer) {
        adapter.saveTurn(id, new ConversationTurn(question, answer)).block();
    }

    @Test
    void theTurnsComeBackInChronologicalOrder() {
        save(first, "one", "uno");
        save(first, "two", "dos");
        save(first, "three", "tres");

        assertThat(adapter.recent(first, 10).block()).containsExactly(
                new ConversationTurn("one", "uno"), new ConversationTurn("two", "dos"),
                new ConversationTurn("three", "tres"));
    }

    @Test
    void onlyTheMostRecentTurnsUpToTheLimitAreRead() {
        save(first, "one", "uno");
        save(first, "two", "dos");
        save(first, "three", "tres");

        assertThat(adapter.recent(first, 2).block()).containsExactly(
                new ConversationTurn("two", "dos"), new ConversationTurn("three", "tres"));
    }

    @Test
    void twoConversationsNeverShareTurns() {
        save(first, "about shipping", "ships");
        save(second, "about returns", "returns");

        assertThat(adapter.recent(first, 10).block()).containsExactly(new ConversationTurn("about shipping", "ships"));
        assertThat(adapter.recent(second, 10).block()).containsExactly(new ConversationTurn("about returns", "returns"));
    }

    @Test
    void aConversationWithoutTurnsIsEmptyNotAnError() {
        assertThat(adapter.recent(first, 5).block()).isEmpty();
    }

    @Test
    void awkwardTextIsStoredAndReadBackIdentical() {
        String question = "¿Cuánto cuesta el 100% de 'envío'? \"ahora\"\n\tÑandú 😀 ; DROP TABLE x; --";
        String answer = "Línea 1\nLínea 2 [1] con %s y ? y \\ barra";
        save(first, question, answer);

        assertThat(adapter.recent(first, 1).block()).containsExactly(new ConversationTurn(question, answer));
    }

    @Test
    void aZeroLimitReadsNothing() {
        save(first, "one", "uno");

        assertThat(adapter.recent(first, 0).block()).isEmpty();
    }

    @Test
    void theCallsDoNotBlockAThreadOfTheEventLoop() {
        // BlockHound is active in the whole suite: subscribing from a non-blocking thread proves the JDBC work is
        // moved to the elastic scheduler by BlockingCalls.
        adapter.saveTurn(first, new ConversationTurn("one", "uno")).subscribeOn(Schedulers.parallel()).block();

        StepVerifier.create(adapter.recent(first, 5).subscribeOn(Schedulers.parallel()))
                .expectNext(List.of(new ConversationTurn("one", "uno")))
                .verifyComplete();
    }

    @Test
    void aDatabaseFailureIsAMemoryExceptionWithoutTheTextOfTheTurn() {
        JdbcConversationMemoryAdapter broken = new JdbcConversationMemoryAdapter(new JdbcTemplate(
                new DriverManagerDataSource("jdbc:postgresql://localhost:1/none", "nobody", "nothing")));

        StepVerifier.create(broken.saveTurn(first, new ConversationTurn("the secret question", "the secret answer")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ConversationMemoryException.class);
                    assertThat(((ConversationMemoryException) error).getOperation()).isEqualTo(MemoryOperation.WRITE);
                    assertThat(((ConversationMemoryException) error).getResponseCode())
                            .isEqualTo(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("secret").doesNotContain(first.value());
                })
                .verify();
        StepVerifier.create(broken.recent(first, 5))
                .expectErrorSatisfies(error -> assertThat(((ConversationMemoryException) error).getOperation())
                        .isEqualTo(MemoryOperation.READ))
                .verify();
    }
}
