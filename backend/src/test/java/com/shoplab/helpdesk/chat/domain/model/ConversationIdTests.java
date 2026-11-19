package com.shoplab.helpdesk.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationIdTests {

    private static final String CANONICAL = "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11";

    @Test
    void anAbsentIdentifierMeansNoMemory() {
        assertThat(ConversationId.optional(null)).isEmpty();
    }

    @Test
    void aCanonicalUuidIsValid() {
        Optional<ConversationId> id = ConversationId.optional(CANONICAL);

        assertThat(id).hasValueSatisfying(value -> assertThat(value.value()).isEqualTo(CANONICAL));
    }

    @Test
    void upperCaseIsNormalizedSoBothSpellingsAreTheSameConversation() {
        assertThat(ConversationId.optional(CANONICAL.toUpperCase())).isEqualTo(ConversationId.optional(CANONICAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "no-es-un-uuid",
        "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e1",
        "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e111",
        "3f1b6c528d0e4c1a9a537b2a0c9d4e11",
        "{3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11}",
        "urn:uuid:3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11",
        " 3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11",
        "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e1g"
    })
    void anythingElseIsRejectedWithoutEchoingTheValue(String raw) {
        assertThatThrownBy(() -> ConversationId.optional(raw))
                .isInstanceOfSatisfying(HelpdeskException.class,
                        error -> assertThat(error.getResponseCode())
                                .isEqualTo(ResponseCodeEnum.INVALID_CONVERSATION_ID))
                .message().doesNotContain(raw.isBlank() ? "\u0000" : raw.strip());
    }
}
