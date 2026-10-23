#!/bin/sh
# Starts the Ollama server, pulls the configured chat and embedding models when missing and keeps
# the server in the foreground. The compose healthcheck only passes once both models are listed.
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

for model in "$OLLAMA_CHAT_MODEL" "$OLLAMA_EMBED_MODEL"; do
  if ollama list | grep -qF "$model"; then
    echo "model $model already present"
  else
    echo "pulling model $model"
    ollama pull "$model"
  fi
done

wait "$server_pid"
