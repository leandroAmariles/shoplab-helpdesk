# syntax=docker/dockerfile:1
# Base images are pinned by digest; Dependabot (docker ecosystem) keeps them current.

FROM node:25-alpine@sha256:bdf2cca6fe3dabd014ea60163eca3f0f7015fbd5c7ee1b0e9ccb4ced6eb02ef4 AS build
WORKDIR /workspace
COPY . .
# Until the Angular app lands, build a placeholder page so the image is still publishable.
# The output folder (dist/browser) is confirmed in the frontend story.
RUN if [ -f package.json ]; then \
      npm ci && npm run build; \
    else \
      mkdir -p dist/browser \
      && echo '<!doctype html><title>ShopLab Helpdesk</title><p>ShopLab Helpdesk frontend: pending</p>' \
        > dist/browser/index.html; \
    fi

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
USER root
# CVE-2026-111497 (HIGH) is fixed in brotli-libs 1.2.1-r0 but not yet in the pinned base digest;
# remove this upgrade once a Dependabot bump of the base image includes the fix.
RUN apk upgrade --no-cache brotli-libs
COPY --from=build --chown=101:101 /workspace/dist/browser /usr/share/nginx/html
USER 101
EXPOSE 8080
