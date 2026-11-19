package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.SmallTalkModelPort;
import com.shoplab.helpdesk.chat.config.SmallTalkProperties;
import com.shoplab.helpdesk.chat.support.SmallTalkTestSupport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SmallTalkTests {

    private final SmallTalkModelPort model = mock(SmallTalkModelPort.class);

    private SmallTalk smallTalk(boolean enabled) {
        SmallTalkProperties properties = SmallTalkTestSupport.properties(enabled);
        return new SmallTalk(new SmallTalkClassifier(properties), properties, model);
    }

    @Test
    void handlesACourtesyWhenThePathIsEnabled() {
        assertThat(smallTalk(true).handles("hola")).isTrue();
        assertThat(smallTalk(true).isCourtesy("gracias")).isTrue();
    }

    @Test
    void doesNotHandleAQuestionOrAMixedMessage() {
        assertThat(smallTalk(true).handles("¿Cuánto tarda un envío?")).isFalse();
        assertThat(smallTalk(true).handles("hola, ¿cuánto tarda un envío?")).isFalse();
        assertThat(smallTalk(true).isCourtesy("hola, ¿cuánto tarda un envío?")).isFalse();
    }

    @Test
    void withThePathDisabledNothingIsACourtesy() {
        assertThat(smallTalk(false).handles("hola")).isFalse();
        assertThat(smallTalk(false).isCourtesy("hola")).isFalse();
    }

    @Test
    void theReplyIsTheOneOfTheModelUntouched() {
        when(model.streamReply("hola")).thenReturn(Flux.just("¡Ho", "la!"));

        StepVerifier.create(smallTalk(true).reply("hola")).expectNext("¡Ho", "la!").verifyComplete();

        verify(model).streamReply("hola");
    }
}
