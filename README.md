# ERBAS

ERBAS is a Java-based laboratory for designing and building an API-only ERP core.

## Current baseline

The repository currently contains only the reproducible Docker build baseline:

- Java 25;
- Spring Boot 4.1.1;
- Maven 3.10.0 through Maven Wrapper;
- JUnit context test;
- a multi-stage application image with a non-privileged runtime user;
- a Spring Boot Actuator health endpoint.

No business capability, persistence, authentication, authorization, PostgreSQL,
or Bruno API collection is included in this baseline.

## Host requirements

Only Git, Docker, Docker Compose, and Codex are required on the host. Java and
Maven are executed inside Docker.

## Build and test

Run the complete build and unit test suite through Docker Compose:

```bash
docker compose run --rm build
```

Build and start the application image:

```bash
docker compose up --build app
```

The application health endpoint is available at
`http://localhost:8080/actuator/health`.

## Documentation language

Repository comments and public documentation are written in English. Detailed
Spanish working documentation belongs under the local `private/` directory,
which is excluded from version control and Docker build context.
