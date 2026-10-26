# Contrato: alerta `BackendDown`

## Regla

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: backend
  labels:
    app.kubernetes.io/name: backend
spec:
  groups:
    - name: backend
      rules:
        - alert: BackendDown
          expr: |
            up{job="backend", namespace="shoplab-dev"} == 0
            or
            absent(up{job="backend", namespace="shoplab-dev"})
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: El backend de ShopLab no responde
            description: >-
              Prometheus no recoge métricas del backend en shoplab-dev desde hace más de 2 minutos.
              Revisa los pods del backend y el dashboard «ShopLab · Pods».
```

El archivo definitivo es `shoplab-infra/k8s/base/backend/prometheusrule.yaml` (con comentarios de cabecera); estas
expresiones y textos son los que prueba `rules-tests/backend-down.test.yaml`.

## Comportamiento esperado

| Situación | `up` | Resultado |
|---|---|---|
| Backend sano | `1` | Inactiva |
| Backend detenido o `/actuator/prometheus` no responde | `0` | Pendiente 2 min → disparada |
| Réplicas escaladas a 0 o `Deployment` borrado | serie ausente | `absent()` → pendiente 2 min → disparada |
| Despliegue progresivo (pod antiguo sirve hasta que el nuevo está listo) | `1` | Inactiva |
| Corte menor de 2 min | `0` o ausente, luego `1` | Pendiente → vuelve a inactiva, sin disparar |
| Backend vuelve a responder | `1` | Disparada → resuelta sola |

## Tiempos (SC-004)

Recogida 30 s + evaluación de reglas 30 s + `for: 2m` → disparada en ≈ 3 min (< 5 min). Resolución: un ciclo de
recogida y evaluación tras recuperarse (< 5 min).

## Alertmanager

- Habilitado por LAB-102 (research D7); sin receptores externos: la alerta se ve en la interfaz de Alertmanager y en
  `GET /api/v2/alerts`.
- La alerta llega con `alertname=BackendDown`, `severity=critical` y la descripción anterior.

## Pruebas (`shoplab-infra/rules-tests/backend-down.test.yaml`, `promtool test rules`)

La regla se extrae del `PrometheusRule` (`yq '.spec'`) y se prueba con series sintéticas:

| Caso | Serie de entrada | Esperado a los 3 min | Esperado a los 1 min |
|---|---|---|---|
| `up == 0` sostenido | `up{job="backend",namespace="shoplab-dev"} 0x10` | `BackendDown` disparada | pendiente, sin alerta |
| Serie ausente (0 réplicas) | solo una serie `up` de otro `job` | `BackendDown` disparada | pendiente, sin alerta |
| Corte breve | `0 0 1 1 1 1` | sin alerta | sin alerta |
| Backend sano | `1x10` | sin alerta | sin alerta |
| Caído en otro namespace, `shoplab-dev` sano | `up{…namespace="shoplab-dev"} 1x10` y `up{…namespace="otro"} 0x10` | sin alerta | sin alerta |
