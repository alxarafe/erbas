# Usage

## Requirements and versions

Use Bash, Git, Docker with Compose, and basic shell utilities including GNU
`timeout`, `mktemp`, `sed` and `grep`. The Docker daemon must be available.
The lifecycle scripts resolve the repository root from any working directory;
other examples below assume the Java repository root. Java, Maven, PostgreSQL, Node.js and
Bruno run inside Docker, never on the host.

Existing tools remain Java 25, Spring Boot 4.1.1, Maven 3.10.0 and Wrapper 3.3.4.
No dependency or existing image pin changes in this task.

| Image | Pinned digest |
| --- | --- |
| `maven:3.10.0-eclipse-temurin-25-noble` | `sha256:f3784fd4e6e90b22a81cdd7967720143802989ac13696ae7d5d19ec95cabcc30` |
| `eclipse-temurin:25.0.4.1_1-jre-ubi10-minimal` | `sha256:e961af01f4a1a3ec3ca71a3063739a33da943cf71734a8a747856d9385846544` |
| `postgres:18.6` | `sha256:86c951e05bf56c93d95d397747fb8820ac76cc3bedb78f43abd83eedbe3666ae` |

The selected contract supplies Bruno CLI 4.2.0 and Redocly CLI 2.5.1 in its own
pinned runner. No collection or tooling is copied into Java.

## Development

```bash
./bin/up
./bin/down
ERBAS_JAVA_PORT=49080 ./bin/up
```

Development uses `compose.yaml`, a persistent PostgreSQL volume and container
port 8080 published at `127.0.0.1:48080`. Configure `ERBAS_JAVA_PORT` to change
the host port. PostgreSQL has no host port. Development defaults are database
`erbas_dev`, user `erbas`, password `erbas_local_only`; override them with
`ERBAS_DB_NAME`, `ERBAS_DB_USER`, `ERBAS_DB_PASSWORD`. They are local defaults,
not production credentials. `bin/down` removes development containers and the
Compose network, preserving the named PostgreSQL volume and its data. It is safe
to repeat when services are already stopped and accepts no volume-removal option.

`bin/up` validates Compose, builds only the application target, and starts only
`postgres` and `app` in the background. Compose waits up to 120 seconds for their
existing healthchecks (including PostgreSQL readiness and Spring Actuator).
The script then waits up to 60 seconds for the published `/health` endpoint:
HTTP 200, `application/json` and the literal object `{"status":"ok"}` (JSON
whitespace and media-type parameters are accepted). Other JSON properties or
values are rejected. A noncontractual HTTP 200 fails immediately. Startup,
build, Docker and probe failures exit nonzero; failed startup leaves resources
available for diagnosis and does not delete data. Builds have a 900-second
watchdog; each probe has bounded Docker and HTTP timeouts. Readiness polling can
exceed its deadline by one bounded probe.

The published endpoint probe reuses curl already installed in the application
runtime image through a short-lived container with host networking. This
development workflow requires Linux Docker Engine host networking; it needs no
host curl, Java or JSON tool and introduces no extra image dependency. Docker
Desktop requires host networking support to be explicitly enabled.

These scripts are the repository-owned lifecycle interface for external
development orchestrators. They do not run complete isolated validation;
`bin/check` remains its sole authority. For only native build/tests, use
`docker compose run --rm build`.

