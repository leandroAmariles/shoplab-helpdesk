package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.CompareEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Checks the requests that read the history before the database is touched (specs/LAB-32, data-model.md). Every
 * failure names the parameter and the rule and never repeats the value it was given.
 */
@Component
public class EvalHistoryQueryValidator {

    /** A list request that is valid; {@code origin} is {@code null} for all origins. */
    public record ListQuery(RunKind kind, String origin, int limit) {
    }

    /** A comparison request that is valid. */
    public record CompareQuery(String base, String target, String metric, BigDecimal drop) {
    }

    public ListQuery list(ListEvalRunsCommand command) {
        RunKind kind = RunKind.fromValue(command.kind())
                .orElseThrow(() -> invalid("kind: is required and must be QUALITY or RETRIEVAL"));
        String origin = command.origin() == null || command.origin().isBlank() ? null : command.origin();
        if (origin != null && !EvalConstants.ORIGIN.matcher(origin).matches()) {
            throw invalid("origin: must match " + EvalConstants.ORIGIN.pattern());
        }
        return new ListQuery(kind, origin, limit(command.limit()));
    }

    /** The run id, if it fits the format of LAB-31. */
    public String runId(String runId) {
        return checkRunId("run_id", runId);
    }

    public CompareQuery compare(CompareEvalRunsCommand command) {
        String base = checkRunId("base", command.base());
        String target = checkRunId("target", command.target());
        if (base.equals(target)) {
            throw invalid("base and target: must be two different runs");
        }
        String metric = command.metric();
        if (metric == null || !EvalConstants.METRIC.matcher(metric).matches()) {
            throw invalid("metric: is required and must match " + EvalConstants.METRIC.pattern());
        }
        return new CompareQuery(base, target, metric, drop(command.drop()));
    }

    private static int limit(String text) {
        if (text == null || text.isBlank()) {
            return EvalConstants.HISTORY_LIMIT_DEFAULT;
        }
        int limit;
        try {
            limit = Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw invalid(limitRule());
        }
        if (limit < EvalConstants.HISTORY_LIMIT_MIN || limit > EvalConstants.HISTORY_LIMIT_MAX) {
            throw invalid(limitRule());
        }
        return limit;
    }

    private static String limitRule() {
        return "limit: must be a whole number from " + EvalConstants.HISTORY_LIMIT_MIN + " to "
                + EvalConstants.HISTORY_LIMIT_MAX;
    }

    private static BigDecimal drop(String text) {
        if (text == null || text.isBlank()) {
            return new BigDecimal(EvalConstants.COMPARE_DROP_DEFAULT);
        }
        BigDecimal drop;
        try {
            drop = new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw invalid("drop: must be a number from 0 to 1");
        }
        if (drop.compareTo(BigDecimal.ZERO) < 0 || drop.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("drop: must be a number from 0 to 1");
        }
        return drop;
    }

    private static String checkRunId(String name, String runId) {
        if (runId == null || !EvalConstants.RUN_ID.matcher(runId).matches()) {
            throw invalid(name + ": must match " + EvalConstants.RUN_ID.pattern());
        }
        return runId;
    }

    private static EvalRunInvalidException invalid(String detail) {
        return new EvalRunInvalidException(detail);
    }
}
