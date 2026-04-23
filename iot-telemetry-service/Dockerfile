# ─────────────────────────────────────────────────────────────────────────────
# SovereignNode AI — Java Ingestion Service Dockerfile
# Multi-stage build: Maven build → Temurin 21 JRE runtime
# ─────────────────────────────────────────────────────────────────────────────

# Stage 1: Build the Spring Boot fat JAR
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM first to cache dependency resolution layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B --no-transfer-progress

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -B --no-transfer-progress

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Minimal runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.output.ansi.enabled=ALWAYS"

# Copy the fat JAR from the build stage
COPY --from=builder /build/target/iot-telemetry-service-*.jar app.jar

# Non-root user
RUN addgroup -S sovnode && adduser -S -G sovnode sovnode
USER sovnode

# Actuator health check
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=60s \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
