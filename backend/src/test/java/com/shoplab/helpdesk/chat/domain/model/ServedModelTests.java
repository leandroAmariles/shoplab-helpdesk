package com.shoplab.helpdesk.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServedModelTests {

    @Test
    void theConfiguredPrimaryModelIsNotAFallback() {
        assertThat(ServedModel.of("llama3.2:3b", "llama3.2:3b")).isEqualTo(new ServedModel("llama3.2:3b", false));
    }

    @Test
    void anotherModelThanThePrimaryOneIsAFallback() {
        assertThat(ServedModel.of("llama3.2:1b", "llama3.2:3b")).isEqualTo(new ServedModel("llama3.2:1b", true));
    }

    @Test
    void withoutAKnownPrimaryModelNothingIsEverMarkedAsFallback() {
        assertThat(ServedModel.of("llama3.2:1b", "").fallback()).isFalse();
        assertThat(ServedModel.of("llama3.2:1b", null).fallback()).isFalse();
    }

    @Test
    void aResponseThatDoesNotSayWhichModelAnsweredIsUnknownAndNotAFallback() {
        assertThat(ServedModel.of(null, "llama3.2:3b")).isEqualTo(ServedModel.unknown());
        assertThat(ServedModel.of("  ", "llama3.2:3b").name()).isEqualTo(ServedModel.UNKNOWN);
        assertThat(ServedModel.of("", "llama3.2:3b").fallback()).isFalse();
    }
}
