# Multi-stage Dockerfile for QueueLess Spring Boot Backend (Java 21)
# Optimized for Google Cloud Run (Mumbai asia-south1, 1 vCPU, 1 GiB RAM)
# Stage 1: Build backend JAR
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy backend pom and resolve dependencies
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy backend source code and build
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal hardened runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create dedicated non-root system user
RUN addgroup -S queueless && adduser -S queueless -G queueless

# Create data directory for local fallback storage (stateless in Cloud Run)
RUN mkdir -p /app/data && chown -R queueless:queueless /app

COPY --from=builder --chown=queueless:queueless /workspace/target/queueless-backend-1.0.0.jar app.jar

USER queueless

# Cloud Run injects $PORT (default 8080)
EXPOSE 8080

# Memory ergonomics: 75% max RAM (~768MB on 1 GiB instance), leaving headroom for metaspace & OS threads
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080
ENV SERVER_PORT=8080

# Container healthcheck probes actuator endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:${PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
