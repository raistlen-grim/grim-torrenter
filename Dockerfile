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
WORKDIR /app
COPY --from=backend-build /build/grimtorrenter-app/target/quarkus-app/ ./

# 8080: the web UI/REST API (http).
EXPOSE 8080
# 6881: the BitTorrent listen port (grimtorrenter.listen-port) - both peer-wire (tcp,
# incoming connections - see design_docs/0038) and DHT (udp - see design_docs/0028) use
# this same port number. Deploy-time only (not user-editable via settings.json, see
# design_docs/0041) - override with -e grimtorrenter.listen-port=<port> if 6881 needs to
# map to something else; the container-side value only needs to match whatever -p/-p udp
# mapping is actually used, not this literal number.
EXPOSE 6881/tcp
EXPOSE 6881/udp

# Three independently mountable directories, all created automatically if missing:
#   grimtorrenter.download-directory (default ./downloads, relative to /app) - torrent data.
#   grimtorrenter.config-directory   (default ./config, relative to /app)    - settings.json,
#                                     the library event log (config-directory/events/, rolling
#                                     daily files - see design_docs/0055), and other small
#                                     persisted state (see design_docs/0041).
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
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
