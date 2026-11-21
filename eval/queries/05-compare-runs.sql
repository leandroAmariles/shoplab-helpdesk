-- 05 · Dos ejecuciones, caso a caso.
-- Para qué: ver qué preguntas cambiaron, no solo la media. delta = valor de B menos valor de A (negativo = B es peor).
--           comparable = false cuando el caso cambió de contenido entre las dos (otra revisión): su diferencia no se debe
--           a la calidad del asistente. Es nulo cuando el caso solo está en una de las dos.
-- Parámetros: run_a (la de referencia), run_b (la nueva), metric.
-- Cómo: psql "$DATABASE" -v run_a=t031-1 -v run_b=t031-2 -v metric=faithfulness -f eval/queries/05-compare-runs.sql
WITH a AS (
    SELECT s.case_ref, c.case_id, c.category, (s.scores ->> :'metric')::numeric AS value
    FROM eval.eval_run r
    JOIN eval.eval_result s ON s.run_ref = r.id
    JOIN eval.eval_case c ON c.id = s.case_ref
    WHERE r.run_id = :'run_a'
), b AS (
    SELECT s.case_ref, c.case_id, c.category, (s.scores ->> :'metric')::numeric AS value
    FROM eval.eval_run r
    JOIN eval.eval_result s ON s.run_ref = r.id
    JOIN eval.eval_case c ON c.id = s.case_ref
    WHERE r.run_id = :'run_b'
)
SELECT coalesce(a.case_id, b.case_id) AS case_id,
       coalesce(a.category, b.category) AS category,
       a.value AS value_a,
       b.value AS value_b,
       b.value - a.value AS delta,
       (a.case_ref = b.case_ref) AS comparable
FROM a
FULL JOIN b ON b.case_id = a.case_id
ORDER BY delta NULLS LAST, case_id;
