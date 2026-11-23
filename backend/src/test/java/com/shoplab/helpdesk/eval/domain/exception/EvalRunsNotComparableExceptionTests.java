package com.shoplab.helpdesk.eval.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.shared.exception.PublicDetail;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EvalRunsNotComparableExceptionTests {

    @Test
    void twoRunsOfDifferentKindsAreAnUnprocessableRequestWithItsOwnCode() {
        EvalRunsNotComparableException exception = new EvalRunsNotComparableException();

        assertThat(exception.getResponseCode()).isEqualTo(ResponseCodeEnum.EVAL_RUNS_NOT_COMPARABLE);
        assertThat(ResponseCodeEnum.EVAL_RUNS_NOT_COMPARABLE.getCode()).isEqualTo("SLHD_EVAL_00006");
        assertThat(ResponseCodeEnum.EVAL_RUNS_NOT_COMPARABLE.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void theMessageOfTheScreenComesFromTheCodeNotFromADetail() {
        assertThat(new EvalRunsNotComparableException()).isNotInstanceOf(PublicDetail.class);
    }

    @Test
    void theCodesOfTheEvalModuleAreUnique() {
        long eval = java.util.Arrays.stream(ResponseCodeEnum.values())
                .filter(code -> code.getCode().startsWith("SLHD_EVAL_")).count();
        long distinct = java.util.Arrays.stream(ResponseCodeEnum.values())
                .filter(code -> code.getCode().startsWith("SLHD_EVAL_")).map(ResponseCodeEnum::getCode).distinct()
                .count();

        assertThat(distinct).isEqualTo(eval).isEqualTo(6);
    }
}
