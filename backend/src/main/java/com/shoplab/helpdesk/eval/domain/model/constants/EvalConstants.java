package com.shoplab.helpdesk.eval.domain.model.constants;

import java.util.List;
import java.util.regex.Pattern;

/** Formats, limits and names of the evaluation history (specs/LAB-31, data-model.md). */
public final class EvalConstants {

    public static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");
    public static final Pattern CASE_ID = Pattern.compile("^G[0-9]{3}$");
    public static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    public static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");
    public static final Pattern ORIGIN = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");
    public static final Pattern DOCUMENT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    public static final Pattern TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** The name of a metric as it is read (the scores of a run are saved under these names). */
    public static final Pattern METRIC = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    public static final String TOKEN_HEADER = "X-Eval-Token";

    public static final String FAITHFULNESS = "faithfulness";
    public static final String ANSWER_RELEVANCY = "answer_relevancy";
    public static final String CONTEXT_PRECISION = "context_precision";
    public static final String RECIPROCAL_RANK = "reciprocal_rank";
    public static final String HIT_AT_PREFIX = "hit_at_";
    public static final String RECALL_AT_PREFIX = "recall_at_";

    /** The categories of the cases that are measured; the conversation categories are never measured. */
    public static final List<String> MEASURED_CATEGORIES =
            List.of("payments", "privacy", "returns", "shipping", "warranty", "off_topic");

    /** The prompts of the chat (LAB-85), in the order the assistant reports them. */
    public static final List<String> PROMPT_NAMES =
            List.of("chat-system", "chat-small-talk", "chat-context", "chat-empty-context");

    public static final int QUESTION_MIN = 10;
    public static final int QUESTION_MAX = 300;
    public static final int REFERENCE_ANSWER_MIN = 20;
    public static final int REFERENCE_ANSWER_MAX = 800;
    public static final int MAX_EXPECTED_DOCUMENTS = 2;

    public static final int TOP_K_MIN = 1;
    public static final int TOP_K_MAX = 20;
    public static final int MIN_CONTEXT_CHARACTERS = 500;
    public static final int DEPTH_MIN = 1;
    public static final int DEPTH_MAX = 50;

    public static final int HISTORY_LIMIT_DEFAULT = 100;
    public static final int HISTORY_LIMIT_MIN = 1;
    public static final int HISTORY_LIMIT_MAX = 500;
    /** What a metric has to change between two runs to count as worse or better, when the request does not say. */
    public static final String COMPARE_DROP_DEFAULT = "0.05";
    public static final String SUMMARY_ALL = "all";

    public static final double SCORE_MIN = 0.0;
    public static final double SCORE_MAX = 1.0;

    private EvalConstants() {
    }
}
