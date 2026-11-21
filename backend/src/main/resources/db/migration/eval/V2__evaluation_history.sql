-- History of the evaluation runs (specs/LAB-31): one row per run, one per revision of a case of the golden dataset and
-- one per result of a case in a run. Only the eval module reads and writes it; nothing here refers to another schema,
-- and a reference to a document of the knowledge base is only its id (the name of its file without the extension).
--
-- A run is inserted together with its results in a single transaction, so a row of eval_run is always a complete run.
-- There is no deletion or retention yet: the history only grows.
--
-- The tables never hold the text of an answer of the assistant or of a retrieved fragment: that content lives in the
-- traces of Langfuse, which eval_result.trace_id points to. eval_case holds the golden dataset, which is synthetic.
-- Flyway runs this with the schema as default, so the objects are created in 'eval'.

CREATE TABLE eval_run (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id            text        NOT NULL,
    kind              text        NOT NULL,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz NOT NULL,
    recorded_at       timestamptz NOT NULL DEFAULT now(),
    origin            text        NOT NULL,
    commit_sha        text,
    dataset_hash      text        NOT NULL,
    cases_measured    integer     NOT NULL,
    cases_excluded    integer     NOT NULL,
    assistant_model   text,
    embedding_model   text        NOT NULL,
    prompts           jsonb       NOT NULL,
    judge             jsonb,
    evaluator         jsonb,
    retrieval_config  jsonb       NOT NULL,
    CONSTRAINT eval_run_run_id_key UNIQUE (run_id),
    CONSTRAINT eval_run_run_id_format CHECK (run_id ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT eval_run_kind_known CHECK (kind IN ('RETRIEVAL', 'QUALITY')),
    CONSTRAINT eval_run_finished_after_started CHECK (finished_at >= started_at),
    CONSTRAINT eval_run_origin_format CHECK (origin ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT eval_run_commit_sha_format CHECK (commit_sha IS NULL OR commit_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT eval_run_dataset_hash_format CHECK (dataset_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT eval_run_counts_not_negative CHECK (cases_measured >= 0 AND cases_excluded >= 0),
    CONSTRAINT eval_run_prompts_is_array CHECK (jsonb_typeof(prompts) = 'array'),
    CONSTRAINT eval_run_retrieval_config_is_object CHECK (jsonb_typeof(retrieval_config) = 'object'),
    CONSTRAINT eval_run_judge_is_object CHECK (judge IS NULL OR jsonb_typeof(judge) = 'object'),
    CONSTRAINT eval_run_evaluator_is_object CHECK (evaluator IS NULL OR jsonb_typeof(evaluator) = 'object'),
    CONSTRAINT eval_run_judge_only_in_quality CHECK ((kind = 'QUALITY') = (judge IS NOT NULL)),
    CONSTRAINT eval_run_evaluator_only_in_quality CHECK ((kind = 'QUALITY') = (evaluator IS NOT NULL))
);

-- The newest runs first: the listing of the history.
CREATE INDEX eval_run_started_at_idx ON eval_run (started_at DESC);

-- One row per revision of a case: the same id with other content is another row, so a result never changes meaning
-- when the dataset is edited. content_hash is computed by the module from the five fields that follow it.
CREATE TABLE eval_case (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id            text        NOT NULL,
    content_hash       text        NOT NULL,
    category           text        NOT NULL,
    question           text        NOT NULL,
    reference_answer   text        NOT NULL,
    expected_documents text[]      NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT eval_case_revision_key UNIQUE (case_id, content_hash),
    CONSTRAINT eval_case_case_id_format CHECK (case_id ~ '^G[0-9]{3}$'),
    CONSTRAINT eval_case_content_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT eval_case_category_known CHECK (category IN ('payments', 'privacy', 'returns', 'shipping', 'warranty',
        'off_topic', 'small_talk', 'injection'))
);

-- What a case gave in a run. scores maps the stable name of each metric to a number from 0 to 1, or to null when the
-- metric does not apply or cannot be computed by definition: a null is never a zero.
CREATE TABLE eval_result (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_ref             bigint  NOT NULL REFERENCES eval_run (id),
    case_ref            bigint  NOT NULL REFERENCES eval_case (id),
    case_kind           text    NOT NULL,
    scores              jsonb   NOT NULL,
    retrieved_documents text[]  NOT NULL,
    abstained           boolean,
    trace_id            text,
    CONSTRAINT eval_result_case_once_per_run_key UNIQUE (run_ref, case_ref),
    CONSTRAINT eval_result_case_kind_known CHECK (case_kind IN ('answered', 'unanswered')),
    CONSTRAINT eval_result_scores_is_object CHECK (jsonb_typeof(scores) = 'object'),
    CONSTRAINT eval_result_scores_between_zero_and_one CHECK (
        NOT jsonb_path_exists(scores, '$.* ? (@.type() == "number" && (@ < 0 || @ > 1))'))
);

CREATE INDEX eval_result_case_ref_idx ON eval_result (case_ref);
