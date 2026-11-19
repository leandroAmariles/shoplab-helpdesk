package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class NoOpChatTraceTests {

    private final NoOpChatTrace trace = new NoOpChatTrace();
    private final StreamChatCommand command = new StreamChatCommand("question", "request-1");

    @Test
    void theEventsComeOutUnchangedAndInOrder() {
        Flux<ChatEvent> events = trace.traceTurn(command,
                turn -> Flux.just(new TokenEvent("a"), new TokenEvent("b"), new DoneEvent(2)));

        StepVerifier.create(events)
                .expectNext(new TokenEvent("a"), new TokenEvent("b"), new DoneEvent(2))
                .verifyComplete();
    }

    @Test
    void theTurnReceivesATraceThatIgnoresEverything() {
        AtomicReference<TurnTrace> received = new AtomicReference<>();

        StepVerifier.create(trace.traceTurn(command, turn -> {
            received.set(turn);
            return Flux.empty();
        })).verifyComplete();

        assertThat(received.get()).isSameAs(TurnTrace.NONE);
    }

    @Test
    void theErrorOfTheTurnIsPropagated() {
        IllegalStateException failure = new IllegalStateException("model down");

        StepVerifier.create(trace.traceTurn(command, turn -> Flux.error(failure)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
    }

    @Test
    void theCancellationReachesTheTurn() {
        AtomicBoolean cancelled = new AtomicBoolean();

        StepVerifier.create(trace.traceTurn(command,
                turn -> Flux.<ChatEvent>never().doOnCancel(() -> cancelled.set(true))))
                .thenCancel()
                .verify();

        assertThat(cancelled).isTrue();
    }
}
