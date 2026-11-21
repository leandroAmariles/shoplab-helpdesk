package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.eval.config.EvalProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EvalAccessGuardTests {

    private static final String TOKEN = "secret-token-for-tests";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "anything", TOKEN})
    void withoutAConfiguredTokenNothingGetsIn(String presented) {
        EvalAccessGuard guard = new EvalAccessGuard(new EvalProperties(""));

        assertThatThrownBy(() -> guard.check(presented)).isInstanceOf(EvalPersistenceDisabledException.class);
    }

    @Test
    void aBlankConfiguredTokenCountsAsNone() {
        EvalAccessGuard guard = new EvalAccessGuard(new EvalProperties("   "));

        assertThatThrownBy(() -> guard.check("   ")).isInstanceOf(EvalPersistenceDisabledException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "wrong", "secret-token-for-test", "secret-token-for-tests-and-more"})
    void aMissingOrDifferentTokenIsRejected(String presented) {
        EvalAccessGuard guard = new EvalAccessGuard(new EvalProperties(TOKEN));

        assertThatThrownBy(() -> guard.check(presented)).isInstanceOf(EvalPersistenceDisabledException.class);
    }

    @Test
    void theRightTokenPasses() {
        EvalAccessGuard guard = new EvalAccessGuard(new EvalProperties(TOKEN));

        assertThatCode(() -> guard.check(TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void theRejectionNeverShowsAnyOfTheTokens() {
        EvalAccessGuard guard = new EvalAccessGuard(new EvalProperties(TOKEN));

        assertThatThrownBy(() -> guard.check("presented-value")).satisfies(error -> {
            assertThat(String.valueOf(error.getMessage())).doesNotContain(TOKEN).doesNotContain("presented-value");
            assertThat(error.getCause()).isNull();
        });
    }
}
