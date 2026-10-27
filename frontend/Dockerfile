# syntax=docker/dockerfile:1
# Base images are pinned by digest; Dependabot (docker ecosystem) keeps them current.

FROM node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 AS build
WORKDIR /workspace
COPY . .
RUN npm ci && npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
USER root
# CVE-2026-111497 (HIGH) is fixed in brotli-libs 1.2.1-r0 and CVE-2026-92392 (HIGH, HTTP/2 upload use-after-free)
# in curl/libcurl 8.23.0-r0, but neither is in the pinned base digest yet;
# remove these upgrades once a Dependabot bump of the base image includes the fixes.
RUN apk upgrade --no-cache brotli-libs curl libcurl
COPY --chown=101:101 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/dist/browser /usr/share/nginx/html
USER 101
EXPOSE 8080
