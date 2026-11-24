-- 01 · Las ejecuciones guardadas, la más reciente primero.
-- Para qué: ver de un vistazo qué se midió, cuándo, con qué prompts y modelo, y cuánto dio la métrica principal de cada
--           tipo (fidelidad en calidad, MRR en recuperación) sobre el conjunto completo.
-- Parámetros: ninguno.
-- Cómo: psql "$DATABASE" -f eval/queries/01-list-runs.sql
SELECT r.run_id,
       r.kind,
       r.started_at,
       r.origin,
       r.assistant_model,
       (SELECT string_agg(p ->> 'name' || '=' || coalesce(p ->> 'version', 'default'), ', ' ORDER BY p ->> 'name')
          FROM jsonb_array_elements(r.prompts) AS p) AS prompts,
       r.cases_measured,
       (SELECT round(avg((s.scores ->> CASE r.kind WHEN 'QUALITY' THEN 'faithfulness' ELSE 'reciprocal_rank' END)::numeric), 4)
          FROM eval.eval_result s WHERE s.run_ref = r.id) AS headline_mean
FROM eval.eval_run r
ORDER BY r.started_at DESC, r.id DESC;
