# --- Build stage -------------------------------------------------------------------
# Runs `mvn package` with real Maven Central access (this is a plain container build,
# not the sandboxed environment that couldn't reach it) -- this is where the actual JAR
# gets built.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy just the POM first so dependency resolution is cached across rebuilds that only
# change source, not dependencies.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage -------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/lending-service-*.jar app.jar
USER app
EXPOSE 8080
# Container-friendly JVM defaults: respect the container's memory cgroup limits rather
# than the host's, since this runs under ECS/Fargate task memory limits in production.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
