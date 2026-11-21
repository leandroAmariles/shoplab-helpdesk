-- 06 · Qué parámetros difieren entre dos ejecuciones.
-- Para qué: antes de atribuir una diferencia de métricas al código, ver si cambió la vara de medir: el juez, el modelo del
--           asistente, los embeddings, la recuperación, el dataset o los prompts. Una fila por parámetro que difiere.
-- Parámetros: run_a, run_b.
-- Cómo: psql "$DATABASE" -v run_a=t031-1 -v run_b=t031-2 -f eval/queries/06-compare-parameters.sql
SELECT x.parameter, x.a_value, x.b_value
FROM eval.eval_run a
JOIN eval.eval_run b ON b.run_id = :'run_b'
CROSS JOIN LATERAL (VALUES
    ('kind', a.kind, b.kind),
    ('assistant_model', a.assistant_model, b.assistant_model),
    ('embedding_model', a.embedding_model, b.embedding_model),
    ('judge', a.judge::text, b.judge::text),
    ('evaluator', a.evaluator::text, b.evaluator::text),
    ('retrieval_config', a.retrieval_config::text, b.retrieval_config::text),
    ('prompts', a.prompts::text, b.prompts::text),
    ('dataset_hash', a.dataset_hash, b.dataset_hash),
    ('commit_sha', a.commit_sha, b.commit_sha),
    ('origin', a.origin, b.origin)
) AS x (parameter, a_value, b_value)
WHERE a.run_id = :'run_a'
  AND x.a_value IS DISTINCT FROM x.b_value
ORDER BY x.parameter;