The health URL is `http://127.0.0.1:48080/health`. For example,
`ERBAS_JAVA_PORT=49080 ./bin/up` publishes the same internal
8080 on loopback host port 49080. `ERBAS_APP_PORT` is no longer used. See the
[shared port convention](https://github.com/alxarafe/erbas-contract/blob/main/docs/development-ports.md).
The CI overlay `docker/compose.ci.yaml` removes the app's host publication;
CI and complete isolated validation access services inside Docker.

To query Health without installing host curl, while the default environment runs:

```bash
docker run --rm --network host --entrypoint curl "$(docker compose images --quiet app)" \
  --fail --silent --show-error --noproxy '*' -H 'Accept: application/json' \
  http://127.0.0.1:48080/health
```

Use port 49080 in that URL after the override example. Alternatively, if curl
is already installed on the host, run `curl http://127.0.0.1:48080/health`.
Stop with `./bin/down` (use the same Compose project/environment configuration
as startup). No database port is published by either lifecycle command.

Flyway reads `src/main/resources/db/migration` at startup. V1 creates only
`erbas_persistence_marker` and inserts marker 1. No business schema, ORM, JPA or
Hibernate is present. The multi-stage runtime runs as user 10001; Compose uses
a read-only filesystem and temporary `/tmp`.

`GET /health` is public process liveness, independent of dependencies after
startup. `/actuator/health` retains Spring's operational meaning and representation.

## Complete validation

```bash
ERBAS_CONTRACT_DIR=/path/to/checkout/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check
```

`bin/check` is the sole complete validation entry point. Supply the root of
`alxarafe/erbas-contract` at the exact commit in `contract.revision`. This is an
unpublished revision, not a released version. There is no sibling default,
automatic download, pull or checkout. Missing opt-in, wrong origin/revision,
staged/unstaged tracked changes or nonignored untracked files are rejected before
resources are created. Ignored files are allowed and neither changed nor deleted.
Revision and cleanliness are rechecked immediately before each contract call.

The check validates Compose, builds the unchanged Dockerfile and executes the
regular native suite in a fresh container even with cached layers. Two fresh
PostgreSQL instances must have empty public schemas. Maven explicitly selects
`HealthIntegrationIT` against its own PostgreSQL, with real Flyway and JDBC.
The existing context test and new MVC test remain in the regular native suite.

Java starts against the separate API database. Bounded health waiting, real
Actuator HTTP and SQL assertions verify migration success and marker 1. The
script then invokes the existing contract interface with its generated network:

```bash
"$ERBAS_CONTRACT_DIR/bin/test" --network "$validation_network" http://app:8080
```

This illustrates the internal call, not another complete validation entry point.
The same collection runs again after only API PostgreSQL is stopped, proving
HTTP liveness does not promise database availability.

## Isolation, timeouts and cleanup

`docker/compose.validation.yaml` is standalone; development dotenv configuration
is not loaded. Each invocation has a unique project, network, image tags and two
volumes. No ports, development paths, external resources or Docker socket mounts
are used. Native integration and Bruno/API tests use separate PostgreSQL storage.

| Stage | Limit |
| --- | --- |
| Each build or Maven execution | 900 seconds |
| Database/application startup | 120 seconds |
| Each contract command, including runner build | 600 seconds |
| Compose teardown | 90 seconds |
| Docker ownership/inventory operation | 15 seconds |

Contract limits remain HTTP 2 seconds, Bruno 10 and lint 30. Host watchdogs allow
10 seconds after TERM before forcing termination. Startup polling may exceed
its deadline by one bounded inspect operation.

All assertions, tool errors, timeouts and cleanup failures return nonzero. Usage
and preflight errors return 2; watchdog/startup timeout 124; signals 130/143.
Other failures retain their status unless cleanup fails.

EXIT/INT/TERM traps verify exact ownership labels before removing only the run's
containers, network, volumes and image tags. No prune or broad deletion is used.
Shared local image layers and build cache may remain; nothing is published.
Success is printed only after empty resource inventories. SIGKILL or an unavailable
daemon cannot guarantee cleanup: retain the run identity for scoped recovery.

## Orchestrator verification

```bash
ERBAS_CONTRACT_DIR=/path/to/checkout/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./tests/check-lifecycle.sh
```

The harness calls `bin/check` and Docker's public inventory interfaces, without
another validation implementation. It expects success, startup timeout, and
contractual connection failure, checking cleanup each time. Logs remain in the
printed temporary evidence directory.

`bin/check --exercise-failure startup-timeout` pauses only its ephemeral app
and uses a 5-second wait; `--exercise-failure contract` stops only that app before
shared Bruno. Both must fail. These explicit host-only test controls never change
application behavior and are absent from the runtime image.

Run concurrency deliberately, not during ordinary `bin/check`:

```bash
ERBAS_CONTRACT_DIR=/path/to/checkout/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./tests/check-lifecycle.sh concurrent
```

Existing CI retains its original controls and does not yet invoke shared Bruno.
CONTRACT-002 owns mandatory CI integration and published-version consumption.
Local success does not authorize publication or deployment.
