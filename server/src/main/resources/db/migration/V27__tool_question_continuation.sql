CREATE TABLE tool_question (
    prompt_message_id bigint PRIMARY KEY,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    org_id bigint NOT NULL,
    space_id bigint NOT NULL,
    prompt_text text NOT NULL,
    dialogue_json text NOT NULL,
    catalog_json text NOT NULL,
    web_context text NOT NULL,
    model_requests integer NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    ready boolean NOT NULL DEFAULT false,
    continued boolean NOT NULL DEFAULT false,
    continuation_hash varchar(64),
    continuation_expires_at timestamp with time zone,
    decision_call_id bigint,
    created_at timestamp with time zone NOT NULL
);
ALTER TABLE chat_message ADD COLUMN prompt_message_id bigint;
CREATE INDEX idx_tool_question_conversation ON tool_question(conversation_id);

CREATE TABLE tool_execution_guard (
    prompt_message_id bigint PRIMARY KEY,
    conversation_id bigint NOT NULL,
    lease varchar(36)
);
