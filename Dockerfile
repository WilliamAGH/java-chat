# syntax=docker/dockerfile:1

# ================================
# JAVA CHAT DOCKERFILE
# ================================
# Multi-stage build for Frontend and Backend
# Switched to Debian/Ubuntu-based images to resolve Alpine network issues
# Node is sourced from public.ecr.aws; Liberica Java images come from BellSoft's Docker Hub repositories
# Note: Requires DOCKER_BUILDKIT=1 for cache mount support

# ================================
# FRONTEND BUILD STAGE
# ================================
FROM public.ecr.aws/docker/library/node:24.15.0-bookworm-slim AS frontend-builder
WORKDIR /app/frontend

# Copy dependency definitions first for cache layer
COPY frontend/package*.json ./

# Install with cache mount
RUN --mount=type=cache,target=/root/.npm \
    npm ci

# Copy source files, validate, test, and build
COPY frontend/ .
COPY .gitignore /app/.ignore
COPY Dockerfile /app/Dockerfile
COPY docs/getting-started.md /app/docs/getting-started.md
COPY src/main/resources/enrichment-kinds.manifest /app/src/main/resources/enrichment-kinds.manifest
COPY src/main/resources/sse-status-contracts.json /app/src/main/resources/sse-status-contracts.json
RUN npm run validate && npm run test && npm run build

# ================================
# BACKEND BUILD STAGE
# ================================
FROM bellsoft/liberica-openjdk-debian:25.0.3-11 AS builder
ARG SOURCE_COMMIT=unknown
WORKDIR /app

# 1. Gradle wrapper (rarely changes)
COPY gradlew .
COPY gradle/ gradle/
RUN chmod +x gradlew

# 2. Build configuration (changes occasionally)
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY config/pmd/ config/pmd/
COPY config/spotbugs/ config/spotbugs/

# 3. Download dependencies with cache mount (redirect to /dev/null to avoid massive logs)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# 4. Copy source code (excluding static assets which come from frontend build)
COPY src ./src/

# 5. Copy built frontend assets (includes favicons + Svelte build)
COPY --from=frontend-builder /app/src/main/resources/static ./src/main/resources/static/

# 6. Build application with cache mount
RUN --mount=type=cache,target=/root/.gradle \
    SOURCE_COMMIT="${SOURCE_COMMIT}" ./gradlew clean build -x test --no-daemon && \
    cp $(ls build/libs/*.jar | grep -v '\-plain\.jar' | head -n 1) build/app.jar

# ================================
# RUNTIME STAGE
# ================================
FROM bellsoft/liberica-openjre-debian:25.0.3-11 AS runtime
ARG SOURCE_COMMIT=unknown
LABEL io.iocloudhost.logs.owner=split

# 1. System packages (never changes) - FIRST for maximum cache reuse
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 2. Create non-root user (never changes)
RUN useradd -u 1001 -m -s /bin/bash appuser

WORKDIR /app

# 3. Create writable data directories (rarely changes)
RUN mkdir -p logs /app/data/snapshots /app/data/parsed /app/data/index

# 4. Environment variables (rarely changes)
ENV PORT=8085
ENV QDRANT_INIT_SCHEMA=false
ENV APP_LOCAL_EMBEDDING_ENABLED=false
ENV APP_KILL_ON_CONFLICT=false
ENV DOCS_SNAPSHOT_DIR=/app/data/snapshots
ENV DOCS_PARSED_DIR=/app/data/parsed
ENV DOCS_INDEX_DIR=/app/data/index
ENV SOURCE_COMMIT=${SOURCE_COMMIT}

# 5. Application JAR (changes every build) - LAST for optimal caching
COPY --from=builder /app/build/app.jar app.jar

# 6. Finalize permissions
RUN chown -R appuser:appuser logs /app/data app.jar
USER appuser

EXPOSE 8085

# Gate Coolify's rolling cutover on the JVM accepting traffic; external dependencies report separately.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:${PORT:-8085}/actuator/health/readiness || exit 1

ENTRYPOINT ["/bin/sh", "-c", "exec java \
  -XX:+IgnoreUnrecognizedVMOptions \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Xms64m -Xmx192m \
  -XX:MaxMetaspaceSize=192m \
  -XX:ReservedCodeCacheSize=32m \
  -XX:MaxDirectMemorySize=32m \
  -Xss256k \
  -XX:+UseStringDeduplication \
  -XX:+ExitOnOutOfMemoryError \
  -Dreactor.schedulers.defaultBoundedElasticSize=32 \
  -Dreactor.schedulers.defaultBoundedElasticQueueSize=256 \
  -Dreactor.netty.ioWorkerCount=2 \
  -Dio.netty.allocator.maxOrder=7 \
  -Djava.security.egd=file:/dev/./urandom \
  -jar app.jar --spring.main.banner-mode=off --spring.jmx.enabled=false --server.port=${PORT}"]
