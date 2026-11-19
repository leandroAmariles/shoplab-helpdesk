#!/bin/sh
# Starts the Ollama server, pulls the configured chat and embedding models (and the optional fallback model of the
# gateway) when missing and keeps the server in the foreground. The compose healthcheck only passes once they are listed.
set -eu

: "${OLLAMA_CHAT_MODEL:?OLLAMA_CHAT_MODEL is required}"
: "${OLLAMA_EMBED_MODEL:?OLLAMA_EMBED_MODEL is required}"

ollama serve &
server_pid=$!

until ollama list >/dev/null 2>&1; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "ollama server exited before becoming ready" >&2
    exit 1
  fi
  sleep 2
done

# The gateway's fallback model (LAB-84) is only downloaded when it is defined: with the variable empty nothing extra is
# pulled, so small machines do not pay for a second model they do not use.
models="$OLLAMA_CHAT_MODEL $OLLAMA_EMBED_MODEL"
if [ -n "${OLLAMA_FALLBACK_MODEL:-}" ]; then
  models="$models $OLLAMA_FALLBACK_MODEL"
fi
# The judge of the answer quality evaluation (LAB-29) is a bigger model (qwen2.5:7b, ~4.7 GB), so it is also opt-in: only
# machines that run the evaluation download it. The gateway uses the same model name by default.
if [ -n "${OLLAMA_JUDGE_MODEL:-}" ]; then
  models="$models $OLLAMA_JUDGE_MODEL"
fi

for model in $models; do
  if ollama list | grep -qF "$model"; then
    echo "model $model already present"
  else
    echo "pulling model $model"
    ollama pull "$model"
  fi
done

wait "$server_pid"
