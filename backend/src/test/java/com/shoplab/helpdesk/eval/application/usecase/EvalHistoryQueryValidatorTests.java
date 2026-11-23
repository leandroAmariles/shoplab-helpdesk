package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.eval.application.port.in.CompareEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EvalHistoryQueryValidatorTests {

    private final EvalHistoryQueryValidator validator = new EvalHistoryQueryValidator();

    @Test
    void aListWithOnlyTheKindUsesTheDefaultLimitAndAllOrigins() {
        EvalHistoryQueryValidator.ListQuery query = validator.list(new ListEvalRunsCommand("QUALITY", null, null));

        assertThat(query.kind()).isEqualTo(RunKind.QUALITY);
        assertThat(query.origin()).isNull();
        assertThat(query.limit()).isEqualTo(100);
    }

    @Test
    void aBlankOriginMeansAllOrigins() {
        assertThat(validator.list(new ListEvalRunsCommand("RETRIEVAL", "  ", "5")).origin()).isNull();
        assertThat(validator.list(new ListEvalRunsCommand("RETRIEVAL", "dev", "5")).origin()).isEqualTo("dev");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100", "500"})
    void theLimitIsAcceptedFromOneToFiveHundred(String limit) {
        assertThat(validator.list(new ListEvalRunsCommand("QUALITY", null, limit)).limit())
                .isEqualTo(Integer.parseInt(limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "501", "ten", "1.5", "99999999999"})
    void aLimitOutsideTheRangeNamesTheParameterWithoutRepeatingTheValue(String limit) {
        assertThatThrownBy(() -> validator.list(new ListEvalRunsCommand("QUALITY", null, limit)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).isEqualTo("limit: must be a whole number from 1 to 500"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"quality", "OTHER", "QUALITY "})
    void theKindIsRequiredAndKnown(String kind) {
        assertThatThrownBy(() -> validator.list(new ListEvalRunsCommand(kind, null, null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).startsWith("kind"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"con espacio", "a/b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void anOriginThatDoesNotFitIsInvalid(String origin) {
        assertThatThrownBy(() -> validator.list(new ListEvalRunsCommand("QUALITY", origin, null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).startsWith("origin").doesNotContain(origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"t031-1", "a.b_c-9", "x"})
    void aRunIdThatFitsIsAccepted(String runId) {
        assertThat(validator.runId(runId)).isEqualTo(runId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"con espacio", "a/b", "ñandú", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void aRunIdThatDoesNotFitIsInvalid(String runId) {
        assertThatThrownBy(() -> validator.runId(runId)).isInstanceOfSatisfying(EvalRunInvalidException.class,
                e -> assertThat(e.publicDetail()).startsWith("run_id"));
    }

    @Test
    void aComparisonWithoutADropUsesFiveHundredthsAndKeepsTheMetric() {
        EvalHistoryQueryValidator.CompareQuery query = validator
                .compare(new CompareEvalRunsCommand("t031-1", "t031-2", "faithfulness", null));

        assertThat(query.base()).isEqualTo("t031-1");
        assertThat(query.target()).isEqualTo("t031-2");
        assertThat(query.metric()).isEqualTo("faithfulness");
        assertThat(query.drop()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "1", "0.5", "0.05", "1.0"})
    void aDropFromZeroToOneIsAcceptedExactly(String drop) {
        assertThat(validator.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", drop)).drop())
                .isEqualByComparingTo(new BigDecimal(drop));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "NaN", "Infinity", "abc", "1e400", "0,5"})
    void aDropOutsideZeroToOneOrNotANumberIsInvalid(String drop) {
        assertThatThrownBy(() -> validator.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", drop)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).isEqualTo("drop: must be a number from 0 to 1"));
    }

    @Test
    void aRunCannotBeComparedWithItself() {
        assertThatThrownBy(() -> validator.compare(new CompareEvalRunsCommand("a", "a", "faithfulness", null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).contains("base", "target"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Faithfulness", "1abc", "has space", "hit-at-3"})
    void theMetricIsRequiredAndNamedLikeTheSavedOnes(String metric) {
        assertThatThrownBy(() -> validator.compare(new CompareEvalRunsCommand("a", "b", metric, null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).startsWith("metric"));
    }

    @Test
    void theRunIdsOfAComparisonAreCheckedLikeAnyOther() {
        assertThatThrownBy(() -> validator.compare(new CompareEvalRunsCommand("a/b", "b", "faithfulness", null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).startsWith("base"));
        assertThatThrownBy(() -> validator.compare(new CompareEvalRunsCommand("a", "b/c", "faithfulness", null)))
                .isInstanceOfSatisfying(EvalRunInvalidException.class,
                        e -> assertThat(e.publicDetail()).startsWith("target"));
    }
}
