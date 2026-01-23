# ================================
# LIGHTWEIGHT JAVA CHAT DOCKERFILE
# ================================
# Memory-optimized for <512MB RAM usage
# Uses modern Alpine Linux + Eclipse Temurin JRE

# ================================
# BUILD STAGE - Maven + JDK
# ================================
# Use Amazon ECR Public mirror to avoid Docker Hub rate limits
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS builder

# Install Maven (Alpine package is lightweight)
RUN apk add --no-cache maven

# Set working directory
WORKDIR /app

# Copy Maven wrapper and pom.xml first (for better layer caching)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src/

# Build the application
RUN ./mvnw clean package -DskipTests -B

# ================================
# RUNTIME STAGE - JRE Only
# ================================
# Use Amazon ECR Public mirror to avoid Docker Hub rate limits
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine AS runtime

# Add labels for better container management
LABEL maintainer="Java Chat Team" \
      description="Lightweight Java Chat AI Application" \
      version="1.0"

# Install curl for health checks (minimal Alpine package)
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Set working directory
WORKDIR /app

# Copy the JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port (Railway will assign via PORT env var)
EXPOSE 8085

# Health check using PORT environment variable
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${PORT}/actuator/health || exit 1

# ================================
# JVM OPTIMIZATION FOR <512MB RAM
# ================================
# Memory settings optimized for container constraints (512MB limit):
# -Xmx192m: Max heap 192MB (shift budget to metaspace)
# -Xms64m:  Small initial heap for faster start and lower RSS
# -XX:MaxMetaspaceSize=192m: Allow more classes to prevent metaspace OOM
# -XX:ReservedCodeCacheSize=32m: Cap JIT code cache
# -XX:MaxDirectMemorySize=32m: Cap Netty/gRPC direct buffers
# -Xss256k: Smaller thread stacks
# -XX:+UseStringDeduplication: Reduce duplicate string overhead
# -Dreactor.schedulers.defaultBoundedElasticSize=32: Limit elastic threads
# -Dreactor.schedulers.defaultBoundedElasticQueueSize=256: Limit task queue
# -Dreactor.netty.ioWorkerCount=2: Fewer IO threads
# -Dio.netty.allocator.maxOrder=7: Smaller pooled chunks
# -XX:+ExitOnOutOfMemoryError: Fail fast
# ================================
# Use PORT environment variable (Railway assigns this)
# Default to 8085 if not set
ENV PORT=8085

# Disable Qdrant initialization for Railway (no vector DB needed for basic functionality)
ENV QDRANT_INIT_SCHEMA=false
ENV APP_LOCAL_EMBEDDING_ENABLED=false

# Enable hash-based fallback for graceful degradation when embeddings fail
ENV APP_LOCAL_EMBEDDING_USE_HASH_WHEN_DISABLED=true

# Disable aggressive port management in container environment
ENV APP_KILL_ON_CONFLICT=false

# JSON array format for ENTRYPOINT (recommended by Docker)
# Use shell form to allow PORT variable expansion
# Disable Netty native OpenSSL (tcnative) to avoid segfaults on Alpine/musl
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
  -Dio.netty.handler.ssl.noOpenSsl=true \
  -Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true \
  -jar app.jar --spring.main.banner-mode=off --spring.jmx.enabled=false --server.port=${PORT}"]

# ================================
# IMAGE SIZE OPTIMIZATION
# ================================
# Base image: eclipse-temurin:21-jre-alpine (~80MB)
# Alpine Linux: Minimal package manager, no bloat
# JRE only: ~100MB smaller than JDK
# Multi-stage: Build dependencies not in final image
# Total expected size: ~150-200MB
# ================================