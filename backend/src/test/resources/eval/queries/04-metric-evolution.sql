-- 04 · La evolución de una métrica a lo largo de las ejecuciones.
-- Para qué: ver si la fidelidad (o cualquier métrica) sube o baja de una ejecución a la siguiente, en orden cronológico.
--           Compara solo ejecuciones que tengan la métrica; mira antes los parámetros (consulta 06) de las que difieren.
-- Parámetros: metric (por ejemplo faithfulness, answer_relevancy, context_precision, hit_at_3, recall_at_3,
--             reciprocal_rank).
-- Cómo: psql "$DATABASE" -v metric=faithfulness -f eval/queries/04-metric-evolution.sql
SELECT r.run_id,
       r.kind,
       r.started_at,
       round(avg((s.scores ->> :'metric')::numeric), 4) AS mean,
       count(s.scores ->> :'metric') AS n
FROM eval.eval_run r
JOIN eval.eval_result s ON s.run_ref = r.id
GROUP BY r.id
HAVING count(s.scores ->> :'metric') > 0
ORDER BY r.started_at, r.id;
