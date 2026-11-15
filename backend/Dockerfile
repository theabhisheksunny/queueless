# Multi-stage Dockerfile for QueueLess Spring Boot Backend (Java 21)
# Stage 1: Build backend JAR
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy backend pom and resolve dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy backend source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal hardened runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create dedicated non-root system user
RUN addgroup -S queueless && adduser -S queueless -G queueless

# Create data directory for persistence when running in standalone mode
RUN mkdir -p /app/data && chown -R queueless:queueless /app

COPY --from=builder --chown=queueless:queueless /workspace/target/queueless-backend-1.0.0.jar app.jar

USER queueless

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SERVER_PORT=8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/api/public/queue/vapid-public-key || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
