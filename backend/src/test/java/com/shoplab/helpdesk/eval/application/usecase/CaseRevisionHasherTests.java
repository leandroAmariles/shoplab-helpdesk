package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class CaseRevisionHasherTests {

    private final CaseRevisionHasher hasher = new CaseRevisionHasher();

    private static EvalCase base() {
        return new EvalCase("G001", null, "payments", "¿Cuántas cuotas puedo usar?", "Hasta doce cuotas sin interés.",
                List.of("payments-01-a", "payments-02-b"));
    }

    private static EvalCase of(String id, String category, String question, String answer, List<String> documents) {
        return new EvalCase(id, null, category, question, answer, documents);
    }

    @Test
    void theHashIsSha256InLowercaseHexadecimalAndDoesNotChangeBetweenCalls() {
        String first = hasher.hash(base());

        assertThat(first).matches("^[0-9a-f]{64}$").isEqualTo(hasher.hash(base()));
    }

    static Stream<EvalCase> changedCases() {
        EvalCase base = base();
        return Stream.of(
                of("G002", base.category(), base.question(), base.referenceAnswer(), base.expectedDocuments()),
                of(base.caseId(), "privacy", base.question(), base.referenceAnswer(), base.expectedDocuments()),
                of(base.caseId(), base.category(), "¿Cuántas cuotas puedo usar hoy?", base.referenceAnswer(),
                        base.expectedDocuments()),
                of(base.caseId(), base.category(), base.question(), "Hasta seis cuotas sin interés.",
                        base.expectedDocuments()),
                of(base.caseId(), base.category(), base.question(), base.referenceAnswer(), List.of("payments-01-a")),
                of(base.caseId(), base.category(), base.question(), base.referenceAnswer(), List.of()));
    }

    @ParameterizedTest
    @MethodSource("changedCases")
    void changingAnyOfTheFiveFieldsChangesTheRevision(EvalCase changed) {
        assertThat(hasher.hash(changed)).isNotEqualTo(hasher.hash(base()));
    }

    @Test
    void theOrderOfTheExpectedDocumentsIsNotAChange() {
        EvalCase reordered = of("G001", "payments", base().question(), base().referenceAnswer(),
                List.of("payments-02-b", "payments-01-a"));

        assertThat(hasher.hash(reordered)).isEqualTo(hasher.hash(base()));
    }

    @Test
    void twoCasesThatOnlyDifferInWhereAFieldEndsAreNotTheSame() {
        EvalCase first = of("G001", "payments", "ab", "c", List.of());
        EvalCase second = of("G001", "payments", "a", "bc", List.of());

        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(second));
    }

    @Test
    void accentsAreHashedAsUtf8AndTheHashFieldItselfIsIgnored() {
        EvalCase accented = of("G001", "payments", "¿Cuántas cuotas?", "Año", List.of());
        EvalCase plain = of("G001", "payments", "¿Cuantas cuotas?", "Ano", List.of());

        assertThat(hasher.hash(accented)).isNotEqualTo(hasher.hash(plain));
        assertThat(hasher.hash(accented.withContentHash("x".repeat(64)))).isEqualTo(hasher.hash(accented));
    }
}
