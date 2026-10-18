# --- Build stage -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage -------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/lending-iam-service-*.jar app.jar
USER app
EXPOSE 8085
# 512 MB plan: keep the heap well below the container limit (metaspace, threads and the JIT need the rest).
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=60.0", "-XX:+UseSerialGC", "-Xss512k", "-jar", "app.jar"]
