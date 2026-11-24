-- 03 · El resumen de una ejecución, recalculado desde sus resultados.
-- Para qué: media, casos que entraron (n) y casos vacíos de cada métrica, para el conjunto completo (all) y por categoría.
--           Coincide con la fila equivalente del CSV de la ejecución; un vacío no entra en la media y no es un cero.
-- Parámetros: run_id.
-- Cómo: psql "$DATABASE" -v run_id=t031-1 -f eval/queries/03-run-summary.sql
WITH scored AS (
    SELECT 'all' AS scope, m.key AS metric, m.value AS value
    FROM eval.eval_run r
    JOIN eval.eval_result s ON s.run_ref = r.id
    CROSS JOIN LATERAL jsonb_each(s.scores) AS m
    WHERE r.run_id = :'run_id'
    UNION ALL
    SELECT c.category AS scope, m.key AS metric, m.value AS value
    FROM eval.eval_run r
    JOIN eval.eval_result s ON s.run_ref = r.id
    JOIN eval.eval_case c ON c.id = s.case_ref
    CROSS JOIN LATERAL jsonb_each(s.scores) AS m
    WHERE r.run_id = :'run_id'
)
SELECT scope,
       metric,
       count(*) FILTER (WHERE jsonb_typeof(value) = 'number') AS n,
       count(*) FILTER (WHERE jsonb_typeof(value) = 'null') AS empty,
       round(avg((value #>> '{}')::numeric) FILTER (WHERE jsonb_typeof(value) = 'number'), 4) AS mean
FROM scored
GROUP BY scope, metric
ORDER BY (scope <> 'all'), scope, metric;
