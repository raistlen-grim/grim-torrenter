# syntax=docker/dockerfile:1

# ---- Frontend build ----
FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npx ng build --configuration production

# ---- Backend build ----
# Not build-verified - confirm a maven+eclipse-temurin image with JDK 25
# actually exists when you build; fall back to JDK 21 here and in the
# poms' maven.compiler.release if not.
FROM maven:3.9-eclipse-temurin-25 AS backend-build
WORKDIR /build
COPY pom.xml .
COPY grimtorrenter-engine/pom.xml grimtorrenter-engine/pom.xml
COPY grimtorrenter-app/pom.xml grimtorrenter-app/pom.xml
COPY grimtorrenter-engine/src grimtorrenter-engine/src
COPY grimtorrenter-app/src grimtorrenter-app/src
COPY --from=frontend-build /frontend/dist/frontend/browser grimtorrenter-app/src/main/resources/META-INF/resources
RUN mvn -B -pl grimtorrenter-app -am package -DskipTests

# ---- Runtime ----
FROM eclipse-temurin:25-jre-alpine AS runtime
# Ties the published package on ghcr.io to its repository (design_docs/0087).
LABEL org.opencontainers.image.source="https://github.com/raistlen-grim/grim-torrenter"
# su-exec: lets the entrypoint drop from root to PUID:PGID (see docker/entrypoint.sh).
RUN apk add --no-cache su-exec
WORKDIR /app
COPY --from=backend-build /build/grimtorrenter-app/target/quarkus-app/ ./
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 8080: the web UI/REST API (http).
EXPOSE 8080
# 6881: the BitTorrent listen port (grimtorrenter.listen-port) - both peer-wire (tcp,
# incoming connections - see design_docs/0038) and DHT (udp - see design_docs/0028) use
# this same port number. Deploy-time only (not user-editable via settings.json, see
# design_docs/0041) - override with -e GRIMTORRENTER_LISTEN_PORT=<port> if 6881 needs to
# map to something else; the container-side value only needs to match whatever -p/-p udp
# mapping is actually used, not this literal number.
EXPOSE 6881/tcp
EXPOSE 6881/udp

# Authentication (design_docs/0061) is off by default (Settings.authEnabled=false) - the
# REST API/WebSocket/web UI are all wide open until it's turned on. If you're exposing this
# container beyond your own LAN, turn it on: set an initial password via
# `-e grimtorrenter.bootstrap-password=<password>` on the container's first run (ignored on
# every later run once config-directory/auth.json already exists - change the password
# through the app itself after that, via the Settings page), then enable "Require a
# password" on the Settings page's Security group. Authentication alone does not protect
# against a network eavesdropper - a bearer token sent over plain HTTP can be captured and
# reused just like any other credential - so also put a TLS-terminating reverse proxy
# (Caddy, Traefik, nginx, ...) in front of this container before exposing it to the
# internet; this image does not terminate TLS itself.

# Three independently mountable directories, all created automatically if missing:
#   grimtorrenter.download-directory (default ./downloads, relative to /app) - torrent data.
#   grimtorrenter.config-directory   (default ./config, relative to /app)    - settings.json,
#                                     auth.json (the password hash, if one's been set - see
#                                     design_docs/0061), the library event log
#                                     (config-directory/events/, rolling daily files - see
#                                     design_docs/0055), and other small persisted state (see
#                                     design_docs/0041).
#   grimtorrenter.watch-directory    (default ./watch, relative to /app)     - the watch-folder
#                                     auto-add feature (design_docs/0056, off by default -
#                                     Settings.watchFolderEnabled). Drop a .torrent file here to
#                                     have it auto-added; watch-directory/added and
#                                     watch-directory/failed record the outcome, both pruned on
#                                     a configurable retention window.
# Bind-mount each to a separate host path (e.g. -v host/downloads:/app/downloads
# -v host/config:/app/config -v host/watch:/app/watch) to keep configuration, downloaded data,
# and watched files on separate volumes, matching how other self-hosted tools are typically
# deployed. Without a config-directory mount, the event log (like settings.json) is lost on
# every container recreate, not just a plain restart of the same container; without a
# watch-directory mount, there's nowhere outside the container to actually drop files into.
#
# PUID / PGID (both default 1000): the user and group id the app runs as, so files it creates
# in the mounted directories belong to a real user on the host rather than root. Set them to
# the output of `id -u` / `id -g` for the host user that owns the mounted directories. The
# container starts as root only long enough to take ownership of the config directory and drop
# to that user (docker/entrypoint.sh); it never changes ownership of downloads or watch, and
# warns at startup if it can't write to them. PUID=0 PGID=0 keeps the old run-as-root
# behaviour. See design_docs/0085.
ENV PUID=1000 PGID=1000
# Healthy while the app answers and can write to its config and downloads directories
# (GET /api/system/healthz - design_docs/0086). busybox wget exits non-zero on a 503.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/api/system/healthz || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
