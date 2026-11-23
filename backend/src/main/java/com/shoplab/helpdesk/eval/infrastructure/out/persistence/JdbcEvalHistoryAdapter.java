package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.OriginCount;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.RunListItem;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the history of {@code eval.eval_run}, {@code eval.eval_case} and {@code eval.eval_result} (specs/LAB-32). It
 * only runs {@code SELECT}s. The mean of a metric is computed here, in SQL, ignoring the scores that are
 * {@code null}: it is the definition of {@code eval/queries/03-run-summary.sql} and of the summary of the CSV files,
 * so the list, the chart and the summary all agree. Blocking JDBC runs through {@link BlockingCalls}.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcEvalHistoryAdapter implements EvalHistoryPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Map<String, Object>>> PROMPT_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final TypeReference<LinkedHashMap<String, Double>> SCORES = new TypeReference<>() { };

    private static final String LIST_RUNS = """
            SELECT id, run_id, kind, started_at, finished_at, origin, commit_sha, assistant_model,
                   prompts::text AS prompts, cases_measured
            FROM eval.eval_run
            WHERE kind = ? AND (?::text IS NULL OR origin = ?)
            ORDER BY started_at DESC, run_id DESC
            LIMIT ?
            """;
    private static final String COUNT_BY_ORIGIN = """
            SELECT origin, count(*) AS runs FROM eval.eval_run WHERE kind = ?
            GROUP BY origin ORDER BY runs DESC, origin
            """;
    private static final String FIND_RUN = """
            SELECT id, run_id, kind, started_at, finished_at, recorded_at, origin, commit_sha, dataset_hash,
                   cases_measured, cases_excluded, assistant_model, embedding_model, prompts::text AS prompts,
                   judge::text AS judge, evaluator::text AS evaluator, retrieval_config::text AS retrieval_config
            FROM eval.eval_run WHERE run_id = ?
            """;
    private static final String SUMMARY_METRICS = """
            SELECT scope, metric, mean, n FROM (
                SELECT 'all' AS scope, e.key AS metric,
                       avg((e.value #>> '{}')::numeric) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS mean,
                       count(*) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS n
                FROM eval.eval_result r CROSS JOIN LATERAL jsonb_each(r.scores) e
                WHERE r.run_ref = ?
                GROUP BY e.key
                UNION ALL
                SELECT c.category AS scope, e.key AS metric,
                       avg((e.value #>> '{}')::numeric) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS mean,
                       count(*) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS n
                FROM eval.eval_result r
                JOIN eval.eval_case c ON c.id = r.case_ref
                CROSS JOIN LATERAL jsonb_each(r.scores) e
                WHERE r.run_ref = ?
                GROUP BY c.category, e.key
            ) summary
            ORDER BY (scope <> 'all'), scope, metric
            """;
    private static final String SUMMARY_CASES = """
            SELECT 'all' AS scope, count(*) AS cases FROM eval.eval_result WHERE run_ref = ?
            UNION ALL
            SELECT c.category AS scope, count(*) AS cases
            FROM eval.eval_result r JOIN eval.eval_case c ON c.id = r.case_ref
            WHERE r.run_ref = ? GROUP BY c.category
            """;
    private static final String RESULTS_OF_RUN = """
            SELECT c.case_id, c.content_hash, c.category, c.question, c.reference_answer, c.expected_documents,
                   r.case_kind, r.scores::text AS scores, r.retrieved_documents, r.abstained, r.trace_id
            FROM eval.eval_result r JOIN eval.eval_case c ON c.id = r.case_ref
            WHERE r.run_ref = ?
            ORDER BY c.case_id
            """;
    private static final String METRICS_OF_RUNS = """
            SELECT r.run_ref AS run_ref, e.key AS metric,
                   avg((e.value #>> '{}')::numeric) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS mean,
                   count(*) FILTER (WHERE jsonb_typeof(e.value) = 'number') AS n
            FROM eval.eval_result r CROSS JOIN LATERAL jsonb_each(r.scores) e
            WHERE r.run_ref IN (%s)
            GROUP BY r.run_ref, e.key
            """;

    private final JdbcTemplate jdbc;

    public JdbcEvalHistoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<RunListing> list(RunKind kind, String origin, int limit) {
        return BlockingCalls.call(() -> read(kind, origin, limit))
                .onErrorMap(JdbcEvalHistoryAdapter::translate);
    }

    @Override
    public Mono<Optional<StoredRun>> find(String runId) {
        return BlockingCalls.call(() -> readRun(runId)).onErrorMap(JdbcEvalHistoryAdapter::translate);
    }

    private Optional<StoredRun> readRun(String runId) {
        List<RunHeaderRow> headers = jdbc.query(FIND_RUN, (rs, index) -> headerRow(rs), runId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        RunHeaderRow header = headers.get(0);
        return Optional.of(header.toRun(summaryOf(header.id()), resultsOf(header.id())));
    }

    private List<ScopeSummary> summaryOf(long runRef) {
        Map<String, Integer> cases = new LinkedHashMap<>();
        jdbc.query(SUMMARY_CASES, rs -> {
            cases.put(rs.getString("scope"), rs.getInt("cases"));
        }, runRef, runRef);
        Map<String, Map<String, MetricStat>> metrics = new LinkedHashMap<>();
        jdbc.query(SUMMARY_METRICS, rs -> {
            metrics.computeIfAbsent(rs.getString("scope"), key -> new LinkedHashMap<>())
                    .put(rs.getString("metric"), stat(rs));
        }, runRef, runRef);
        List<String> scopes = new ArrayList<>(cases.keySet());
        scopes.sort((a, b) -> scopeRank(a).compareTo(scopeRank(b)));
        List<ScopeSummary> summary = new ArrayList<>();
        for (String scope : scopes) {
            summary.add(new ScopeSummary(scope, cases.get(scope), metrics.getOrDefault(scope, Map.of())));
        }
        return summary;
    }

    /** The whole set first, then the categories in alphabetical order. */
    private static String scopeRank(String scope) {
        return EvalConstants.SUMMARY_ALL.equals(scope) ? "0" : "1" + scope;
    }

    private List<StoredResult> resultsOf(long runRef) {
        return jdbc.query(RESULTS_OF_RUN, (rs, index) -> new StoredResult(rs.getString("case_id"),
                rs.getString("content_hash"), rs.getString("category"), rs.getString("question"),
                rs.getString("reference_answer"), strings(rs.getArray("expected_documents")),
                CaseKind.fromValue(rs.getString("case_kind")).orElseThrow(),
                JSON.readValue(rs.getString("scores"), SCORES), strings(rs.getArray("retrieved_documents")),
                nullableBoolean(rs, "abstained"), rs.getString("trace_id")), runRef);
    }

    private static RunHeaderRow headerRow(ResultSet rs) throws SQLException {
        return new RunHeaderRow(rs.getLong("id"), rs.getString("run_id"), RunKind.valueOf(rs.getString("kind")),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class), rs.getString("origin"),
                rs.getString("commit_sha"), rs.getString("dataset_hash"), rs.getInt("cases_measured"),
                rs.getInt("cases_excluded"), rs.getString("assistant_model"), rs.getString("embedding_model"),
                prompts(rs.getString("prompts")), judge(rs.getString("judge")),
                evaluator(rs.getString("evaluator")), retrievalConfig(rs.getString("retrieval_config")));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    static JudgeInfo judge(String json) {
        if (json == null) {
            return null;
        }
        Map<String, Object> map = JSON.readValue(json, OBJECT);
        return new JudgeInfo((String) map.get("model"), (String) map.get("underlying_model"),
                number(map.get("temperature"), Number::doubleValue), number(map.get("seed"), Number::intValue),
                number(map.get("max_input_chars"), Number::intValue));
    }

    static EvaluatorInfo evaluator(String json) {
        if (json == null) {
            return null;
        }
        Map<String, Object> map = JSON.readValue(json, OBJECT);
        return new EvaluatorInfo((String) map.get("library"), (String) map.get("library_version"),
                (String) map.get("judgment_instructions_version"));
    }

    static RetrievalConfig retrievalConfig(String json) {
        Map<String, Object> map = JSON.readValue(json, OBJECT);
        List<Integer> ks = null;
        if (map.get("ks") instanceof List<?> values) {
            ks = values.stream().map(value -> ((Number) value).intValue()).toList();
        }
        return new RetrievalConfig(number(map.get("top_k"), Number::intValue),
                number(map.get("similarity_threshold"), Number::doubleValue),
                number(map.get("max_context_characters"), Number::intValue),
                number(map.get("depth"), Number::intValue), ks);
    }

    private static <T> T number(Object value, Function<Number, T> convert) {
        return value instanceof Number number ? convert.apply(number) : null;
    }

    private RunListing read(RunKind kind, String origin, int limit) {
        List<RunRow> rows = jdbc.query(LIST_RUNS, (rs, index) -> runRow(rs), kind.name(), origin, origin, limit);
        Map<Long, Map<String, MetricStat>> metrics = metricsOf(rows);
        List<RunListItem> items = new ArrayList<>();
        for (RunRow row : rows) {
            items.add(row.toItem(kind, metrics.getOrDefault(row.id(), Map.of())));
        }
        // Read newest first to keep the most recent ones; the chart wants them oldest first.
        Collections.reverse(items);
        List<OriginCount> origins = jdbc.query(COUNT_BY_ORIGIN,
                (rs, index) -> new OriginCount(rs.getString("origin"), rs.getLong("runs")), kind.name());
        long totalAllOrigins = origins.stream().mapToLong(OriginCount::count).sum();
        long total = origin == null ? totalAllOrigins : origins.stream()
                .filter(count -> count.origin().equals(origin)).mapToLong(OriginCount::count).sum();
        return new RunListing(kind, items, total, totalAllOrigins, origins);
    }

    private Map<Long, Map<String, MetricStat>> metricsOf(List<RunRow> rows) {
        Map<Long, Map<String, MetricStat>> byRun = new HashMap<>();
        if (rows.isEmpty()) {
            return byRun;
        }
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(RunRow::id).toArray();
        jdbc.query(METRICS_OF_RUNS.formatted(placeholders), rs -> {
            byRun.computeIfAbsent(rs.getLong("run_ref"), key -> new LinkedHashMap<>())
                    .put(rs.getString("metric"), stat(rs));
        }, ids);
        return byRun;
    }

    static MetricStat stat(ResultSet rs) throws SQLException {
        BigDecimal mean = rs.getBigDecimal("mean");
        return new MetricStat(mean == null ? null : mean.doubleValue(), rs.getInt("n"));
    }

    private static RunRow runRow(ResultSet rs) throws SQLException {
        return new RunRow(rs.getLong("id"), rs.getString("run_id"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class), rs.getString("origin"),
                rs.getString("commit_sha"), rs.getString("assistant_model"), prompts(rs.getString("prompts")),
                rs.getInt("cases_measured"));
    }

    static List<PromptVersion> prompts(String json) {
        List<PromptVersion> prompts = new ArrayList<>();
        for (Map<String, Object> entry : JSON.readValue(json, PROMPT_LIST)) {
            Object version = entry.get("version");
            prompts.add(new PromptVersion(String.valueOf(entry.get("name")),
                    version == null ? null : ((Number) version).intValue(),
                    PromptSource.valueOf(String.valueOf(entry.get("source"))),
                    Boolean.TRUE.equals(entry.get("pinned"))));
        }
        return prompts;
    }

    /** Only a database failure is translated; nothing else of the text of a run is ever put in an error. */
    private static Throwable translate(Throwable error) {
        if (error instanceof DataAccessException || error instanceof TransactionException) {
            return new EvalStoreUnavailableException(error);
        }
        return error;
    }

    private record RunHeaderRow(long id, String runId, RunKind kind, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, OffsetDateTime recordedAt, String origin, String commitSha,
            String datasetHash, int casesMeasured, int casesExcluded, String assistantModel, String embeddingModel,
            List<PromptVersion> prompts, JudgeInfo judge, EvaluatorInfo evaluator, RetrievalConfig retrievalConfig) {

        StoredRun toRun(List<ScopeSummary> summary, List<StoredResult> results) {
            return new StoredRun(runId, kind, startedAt.toInstant(), finishedAt.toInstant(), recordedAt.toInstant(),
                    origin, commitSha, datasetHash, casesMeasured, casesExcluded, assistantModel, embeddingModel,
                    prompts, judge, evaluator, retrievalConfig, summary, results);
        }
    }

    private record RunRow(long id, String runId, OffsetDateTime startedAt, OffsetDateTime finishedAt, String origin,
            String commitSha, String assistantModel, List<PromptVersion> prompts, int casesMeasured) {

        RunListItem toItem(RunKind kind, Map<String, MetricStat> metrics) {
            return new RunListItem(runId, kind, startedAt.toInstant(), finishedAt.toInstant(), origin, commitSha,
                    assistantModel, prompts, casesMeasured, metrics);
        }
    }
}
