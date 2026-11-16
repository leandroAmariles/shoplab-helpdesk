#!/bin/sh
# Arranque del gateway (LAB-84): renderiza la plantilla de configuracion, avisa de los modelos locales que falten en
# Ollama (FR-008) y lanza LiteLLM. La configuracion se monta de solo lectura en /app/shoplab; lo renderizado va a /tmp.
set -eu

SHOPLAB_DIR="${SHOPLAB_DIR:-/app/shoplab}"
RENDERED="${RENDERED_CONFIG:-/tmp/litellm-config.yaml}"

python "$SHOPLAB_DIR/render_config.py" "$SHOPLAB_DIR/config.yaml" "$RENDERED"
python "$SHOPLAB_DIR/check_models.py" "$RENDERED" || true

export PYTHONPATH="$SHOPLAB_DIR${PYTHONPATH:+:$PYTHONPATH}"
exec litellm --config "$RENDERED" --port "${LITELLM_INTERNAL_PORT:-4000}"
