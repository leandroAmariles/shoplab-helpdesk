-- 09 · Las ejecuciones medidas con un modelo del asistente.
-- Parámetros: model (por ejemplo llama3.2:3b).
-- Cómo: psql "$DATABASE" -v model=llama3.2:3b -f eval/queries/09-find-by-model.sql
SELECT r.run_id, r.kind, r.started_at, r.assistant_model, r.embedding_model
FROM eval.eval_run r
WHERE r.assistant_model = :'model'
ORDER BY r.started_at DESC, r.id DESC;
