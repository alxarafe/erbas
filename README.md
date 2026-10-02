# ERBAS

[![CI](https://github.com/alxarafe/erbas/actions/workflows/ci.yml/badge.svg)](https://github.com/alxarafe/erbas/actions/workflows/ci.yml)
![Java 25](https://img.shields.io/badge/Java-25-orange?style=flat-square)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square)
![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square)

ERBAS is a Java-based laboratory for designing and building an API-only ERP core.

## Current baseline

The repository currently contains the reproducible Docker baseline and a
minimal PostgreSQL persistence foundation:

- Java 25;
- Spring Boot 4.1.1;
- Maven 3.10.0 through Maven Wrapper;
- JUnit context test;
- a multi-stage application image with a non-privileged runtime user;
- a Spring Boot Actuator health endpoint;
- PostgreSQL 18.6 started by Docker Compose;
- JDBC connectivity and Flyway versioned migrations;
- a minimal infrastructure-only migration marker.

No ERP business schema, ORM, JPA, Hibernate, authentication, authorization, or
Bruno API collection is included in this baseline.

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

The `app` service waits for the PostgreSQL health check before starting. The
default local database uses the `erbas_dev` database, the `erbas` user, and the
`erbas_local_only` password. These values are local development defaults only,
not production credentials. Override them with `ERBAS_DB_NAME`,
`ERBAS_DB_USER`, and `ERBAS_DB_PASSWORD` when needed. The application is
published on host port 8080 by default; override it with `ERBAS_APP_PORT` if
that port is already in use.

Flyway applies versioned migrations from
`src/main/resources/db/migration` when the application starts. The current
`V1__create_persistence_marker.sql` migration creates only the infrastructure
table `erbas_persistence_marker`; no ERP business tables exist yet.

To verify a fresh PostgreSQL database, use a dedicated Compose project name and
remove only that project's containers, network, and volume after verification:

```bash
export ERBAS_APP_PORT=48080
docker compose -p erbas-task2-verify up --detach --build app
docker compose -p erbas-task2-verify exec -T app \
  sh -c 'until curl --fail --silent http://localhost:8080/actuator/health; do sleep 2; done'
docker compose -p erbas-task2-verify exec -T postgres \
  psql -U erbas -d erbas_dev -v ON_ERROR_STOP=1 \
  -c "SELECT version, success FROM flyway_schema_history WHERE version = '1' AND success;"
docker compose -p erbas-task2-verify down --volumes --remove-orphans
```

The verification project name is explicit so its cleanup cannot target the
development Compose project. The regular `docker compose up` workflow keeps
the local PostgreSQL volume for development.

The application health endpoint is available at
`http://localhost:8080/actuator/health`.
