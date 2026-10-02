# Build stage: Maven and source code remain outside the runtime image.
FROM maven:3.10.0-eclipse-temurin-25-noble@sha256:f3784fd4e6e90b22a81cdd7967720143802989ac13696ae7d5d19ec95cabcc30 AS build

WORKDIR /workspace
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress clean verify

# Runtime stage: only the Java runtime, health-check utility, and artifact remain.
FROM eclipse-temurin:25.0.4.1_1-jre-ubi10-minimal@sha256:e961af01f4a1a3ec3ca71a3063739a33da943cf71734a8a747856d9385846544 AS runtime

WORKDIR /app
RUN microdnf install -y curl \
    && microdnf clean all \
    && rm -rf /var/cache/yum
COPY --from=build /workspace/target/erbas-0.1.0-SNAPSHOT.jar /app/erbas.jar

RUN chown -R 10001:0 /app
USER 10001

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD ["curl", "--fail", "--silent", "http://localhost:8080/actuator/health"]
ENTRYPOINT ["java", "-jar", "/app/erbas.jar"]
