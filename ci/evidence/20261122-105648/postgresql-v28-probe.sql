\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA tu03c_upgrade_probe;
SET LOCAL search_path = tu03c_upgrade_probe, public;
CREATE TABLE tool_call(id bigint PRIMARY KEY, arguments_json text, status varchar(20));
CREATE TABLE tool_question(prompt_message_id bigint PRIMARY KEY, decision_call_id bigint, continued boolean, marker text);
INSERT INTO tool_call VALUES(1, '{"ticketId":"T-1"}', 'SUCCEEDED');
INSERT INTO tool_question VALUES(10, 1, true, 'unchanged');
\i /tmp/migration/V28__tool_dependent_action.sql
DO $$
DECLARE changed integer;
BEGIN
  IF NOT EXISTS(SELECT 1 FROM tool_call WHERE id=1 AND parent_call_id IS NULL AND arguments_json='{"ticketId":"T-1"}' AND status='SUCCEEDED') THEN
    RAISE EXCEPTION 'V28 changed an existing card';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM tool_question WHERE prompt_message_id=10 AND followup_used=false AND marker='unchanged') THEN
    RAISE EXCEPTION 'V28 changed existing question data or granted a followup';
  END IF;
  UPDATE tool_question SET followup_used=true WHERE prompt_message_id=10 AND decision_call_id=999 AND continued=true AND followup_used=false;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>0 THEN RAISE EXCEPTION 'wrong parent claimed a followup'; END IF;
  UPDATE tool_question SET followup_used=true WHERE prompt_message_id=10 AND decision_call_id=1 AND continued=true AND followup_used=false;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE EXCEPTION 'first followup claim failed'; END IF;
  UPDATE tool_question SET followup_used=true WHERE prompt_message_id=10 AND decision_call_id=1 AND continued=true AND followup_used=false;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>0 THEN RAISE EXCEPTION 'second followup claim succeeded'; END IF;
END $$;
INSERT INTO tool_call VALUES(2,'{"ticketId":"T-1","exact":9007199254740993}','PENDING',1);
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM tool_call WHERE id=2 AND parent_call_id=1 AND arguments_json LIKE '%9007199254740993%') THEN
    RAISE EXCEPTION 'dependent card lost its parent or exact arguments';
  END IF;
END $$;
SELECT 'PASS: V28 additive upgrade, preserved rows, parent binding, single claim, exact arguments' AS result;
ROLLBACK;
