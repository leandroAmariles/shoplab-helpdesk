#!/usr/bin/env bash
# Captura (--record) o compara contra la línea base las respuestas HTTP del historial de evaluación (LAB-130, FR-026).
# Uso:
#   bash scripts/compare-eval-baseline.sh --record http://localhost:8081 specs/LAB-130-eval-service-extraction/baseline/monolith
#   bash scripts/compare-eval-baseline.sh http://localhost:8084 specs/LAB-130-eval-service-extraction/baseline/monolith
set -euo pipefail

mode="compare"
if [ "${1:-}" = "--record" ]; then
  mode="record"
  shift
fi

base_url="${1:?uso: compare-eval-baseline.sh [--record] <base_url> [baseline_dir]}"
baseline_dir="${2:-specs/LAB-130-eval-service-extraction/baseline/monolith}"
base_url="${base_url%/}"

normalize() {
  # Normaliza únicamente trace_url si cambia la plantilla entre entornos; todo lo demás debe coincidir byte a byte.
  sed -E 's/"trace_url":"[^"]*"/"trace_url":"<TRACE_URL>"/g'
}

fetch() {
  curl -fsS -H "Accept: application/json" "${base_url}$1" | normalize
}

mkdir -p "$baseline_dir"

endpoints=(
  "runs-quality.json|/api/eval/history/runs?kind=QUALITY"
  "runs-retrieval.json|/api/eval/history/runs?kind=RETRIEVAL"
)

if [ "$mode" = "record" ]; then
  for entry in "${endpoints[@]}"; do
    file="${entry%%|*}"; path="${entry#*|}"
    fetch "$path" > "$baseline_dir/$file"
    echo "grabado $baseline_dir/$file"
  done
  # Si hay corridas de calidad o recuperación, grabar detalle y comparación de las dos primeras de un mismo tipo.
  if [ -f "$baseline_dir/ids.txt" ]; then
    while IFS='|' read -r kind id1 id2 metric; do
      [ -n "$id1" ] || continue
      fetch "/api/eval/history/runs/$id1" > "$baseline_dir/detail-$id1.json"
      echo "grabado $baseline_dir/detail-$id1.json"
      if [ -n "${id2:-}" ] && [ -n "${metric:-}" ]; then
        fetch "/api/eval/history/compare?base=$id1&target=$id2&metric=$metric" > "$baseline_dir/compare-$id1-$id2.json"
        echo "grabado $baseline_dir/compare-$id1-$id2.json"
      fi
    done < <(tr -d '\r' < "$baseline_dir/ids.txt")
  fi
  exit 0
fi

status=0
check_one() {
  local file="$1" path="$2" actual
  actual="$(fetch "$path")"
  if [ "$actual" = "$(cat "$baseline_dir/$file")" ]; then
    printf 'IGUAL     %-32s %s\n' "$file" "$path"
  else
    printf 'DISTINTO  %-32s %s\n' "$file" "$path" >&2
    status=1
  fi
}

for entry in "${endpoints[@]}"; do
  file="${entry%%|*}"; path="${entry#*|}"
  [ -f "$baseline_dir/$file" ] || { echo "falta $baseline_dir/$file" >&2; exit 2; }
  check_one "$file" "$path"
done

if [ -f "$baseline_dir/ids.txt" ]; then
  while IFS='|' read -r kind id1 id2 metric; do
    [ -n "$id1" ] || continue
    check_one "detail-$id1.json" "/api/eval/history/runs/$id1"
    if [ -n "${id2:-}" ] && [ -n "${metric:-}" ]; then
      check_one "compare-$id1-$id2.json" "/api/eval/history/compare?base=$id1&target=$id2&metric=$metric"
    fi
  done < <(tr -d '\r' < "$baseline_dir/ids.txt")
fi

[ "$status" -eq 0 ] && echo "0 diferencias frente a la línea base" || echo "HAY DIFERENCIAS frente a la línea base" >&2
exit "$status"
