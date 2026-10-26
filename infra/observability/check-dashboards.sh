#!/usr/bin/env bash
# Valida los dashboards de Grafana versionados (LAB-102). Uso: check-dashboards.sh [directorio]
# Por defecto revisa infra/observability/dashboards. Falla si:
#   - no hay ningun JSON, o alguno no es JSON valido;
#   - falta `uid` o `title`, o se repiten entre archivos;
#   - no declara la variable `datasource` (tipo datasource, consulta prometheus);
#   - algun panel o consulta fija el uid de un datasource concreto (debe usar la variable).
set -euo pipefail

DIR="${1:-$(dirname "${BASH_SOURCE[0]}")/dashboards}"
PYTHON="$(command -v python3 || command -v python || true)"
[ -n "$PYTHON" ] || { echo "falta python3 o python" >&2; exit 2; }

"$PYTHON" - "$DIR" <<'PY'
import json
import pathlib
import re
import sys

directory = pathlib.Path(sys.argv[1])
files = sorted(directory.glob("*.json"))
errors = []
seen_uid, seen_title = {}, {}
VARIABLE = re.compile(r"^\$\{?datasource\}?$")


def pinned_datasources(node, path):
    """Devuelve los datasource con uid fijo (distinto de la variable) bajo `node`."""
    found = []
    if isinstance(node, dict):
        ds = node.get("datasource")
        if isinstance(ds, dict) and "uid" in ds and not VARIABLE.match(str(ds["uid"])):
            found.append(f"{path}.datasource.uid={ds['uid']}")
        for key, value in node.items():
            found += pinned_datasources(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found += pinned_datasources(value, f"{path}[{index}]")
    return found


if not files:
    errors.append(f"{directory}: no hay dashboards (*.json)")

for file in files:
    try:
        dash = json.loads(file.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        errors.append(f"{file.name}: JSON invalido ({exc})")
        continue
    if not isinstance(dash, dict):
        errors.append(f"{file.name}: la raiz debe ser un objeto")
        continue
    for field, seen in (("uid", seen_uid), ("title", seen_title)):
        value = dash.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{file.name}: falta `{field}`")
        elif value in seen:
            errors.append(f"{file.name}: `{field}` repetido ({value!r}, ya en {seen[value]})")
        else:
            seen[value] = file.name
    variables = dash.get("templating", {}).get("list", [])
    if not any(v.get("name") == "datasource" and v.get("type") == "datasource" and v.get("query") == "prometheus"
               for v in variables):
        errors.append(f"{file.name}: falta la variable `datasource` (type datasource, query prometheus)")
    for pinned in pinned_datasources(dash, "$"):
        errors.append(f"{file.name}: datasource con uid fijo: {pinned}")

if errors:
    print("\n".join(f"ERROR {e}" for e in errors), file=sys.stderr)
    sys.exit(1)
print(f"OK {len(files)} dashboards validos en {directory}")
PY
