-- 07 · Los casos que empeoraron entre dos ejecuciones.
-- Para qué: la lista corta que se mira cuando una media baja. Solo casos comparables (misma revisión en las dos) cuya
--           métrica bajó más de `drop` puntos (0,05 = 5 puntos). Un caso editado entre las dos no aparece: no se puede
--           decir que empeoró.
-- Parámetros: run_a (la de referencia), run_b (la nueva), metric, drop (por ejemplo 0.05).
-- Cómo: psql "$DATABASE" -v run_a=t031-1 -v run_b=t031-2 -v metric=faithfulness -v drop=0.05 \
--            -f eval/queries/07-regressions.sql
SELECT c.case_id,
       c.category,
       (sa.scores ->> :'metric')::numeric AS value_a,
       (sb.scores ->> :'metric')::numeric AS value_b,
       (sb.scores ->> :'metric')::numeric - (sa.scores ->> :'metric')::numeric AS delta
FROM eval.eval_run ra
JOIN eval.eval_result sa ON sa.run_ref = ra.id
JOIN eval.eval_run rb ON rb.run_id = :'run_b'
JOIN eval.eval_result sb ON sb.run_ref = rb.id AND sb.case_ref = sa.case_ref
JOIN eval.eval_case c ON c.id = sa.case_ref
WHERE ra.run_id = :'run_a'
  AND (sa.scores ->> :'metric')::numeric - (sb.scores ->> :'metric')::numeric > :'drop'::numeric
ORDER BY delta, c.case_id;
