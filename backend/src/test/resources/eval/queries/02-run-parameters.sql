-- 02 · Con qué se midió una ejecución.
-- Para qué: leer los parámetros de una ejecución: prompts y versiones, modelo del asistente, embeddings, juez,
--           biblioteca, configuración de recuperación, huella del dataset y commit de eval/.
-- Parámetros: run_id (el identificador de la ejecución).
-- Cómo: psql "$DATABASE" -v run_id=t031-1 -f eval/queries/02-run-parameters.sql
SELECT r.run_id,
       r.kind,
       r.started_at,
       r.finished_at,
       r.origin,
       r.commit_sha,
       r.dataset_hash,
       r.cases_measured,
       r.cases_excluded,
       r.assistant_model,
       r.embedding_model,
       r.prompts,
       r.judge,
       r.evaluator,
       r.retrieval_config
FROM eval.eval_run r
WHERE r.run_id = :'run_id';
