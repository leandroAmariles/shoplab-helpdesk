## Description

<!-- What does this change do, and why? -->

## Related issue / task

LAB-

El título del PR será el mensaje del commit de squash en `main`: usa
`type(scope): [LAB-<n>] descripción`, por ejemplo `feat(chat): [LAB-12] respuesta en streaming`.

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Refactor (no functional change)
- [ ] Documentation
- [ ] Chore / tooling / infra

## How was this tested?

<!-- Steps to reproduce/verify, test commands run, coverage impact -->

## Token usage (if `.token-usage.json` exists)

<!-- Fill from <spec-folder>/.token-usage.json; delete this whole section if the file doesn't exist -->

## Checklist

- [ ] CI en verde
- [ ] Título del PR en formato Conventional Commits con la clave `[LAB-<n>]`
- [ ] Tests nuevos o actualizados y pasando
- [ ] La evaluación RAG no empeora (desde F2)
- [ ] Documentación o ADR actualizados si aplica
- [ ] Servicio nuevo (desde F4): incluye su base y su entrada en el overlay `dev` en `shoplab-infra/k8s/` (Definition of Done)
- [ ] Sin violaciones de lint/style/políticas
- [ ] Auto-revisión de diff realizada

## Compatibility / breaking changes

<!-- Anything consumers, other services, or downstream teams need to know -->

## Rollback plan

<!-- How to revert this safely if it causes issues after release -->
