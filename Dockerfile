# ================================
# JAVA CHAT DOCKERFILE
# ================================
# Multi-stage build for Frontend and Backend
# Switched to Debian/Ubuntu-based images to resolve Alpine network issues
# All images sourced from public.ecr.aws to avoid Docker Hub rate limits

# ================================
# FRONTEND BUILD STAGE
# ================================
FROM public.ecr.aws/docker/library/node:22-bookworm-slim AS frontend-builder
WORKDIR /app/frontend

# Copy dependency definitions
COPY frontend/package*.json ./
RUN npm ci

# Copy source files
COPY frontend/ .

# Build frontend (outputs to ../src/main/resources/static/)
# Favicons and static assets in frontend/public/ are automatically copied by Vite
RUN npm run build

# ================================
# BACKEND BUILD STAGE
# ================================
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy Gradle wrapper, build files, and static analysis configs
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY pmd-ruleset.xml spotbugs-exclude.xml spotbugs-include.xml ./
RUN chmod +x gradlew

# Download dependencies (quiet mode to avoid verbose tree output)
RUN ./gradlew dependencies --no-daemon -q

# Copy source code (excluding static assets which come from frontend build)
COPY src ./src/

# Copy built frontend assets (includes favicons + Svelte build) from frontend-builder
COPY --from=frontend-builder /app/src/main/resources/static ./src/main/resources/static/

# Build the application and copy bootable JAR to known location
# Gradle produces two JARs: bootable (Spring Boot) and -plain.jar (non-bootable)
RUN ./gradlew clean build -x test --no-daemon && \
    cp $(ls build/libs/*.jar | grep -v '\-plain\.jar' | head -n 1) build/app.jar

# ================================
# RUNTIME STAGE
# ================================
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre AS runtime

# Install curl for health check
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -u 1001 -m -s /bin/bash appuser

WORKDIR /app

# Copy the bootable JAR from builder stage
COPY --from=builder /app/build/app.jar app.jar

# Create logs directory and set permissions
RUN mkdir -p logs && chown -R appuser:appuser logs app.jar

USER appuser

EXPOSE 8085

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${PORT:-8085}/actuator/health || exit 1

# Environment variables
ENV PORT=8085
ENV QDRANT_INIT_SCHEMA=false
ENV APP_LOCAL_EMBEDDING_ENABLED=false
ENV APP_LOCAL_EMBEDDING_USE_HASH_WHEN_DISABLED=true
ENV APP_KILL_ON_CONFLICT=false

ENTRYPOINT ["/bin/sh", "-c", "java \
  -XX:+IgnoreUnrecognizedVMOptions \
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
