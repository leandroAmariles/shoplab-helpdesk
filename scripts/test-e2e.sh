#!/usr/bin/env bash
# scripts/test-e2e.sh - Smoke test e integracion E2E del sistema local (LAB-110)
set -eo pipefail

echo "=========================================================="
echo "  ShopLab Helpdesk - Verificacion E2E del Sistema Local   "
echo "=========================================================="

FAILED=0

check_endpoint() {
  local name="$1"
  local url="$2"
  local expected_pattern="$3"

  printf "Verificando %-20s (%s)... " "$name" "$url"
  response=$(curl -s --max-time 5 "$url" 2>/dev/null || true)

  if [[ -n "$expected_pattern" ]]; then
    if echo "$response" | grep -q "$expected_pattern"; then
      echo "✅ OK"
    else
      echo "❌ FALLO (respuesta: $response)"
      FAILED=1
    fi
  else
    if [[ -n "$response" ]]; then
      echo "✅ OK"
    else
      echo "❌ FALLO (sin respuesta)"
      FAILED=1
    fi
  fi
}

# 1. LiteLLM Gateway
check_endpoint "LiteLLM Gateway" "http://localhost:4000/health/liveliness" "healthy\|ok\|true\|alive"

# 2. Ingest Service
check_endpoint "Ingest Service" "http://localhost:8000/health" "ok\|healthy"

# 3. Backend Actuator Health
check_endpoint "Backend Health" "http://localhost:8081/actuator/health" "UP"
check_endpoint "Retrieval Health" "http://localhost:8082/actuator/health" "UP"

# 3b. Gateway (LAB-107): su salud y una lectura de la API a través de él
check_endpoint "Gateway Health" "http://localhost:8080/actuator/health" "UP"
check_endpoint "API por el gateway" "http://localhost:8080/api/documents" "\["

# 4. Frontend SPA
check_endpoint "Frontend Web" "http://localhost:4200" "<!DOCTYPE html>\|<app-root>\|html"

echo "=========================================================="
if [ $FAILED -eq 0 ]; then
  echo "  RESULTADO: Todos los servicios estan saludables (OK)    "
  echo "=========================================================="
  exit 0
else
  echo "  RESULTADO: Uno o mas servicios reportaron fallo         "
  echo "=========================================================="
  exit 1
fi
