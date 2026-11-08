FROM maven:3.10.0-eclipse-temurin-25-noble@sha256:f3784fd4e6e90b22a81cdd7967720143802989ac13696ae7d5d19ec95cabcc30 AS verification
WORKDIR /workspace
COPY mvnw ./
COPY .mvn .mvn
COPY experiments/mcp-stdio ./experiments/mcp-stdio
RUN ./mvnw --batch-mode --no-transfer-progress -f experiments/mcp-stdio/pom.xml verify

FROM eclipse-temurin:25.0.4.1_1-jre-ubi10-minimal@sha256:e961af01f4a1a3ec3ca71a3063739a33da943cf71734a8a747856d9385846544 AS runtime
WORKDIR /app
COPY --from=verification /workspace/experiments/mcp-stdio/target/mcp-stdio-0.1.0.jar adapter.jar
COPY --from=verification /workspace/experiments/mcp-stdio/target/dependency/ lib/
COPY experiments/mcp-stdio/SDK-LICENSE.txt /app/licenses/mcp-sdk.txt
USER 10001
HEALTHCHECK --interval=30s --timeout=15s --start-period=15s --retries=2 \
    CMD ["java", "-cp", "/app/adapter.jar:/app/lib/*", "com.alxarafe.erbas.mcp.Main", "--health"]
ENTRYPOINT ["java", "-cp", "/app/adapter.jar:/app/lib/*", "com.alxarafe.erbas.mcp.Main"]
