-- Conversation memory of the chat (specs/LAB-24): one row per turn, the customer's question and the whole answer.
-- Only the chat module reads and writes it; nothing here refers to another schema. A turn is inserted in a single
-- statement once its answer is complete, so a question never exists without its answer and the identity gives the order
-- of the turns of a conversation even when two requests of the same conversation run at once.
--
-- The table holds free text written by customers: it is never logged or measured, and the conversation id is not a
-- metric label. There is no expiry or deletion yet (a later story defines retention).
-- Flyway runs this with the schema as default, so the objects are created in 'chat'.

CREATE TABLE conversation_turn (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id uuid        NOT NULL,
    question        text        NOT NULL CHECK (length(question) > 0),
    answer          text        NOT NULL CHECK (length(answer) > 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- The newest turns of one conversation: WHERE conversation_id = ? ORDER BY id DESC LIMIT n.
CREATE INDEX conversation_turn_conversation_idx ON conversation_turn (conversation_id, id DESC);
