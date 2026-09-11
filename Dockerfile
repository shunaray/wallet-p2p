# Multi-stage Dockerfile for Wallet & P2P Transfer Microservice
# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Cache maven dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Production Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install curl/wget for healthcheck
RUN apk add --no-cache wget

# Create a dedicated non-root user and group
RUN addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring

# Copy compiled jar from builder stage
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring:spring

EXPOSE 8080

# Production Healthcheck using Actuator probe
HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
