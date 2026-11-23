package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import static com.shoplab.helpdesk.eval.EvalRunFixtures.hashed;
import static com.shoplab.helpdesk.eval.EvalRunFixtures.scores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.EvalRunFixtures.Builder;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.OriginCount;
import com.shoplab.helpdesk.eval.domain.model.RunListItem;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The list of the history against a real PostgreSQL (specs/LAB-32): the whole-set mean and count of each metric are
 * the ones of {@code eval/queries/03-run-summary.sql}, a metric nobody could compute is a gap and never a zero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcEvalHistoryAdapterListTests {

    private static final String RUNS = "lst-%";
    private static final String CASES = "^G[56][0-9]{2}$";
    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    @Autowired
    private EvalHistoryPort history;

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // o1: quality a (10:00), b (11:00), c (12:00); retrieval d (13:00). o2: quality e (14:00).
        save(quality("lst-a", 5, "lst-o1", 0));
        save(quality("lst-b", 5, "lst-o1", 1).mapResult(0,
                r -> EvalRunFixtures.qualityResult(r.evalCase(), 0.9, 0.8, 0.7)));
        // c: nobody could compute the faithfulness: it is a gap, not a zero.
        Builder c = quality("lst-c", 5, "lst-o1", 2);
        c.withScores(0, scores("faithfulness", null, "answer_relevancy", 0.6, "context_precision", 0.5));
        c.withScores(1, scores("faithfulness", null, "answer_relevancy", 0.4, "context_precision", 0.3));
        save(c);
        Builder d = EvalRunFixtures.retrieval("lst-d", 6);
        d.origin = "lst-o1";
        d.startedAt = T0.plus(Duration.ofHours(3));
        d.finishedAt = d.startedAt.plusSeconds(60);
        // Only k = 1 was asked in this run: the metrics of k = 3 and 5 are not a gap, they are not there.
        d.withScores(0, scores("hit_at_1", 1, "recall_at_1", 1, "reciprocal_rank", 1.0));
        d.withScores(1, scores("hit_at_1", 0, "recall_at_1", 0, "reciprocal_rank", 0.5));
        save(d);
        save(quality("lst-e", 5, "lst-o2", 4));
    }

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run WHERE run_id LIKE ?)",
                RUNS);
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE ?", RUNS);
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ ?", CASES);
    }

    private static Builder quality(String runId, int base, String origin, int hoursAfter) {
        Builder builder = EvalRunFixtures.quality(runId, base);
        builder.origin = origin;
        builder.startedAt = T0.plus(Duration.ofHours(hoursAfter));
        builder.finishedAt = builder.startedAt.plusSeconds(600);
        return builder;
    }

    private void save(Builder builder) {
        store.save(hashed(builder.build())).block();
    }

    private RunListing list(RunKind kind, String origin, int limit) {
        return history.list(kind, origin, limit).block();
    }

    private static RunListItem run(RunListing listing, String runId) {
        return listing.runs().stream().filter(item -> item.runId().equals(runId)).findFirst().orElseThrow();
    }

    @Test
    void theMeanAndTheCountOfEachMetricIgnoreTheCasesWithoutValue() {
        RunListItem a = run(list(RunKind.QUALITY, "lst-o1", 100), "lst-a");

        // faithfulness: 1.0 and 0.5 (the case without answer is null) -> mean 0.75 over 2; the others likewise.
        assertThat(a.metrics().get("faithfulness").mean()).isCloseTo(0.75, within(1e-9));
        assertThat(a.metrics().get("faithfulness").n()).isEqualTo(2);
        assertThat(a.metrics().get("answer_relevancy").mean()).isCloseTo(0.65, within(1e-9));
        assertThat(a.metrics().get("answer_relevancy").n()).isEqualTo(2);
        assertThat(a.metrics().get("context_precision").mean()).isCloseTo(0.55, within(1e-9));
        assertThat(a.casesMeasured()).isEqualTo(3);
    }

    @Test
    void aMetricNobodyCouldComputeIsAGapWithNoCasesAndNeverAZero() {
        RunListItem c = run(list(RunKind.QUALITY, "lst-o1", 100), "lst-c");

        MetricStat faithfulness = c.metrics().get("faithfulness");
        assertThat(faithfulness).isNotNull();
        assertThat(faithfulness.mean()).isNull();
        assertThat(faithfulness.n()).isZero();
        assertThat(c.metrics().get("answer_relevancy").mean()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aRealZeroIsAScoreAndCountsInTheMean() {
        Builder zero = quality("lst-zero", 5, "lst-o3", 5);
        zero.withScores(0, scores("faithfulness", 0.0, "answer_relevancy", 0.0, "context_precision", 0.0));
        zero.withScores(1, scores("faithfulness", 0.0, "answer_relevancy", 0.0, "context_precision", 0.0));
        save(zero);

        MetricStat faithfulness = run(list(RunKind.QUALITY, "lst-o3", 100), "lst-zero").metrics()
                .get("faithfulness");

        assertThat(faithfulness.mean()).isEqualTo(0.0);
        assertThat(faithfulness.n()).isEqualTo(2);
    }

    @Test
    void aMetricTheRunDoesNotHaveIsNotInItsMap() {
        RunListItem d = run(list(RunKind.RETRIEVAL, "lst-o1", 100), "lst-d");

        assertThat(d.metrics()).containsOnlyKeys("hit_at_1", "recall_at_1", "reciprocal_rank");
        assertThat(d.metrics().get("hit_at_1").mean()).isCloseTo(0.5, within(1e-9));
        assertThat(d.metrics().get("reciprocal_rank").mean()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void theRunsComeOldestFirstAndTheKindSeparatesQualityFromRetrieval() {
        RunListing quality = list(RunKind.QUALITY, "lst-o1", 100);
        RunListing retrieval = list(RunKind.RETRIEVAL, "lst-o1", 100);

        assertThat(quality.runs()).extracting(RunListItem::runId).containsExactly("lst-a", "lst-b", "lst-c");
        assertThat(retrieval.runs()).extracting(RunListItem::runId).containsExactly("lst-d");
        assertThat(quality.kind()).isEqualTo(RunKind.QUALITY);
    }

    @Test
    void theLimitKeepsTheMostRecentRunsAndStillSaysHowManyThereAre() {
        RunListing two = list(RunKind.QUALITY, "lst-o1", 2);

        assertThat(two.runs()).extracting(RunListItem::runId).containsExactly("lst-b", "lst-c");
        assertThat(two.total()).isEqualTo(3);
    }

    @Test
    void runsStartedAtTheSameMomentAreOrderedByTheirId() {
        Builder x = quality("lst-x2", 5, "lst-o4", 7);
        Builder y = quality("lst-x1", 5, "lst-o4", 7);
        save(x);
        save(y);

        assertThat(list(RunKind.QUALITY, "lst-o4", 100).runs()).extracting(RunListItem::runId)
                .containsExactly("lst-x1", "lst-x2");
    }

    @Test
    void theOriginFilterChangesTheTotalButNotTheCountsOfTheWholeKind() {
        RunListing filtered = list(RunKind.QUALITY, "lst-o2", 100);

        assertThat(filtered.runs()).extracting(RunListItem::runId).containsExactly("lst-e");
        assertThat(filtered.total()).isEqualTo(1);
        // 4 quality runs of the seed (a, b, c, e) at least; other rows of the shared database may add more.
        assertThat(filtered.totalAllOrigins()).isGreaterThanOrEqualTo(4);
        assertThat(filtered.origins()).contains(new OriginCount("lst-o1", 3), new OriginCount("lst-o2", 1));
    }

    @Test
    void withoutAnOriginAllOfThemAreListed() {
        RunListing all = list(RunKind.QUALITY, null, 500);

        assertThat(all.runs()).extracting(RunListItem::runId).contains("lst-a", "lst-b", "lst-c", "lst-e");
        assertThat(all.total()).isEqualTo(all.totalAllOrigins());
    }

    @Test
    void anOriginWithoutRunsGivesAnEmptyList() {
        RunListing none = list(RunKind.QUALITY, "lst-nobody", 100);

        assertThat(none.runs()).isEmpty();
        assertThat(none.total()).isZero();
        assertThat(none.totalAllOrigins()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void theParametersOfEachRunArePartOfTheItem() {
        RunListItem a = run(list(RunKind.QUALITY, "lst-o1", 100), "lst-a");

        assertThat(a.origin()).isEqualTo("lst-o1");
        assertThat(a.commitSha()).isEqualTo(EvalRunFixtures.COMMIT);
        assertThat(a.assistantModel()).isEqualTo("llama3.2:3b");
        assertThat(a.prompts()).extracting(prompt -> prompt.name() + ":" + prompt.version())
                .containsExactly("chat-system:3", "chat-small-talk:1", "chat-context:2", "chat-empty-context:null");
        assertThat(a.prompts().get(2).pinned()).isTrue();
        assertThat(a.startedAt()).isEqualTo(T0);
    }

    @Test
    void aHundredRunsAreListedWellWithinTheTimeOfTheScreen() {
        for (int index = 0; index < 100; index++) {
            save(quality("lst-n%03d".formatted(index), 5, "lst-bulk", 20 + index));
        }

        long started = System.nanoTime();
        RunListing listing = list(RunKind.QUALITY, "lst-bulk", 100);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(listing.runs()).hasSize(100);
        assertThat(listing.runs().get(0).runId()).isEqualTo("lst-n000");
        assertThat(listing.runs().get(99).runId()).isEqualTo("lst-n099");
        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertThat(List.copyOf(listing.runs().get(50).metrics().keySet()))
                .contains("faithfulness", "answer_relevancy", "context_precision");
    }
}
