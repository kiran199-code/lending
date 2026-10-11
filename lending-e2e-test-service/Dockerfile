# Standalone image for the E2E lifecycle suite. Built from THIS directory only - it contains no lending-* code and is
# never part of a lending service image. Run it next to (not inside) the system under test.
#
#   docker build -t lending-e2e-test .
#   docker run --rm --env-file .env -v "$PWD/results:/results" lending-e2e-test run --profile=sit
#   docker run --rm --env-file .env -v "$PWD/results:/results" lending-e2e-test dry-run --profile=sit
#   docker run -d -p 8090:8090 -e E2E_SERVER_BIND=0.0.0.0 -e E2E_SERVER_TOKEN=... --env-file .env lending-e2e-test serve --profile=sit
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S e2e && adduser -S e2e -G e2e && mkdir /results && chown e2e:e2e /results
WORKDIR /app
COPY --from=build /build/target/lending-e2e-test.jar app.jar
USER e2e
ENV E2E_OUTPUT_DIR=/results
VOLUME /results
EXPOSE 8090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]
CMD ["serve"]
