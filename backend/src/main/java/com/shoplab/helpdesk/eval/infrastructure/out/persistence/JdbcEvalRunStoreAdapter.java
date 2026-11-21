package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunAlreadyExistsException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvalResult;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the history in {@code eval.eval_run}, {@code eval.eval_case} and {@code eval.eval_result}, which only this
 * module reads and writes. A run goes in with all its results in one transaction, so a run that is visible is a whole
 * run. Blocking JDBC runs through {@link BlockingCalls}; no text of a question or an answer appears in an error.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcEvalRunStoreAdapter implements EvalRunStorePort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RUN_ID_CONSTRAINT = "eval_run_run_id_key";

    private static final String INSERT_RUN = """
            INSERT INTO eval.eval_run (run_id, kind, started_at, finished_at, origin, commit_sha, dataset_hash,
                cases_measured, cases_excluded, assistant_model, embedding_model, prompts, judge, evaluator,
                retrieval_config)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
            RETURNING id
            """;
    private static final String INSERT_CASE = """
            INSERT INTO eval.eval_case (case_id, content_hash, category, question, reference_answer,
                expected_documents)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (case_id, content_hash) DO UPDATE SET case_id = EXCLUDED.case_id
            RETURNING id, (xmax = 0) AS inserted
            """;
    private static final String INSERT_RESULT = """
            INSERT INTO eval.eval_result (run_ref, case_ref, case_kind, scores, retrieved_documents, abstained,
                trace_id)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
            """;
    private static final String FIND = """
            SELECT run_id, kind, started_at, recorded_at FROM eval.eval_run WHERE run_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcEvalRunStoreAdapter(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Mono<SavedRun> save(EvalRun run) {
        return BlockingCalls.call(() -> transaction.execute(status -> store(run)))
                .onErrorMap(JdbcEvalRunStoreAdapter::translate);
    }

    @Override
    public Mono<Optional<StoredRunSummary>> find(String runId) {
        return BlockingCalls.call(() -> jdbc.query(FIND, (rs, index) -> summary(rs), runId).stream().findFirst())
                .onErrorMap(JdbcEvalRunStoreAdapter::translate);
    }

    private SavedRun store(EvalRun run) {
        Long runRef = jdbc.queryForObject(INSERT_RUN, Long.class, run.runId(), run.kind().name(),
                utc(run.startedAt()), utc(run.finishedAt()), run.origin(), run.commitSha(), run.datasetHash(),
                run.casesMeasured(), run.casesExcluded(), run.assistantModel(), run.embeddingModel(),
                promptsJson(run.prompts()), judgeJson(run), evaluatorJson(run), configJson(run.retrievalConfig()));
        int created = 0;
        List<Object[]> rows = new ArrayList<>();
        for (EvalResult result : run.results()) {
            CaseRow row = caseRevision(result.evalCase());
            created += row.inserted() ? 1 : 0;
            rows.add(new Object[] {runRef, row.id(), result.caseKind().value(), JSON.writeValueAsString(
                    result.scores()), result.retrievedDocuments().toArray(String[]::new), result.abstained(),
                    result.traceId()});
        }
        jdbc.batchUpdate(INSERT_RESULT, rows);
        return new SavedRun(run.runId(), run.kind(), run.results().size(), created);
    }

    private CaseRow caseRevision(EvalCase evalCase) {
        return jdbc.queryForObject(INSERT_CASE,
                (rs, index) -> new CaseRow(rs.getLong("id"), rs.getBoolean("inserted")), evalCase.caseId(),
                evalCase.contentHash(), evalCase.category(), evalCase.question(), evalCase.referenceAnswer(),
                evalCase.expectedDocuments().toArray(String[]::new));
    }

    private static StoredRunSummary summary(ResultSet rs) throws SQLException {
        return new StoredRunSummary(rs.getString("run_id"), RunKind.valueOf(rs.getString("kind")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String promptsJson(List<PromptVersion> prompts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PromptVersion prompt : prompts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", prompt.name());
            entry.put("version", prompt.version());
            entry.put("source", prompt.source().name());
            entry.put("pinned", prompt.pinned());
            list.add(entry);
        }
        return JSON.writeValueAsString(list);
    }

    private static String judgeJson(EvalRun run) {
        if (run.judge() == null) {
            return null;
        }
        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("model", run.judge().model());
        judge.put("underlying_model", run.judge().underlyingModel());
        judge.put("temperature", run.judge().temperature());
        judge.put("seed", run.judge().seed());
        judge.put("max_input_chars", run.judge().maxInputChars());
        return JSON.writeValueAsString(judge);
    }

    private static String evaluatorJson(EvalRun run) {
        if (run.evaluator() == null) {
            return null;
        }
        Map<String, Object> evaluator = new LinkedHashMap<>();
        evaluator.put("library", run.evaluator().library());
        evaluator.put("library_version", run.evaluator().libraryVersion());
        evaluator.put("judgment_instructions_version", run.evaluator().judgmentInstructionsVersion());
        return JSON.writeValueAsString(evaluator);
    }

    private static String configJson(RetrievalConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("top_k", config.topK());
        map.put("similarity_threshold", config.similarityThreshold());
        map.put("max_context_characters", config.maxContextCharacters());
        if (config.depth() != null) {
            map.put("depth", config.depth());
        }
        if (config.ks() != null) {
            map.put("ks", config.ks());
        }
        return JSON.writeValueAsString(map);
    }

    /** A run id that already exists is a conflict, any other broken rule an invalid run, the rest a database failure. */
    private static Throwable translate(Throwable error) {
        if (error instanceof DuplicateKeyException && mentions(error, RUN_ID_CONSTRAINT)) {
            return new EvalRunAlreadyExistsException();
        }
        if (error instanceof DataIntegrityViolationException) {
            return new EvalRunInvalidException("the database rejected the run");
        }
        if (error instanceof DataAccessException || error instanceof TransactionException) {
            return new EvalStoreUnavailableException(error);
        }
        return error;
    }

    private static boolean mentions(Throwable error, String text) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains(text)) {
                return true;
            }
        }
        return false;
    }

    private record CaseRow(long id, boolean inserted) {
    }
}
