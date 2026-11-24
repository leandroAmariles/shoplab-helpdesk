-- 08 · Las ejecuciones que usaron una versión de un prompt.
-- Para qué: «¿qué dio el prompt chat-system en su versión 4?». Las ejecuciones de recuperación no usan prompts.
-- Parámetros: prompt_name (chat-system, chat-small-talk, chat-context o chat-empty-context), prompt_version (número).
-- Cómo: psql "$DATABASE" -v prompt_name=chat-system -v prompt_version=4 -f eval/queries/08-find-by-prompt.sql
SELECT r.run_id, r.kind, r.started_at, r.assistant_model, r.prompts
FROM eval.eval_run r
WHERE r.prompts @> jsonb_build_array(jsonb_build_object('name', :'prompt_name', 'version', :'prompt_version'::int))
ORDER BY r.started_at DESC, r.id DESC;
