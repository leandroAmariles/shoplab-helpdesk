# syntax=docker/dockerfile:1
# Base images are pinned by digest; Dependabot (docker ecosystem) keeps them current.

FROM python:3.12-slim@sha256:a0f57064cbdcd6285fa45f51237ab0b35ea8ebaed06a1f5973e89e19dfb86704 AS build
COPY --from=ghcr.io/astral-sh/uv@sha256:32a7bb39b496babbd997b0a39a4d66c126bdaf5661b302bf6f06abda8deca6ff /uv /usr/local/bin/uv
WORKDIR /app
COPY . .
# Until the ingest service lands, build a placeholder page so the image is still publishable.
RUN if [ -f pyproject.toml ]; then \
      uv sync --frozen --no-dev; \
    else \
      mkdir -p /app/site \
      && echo '<!doctype html><title>ShopLab Helpdesk</title><p>ShopLab Helpdesk ingest: pending</p>' \
        > /app/site/index.html; \
    fi

FROM python:3.12-slim@sha256:a0f57064cbdcd6285fa45f51237ab0b35ea8ebaed06a1f5973e89e19dfb86704
ARG APP_MODULE=app.main:app
ENV APP_MODULE=${APP_MODULE} \
    PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1
# CVE-2026-109518 (HIGH, remote code execution via hard link reuse in apt) is fixed in apt and libapt-pkg7.0 3.0.4,
# and CVE-2026-76844 and CVE-2026-85091 (HIGH, zlib) in zlib1g 1:1.3.dfsg+really1.3.1-1+deb13u1, but none is in the
# pinned base digest yet (the latest published one);
# remove this upgrade once a Dependabot bump of the base image includes the fixes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 zlib1g \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid 10001 --no-create-home app
WORKDIR /app
COPY --from=build --chown=10001:10001 /app /app

USER 10001
EXPOSE 8000
CMD ["sh", "-c", "if [ -d .venv ]; then exec uvicorn \"$APP_MODULE\" --host 0.0.0.0 --port 8000; else exec python -m http.server 8000 --directory site; fi"]
