package com.shoplab.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three runs and the way of running the queries of {@code eval/queries/} that the tests of the history share:
 * the queries are written for {@code psql} (variables as {@code :'name'}) and each variable is replaced by a literal.
 */
final class HistoryQueryFixtures {

    private static final Path QUERIES = Path.of("../eval/queries");
    private static final Pattern VARIABLE = Pattern.compile(":'(\\w+)'");

    private HistoryQueryFixtures() {
    }

    /**
     * A: quality. B: quality later, with another version of a prompt, another model and the case G401 rewritten.
     * C: retrieval, the latest.
     */
    static void seedThreeRuns(EvalRunStorePort store) {
        EvalRunFixtures.Builder a = EvalRunFixtures.quality("hist-a", 4);
        EvalRunFixtures.Builder b = EvalRunFixtures.quality("hist-b", 4);
        b.startedAt = Instant.parse("2026-11-21T15:00:00Z");
        b.finishedAt = Instant.parse("2026-11-21T15:09:00Z");
        b.assistantModel = "qwen2.5:3b";
        b.prompts.set(0, new PromptVersion("chat-system", 4, PromptSource.LANGFUSE, false));
        b.mapCase(0, c -> new EvalCase(c.caseId(), null, c.category(), c.question() + " (reescrita)",
                c.referenceAnswer(), c.expectedDocuments()));
        b.mapResult(0, r -> EvalRunFixtures.qualityResult(r.evalCase(), 0.9, 0.9, 0.8));
        b.mapResult(1, r -> EvalRunFixtures.qualityResult(r.evalCase(), 0.2, 0.4, 0.3));
        EvalRunFixtures.Builder c = EvalRunFixtures.retrieval("hist-c", 4);
        c.startedAt = Instant.parse("2026-11-21T16:00:00Z");
        c.finishedAt = Instant.parse("2026-11-21T16:01:00Z");
        for (EvalRunFixtures.Builder run : List.of(a, b, c)) {
            store.save(EvalRunFixtures.hashed(run.build())).block();
        }
    }

    static void removeTestRows(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run "
                + "WHERE run_id LIKE 'hist-%')");
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE 'hist-%'");
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ '^G4[0-9]{2}$'");
    }

    /** Runs a query of the folder with the variables given as name, value, name, value... */
    static List<Map<String, Object>> run(JdbcTemplate jdbc, String file, String... variables) throws IOException {
        String sql = Files.readString(QUERIES.resolve(file));
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < variables.length; index += 2) {
            values.put(variables[index], variables[index + 1]);
        }
        Matcher matcher = VARIABLE.matcher(sql);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            assertThat(value).as("variable %s of %s", matcher.group(1), file).isNotNull();
            matcher.appendReplacement(replaced, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
        }
        matcher.appendTail(replaced);
        return jdbc.queryForList(replaced.toString());
    }
}
