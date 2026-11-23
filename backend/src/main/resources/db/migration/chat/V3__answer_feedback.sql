-- Answer feedback and golden-case promotion (specs/LAB-33, ADR-0030):
-- 1. Extends chat.conversation_turn with a public UUID turn_id (returned in the SSE done event), the list of delivered
--    document ids and the optional 32-hex Langfuse trace id.
-- 2. Creates chat.feedback with a 1:1 link to conversation_turn (UNIQUE turn_ref), UP/DOWN rating, optional comment
--    (1..500 characters after trim) and optional golden-case promotion fields (G<NNN> id, canonical JSONL line and
--    promotion timestamp).
-- Everything stays inside the chat schema (ADR-0015).

ALTER TABLE conversation_turn
    ADD COLUMN turn_id             uuid   NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN delivered_documents text[] NOT NULL DEFAULT '{}',
    ADD COLUMN trace_id            text   CONSTRAINT conversation_turn_trace_id_chk
        CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$');

CREATE TABLE feedback (
    id                  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_ref            bigint      NOT NULL UNIQUE REFERENCES conversation_turn(id) ON DELETE CASCADE,
    rating              text        NOT NULL,
    comment             text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    promoted_case_id    text        UNIQUE,
    promoted_case_jsonl text,
    promoted_at         timestamptz,
    CONSTRAINT feedback_rating_chk
        CHECK (rating IN ('UP', 'DOWN')),
    CONSTRAINT feedback_comment_chk
        CHECK (comment IS NULL OR (length(btrim(comment)) > 0 AND length(comment) <= 500)),
    CONSTRAINT feedback_promoted_case_id_chk
        CHECK (promoted_case_id IS NULL OR promoted_case_id ~ '^G[0-9]{3}$'),
    CONSTRAINT feedback_promoted_only_down_chk
        CHECK (promoted_case_id IS NULL OR rating = 'DOWN'),
    CONSTRAINT feedback_promotion_complete_chk
        CHECK ((promoted_case_id IS NULL AND promoted_case_jsonl IS NULL AND promoted_at IS NULL)
            OR (promoted_case_id IS NOT NULL AND length(btrim(promoted_case_jsonl)) > 0 AND promoted_at IS NOT NULL))
);

CREATE INDEX feedback_rating_created_idx ON feedback (rating, created_at DESC, id DESC);
