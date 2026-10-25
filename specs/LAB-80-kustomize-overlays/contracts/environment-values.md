# Contrato: valores por ambiente

Valores de partida (ajustables en la implementación; la forma del contrato es lo que se valida). `req` = requests,
`lim` = limits.

## Réplicas y HPA

| Componente | dev | staging | prod |
|---|---|---|---|
| backend | HPA 1–2, CPU 75% | HPA 2–4, CPU 70% | HPA 3–8, CPU 70% |
| frontend | HPA 1–2, CPU 75% | HPA 2–3, CPU 70% | HPA 2–6, CPU 70% |
| ingest | HPA 1–2, CPU 75% | HPA 1–2, CPU 70% | HPA 2–4, CPU 70% |
| postgres | 1 (sin HPA) | 1 (sin HPA) | 1 (sin HPA) |
| ollama | 1 (sin HPA) | 1 (sin HPA) | 1 (sin HPA) |

En `staging` y `prod` los Deployments con HPA no fijan `replicas`.

## Recursos (por réplica)

| Componente | dev (req / lim) | staging (req / lim) | prod (req / lim) |
|---|---|---|---|
| backend | 250m, 512Mi / 1 CPU, 768Mi | 500m, 768Mi / 1 CPU, 1Gi | 1 CPU, 1Gi / 2 CPU, 2Gi |
| frontend | 50m, 64Mi / 200m, 128Mi | 100m, 128Mi / 300m, 256Mi | 200m, 256Mi / 500m, 512Mi |
| ingest | 100m, 256Mi / 500m, 512Mi | 250m, 512Mi / 1 CPU, 1Gi | 500m, 1Gi / 1 CPU, 2Gi |
| postgres | 250m, 512Mi / 1 CPU, 1Gi | 500m, 1Gi / 1 CPU, 2Gi | 1 CPU, 2Gi / 2 CPU, 4Gi |
| ollama | 500m, 2Gi / 2 CPU, 4Gi | 1 CPU, 4Gi / 4 CPU, 8Gi | 2 CPU, 6Gi / 6 CPU, 12Gi |

## Almacenamiento

| PVC | dev | staging | prod |
|---|---|---|---|
| postgres | 5Gi | 20Gi | 50Gi |
| ollama (modelos) | 10Gi | 20Gi | 40Gi |

## Configuración no secreta

| Clave | dev | staging | prod |
|---|---|---|---|
| Host del ingress | `shoplab.localtest.me` | `staging.shoplab.example` | `shoplab.example` |
| Nivel de log del backend | `DEBUG` | `INFO` | `INFO` |
| `OLLAMA_CHAT_MODEL` | `llama3.2:3b` | `llama3.2:3b` | `llama3.2:3b` |
| `OLLAMA_EMBED_MODEL` | `bge-m3` | `bge-m3` | `bge-m3` |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true` | `true` | `true` |
| `SPRING_AI_OLLAMA_BASE_URL` | `http://ollama:11434` | igual | igual |

## Garantías

- Cada valor de `prod` ≥ el de `staging` ≥ el de `dev` en réplicas mínimas, recursos y almacenamiento.
- Ningún valor de esta tabla es secreto; las credenciales viven solo en Secrets Manager.
