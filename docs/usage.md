# Usage

## Requirements and versions

Use Bash, Git, Docker with Compose, and basic shell utilities including GNU
`timeout`, `mktemp`, `sed`, `grep` and `od`. The Docker daemon must be available.
The lifecycle scripts resolve the repository root from any working directory;
other examples below assume the Java repository root. Java, Maven, PostgreSQL, Node.js and
Bruno run inside Docker, never on the host.

Existing tools remain Java 25, Spring Boot 4.1.1, Maven 3.10.0 and Wrapper 3.3.4.
Spring Boot manages the added `spring-boot-starter-security` dependency (4.1.1),
including Spring Security 7.1.1. Existing image pins remain unchanged.

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

Flyway reads `src/main/resources/db/migration` at startup. V1 creates
`erbas_persistence_marker` and inserts marker 1. V2 adds the minimal authentication
persistence (`auth_user` and `auth_access_token`) without seeding any users or
tokens. V3 adds non-null `admin`, defaulting existing users to false. Login and
stateless bearer authentication are implemented. Account
bootstrap is explicit and runs after migrations; it is not part of Flyway.
No ORM, JPA or Hibernate is present.
The multi-stage runtime runs as user 10001; Compose uses
a read-only filesystem and temporary `/tmp`.

`GET /health` is public process liveness, independent of dependencies after
startup. `/actuator/health` retains Spring's operational meaning and representation.

## Authentication

`POST /api/auth/login` implements the closed JSON exchange defined by shared
AUTH-001. Password encoding uses native Spring Security PBKDF2-HMAC-SHA256
(600,000 iterations, 16-byte random salt), preserving long passwords unchanged.
Tokens contain 256 random bits encoded as Base64 URL-safe without padding;
only their SHA-256 digest is stored. The internal application setting
`erbas.auth.access-token-ttl` defaults to `PT1H`, with environment override
`ERBAS_AUTH_ACCESS_TOKEN_TTL` and an allowed internal range of 1 second to 30 days.
TTL is not returned by login. Other resources require authentication; the
public operational liveness/readiness probes remain accessible.

Use the returned token unchanged as `Authorization: Bearer <accessToken>`
against the issuing Java backend. It is opaque, not JWT; Java and .NET tokens
are not interoperable. The contract lives exclusively in `erbas-contract`.
No registration, logout, refresh, roles or business permissions are implemented.
See [AUTH-003 verification](verification/auth-003.md).

Each protected request resolves current `id`, `email`, `enabled` and `admin`
from PostgreSQL. Disabling a user rejects existing bearers without deleting
tokens; admin changes update the Spring Security principal and `ADMIN` authority
on the next request. Java implements USERS-001 and COLLECTIONS-001 against the pinned draft 0.4.0.

### Basic user administration

`GET /api/auth/me` returns the already authenticated identity. The public user
object has exactly `id` (opaque string), `email`, `enabled` and `admin`.
`GET /api/users`, `GET /api/users/{id}`, `POST /api/users` and
`PATCH /api/users/{id}` require the central Spring Security `ADMIN` authority.
All these resources return JSON with `Cache-Control: no-store`, including errors.

The user list returns exactly `items`, `offset`, `limit`, `total` and `order`.
Optional `offset` defaults to 0 and must be a nonnegative integer. Optional
`limit` defaults to 50 and must be an integer from 1 to 100. Invalid values
return `400 invalid_request` after authentication and administrator authorization,
without clamping. The server orders by id ASC before applying the window and
reports `order: [{"field":"id","direction":"asc"}]`; IDs remain opaque.
`total` counts all users, including disabled accounts, before pagination, while
`limit` remains the requested capacity. Empty or beyond-total windows return
200 with `items: []` and complete metadata. No custom ordering or filters exist.
See [COLLECTIONS-001 evidence](verification/collections-001.md).

Creation takes exactly required `email`, `password` and Boolean `admin`, and
always enables the account. Email is a nonempty string without normalization;
exact duplicates conflict. Passwords contain 12–256 Unicode code points, without
trimming or composition rules, and use the existing PBKDF2 encoder. PATCH accepts
only one or both Boolean fields `enabled` and `admin`. It cannot change email
or passwords. There is no DELETE, registration, password management,
search, roles or granular permission system.

Closed errors are `400 invalid_request`, protected `401 unauthorized`
(with `WWW-Authenticate: Bearer`), `403 forbidden` (without that header),
`404 user_not_found`, `409 email_conflict` and `409 last_admin`.
Administrative authorization occurs before lookup. Unusable Java IDs also
return `user_not_found`, without parsing details. Login retains AUTH-001 errors.

The last enabled admin cannot be disabled or demoted. Self-disable/demotion is
allowed when another enabled admin remains; the current update succeeds and
subsequent bearer requests reflect the change. State updates reuse Task 2A's
serialized PostgreSQL transaction. See [verification](verification/users-001.md)
and the [shared contract](https://github.com/alxarafe/erbas-contract/blob/42e0c5ad81902a355fe01d6635466717f46b9dfd/docs/users-001.md).

### Local login account

The development Compose sets the `development` profile and forwards optional
bootstrap variables. Bootstrap is disabled by default. For an explicit local
demo account, start with:

```bash
ERBAS_AUTH_BOOTSTRAP_ENABLED=true \
ERBAS_AUTH_BOOTSTRAP_EMAIL=local@example.test \
ERBAS_AUTH_BOOTSTRAP_PASSWORD=local-demo-only \
./bin/up
```

These are **local/demo-only credentials**, never production values. Set your own
local email/password when needed. `ERBAS_JAVA_PORT` remains configurable and can
be combined with these variables. No contract checkout or manual SQL is needed.
For the literal demo values above, this Docker-only probe verifies login without
printing the token:

```bash
printf '%s' '{"email":"local@example.test","password":"local-demo-only"}' |
  docker compose exec -T app curl --silent --show-error --output /dev/null \
    --write-out 'HTTP %{http_code}\n' --max-time 5 \
    -H 'Content-Type: application/json' --data-binary @- \
    http://localhost:8080/api/auth/login
```

Set `ERBAS_AUTH_BOOTSTRAP_ADMIN=true` explicitly to create a controlled demo
administrator; it defaults to false and is forwarded by development Compose.
The account persists with the development volume. Repeated startup with the same
credentials and admin flag reuses it without changing its ID/hash/state.
An existing disabled account, different password or different admin flag fails
bootstrap safely; it is never promoted, demoted, overwritten or
reactivated. Use a fresh local email for a separate demo identity. This is not a
password reset or account management API. Removing bootstrap variables prevents
future seeding; it does not delete accounts or volumes.

Application bootstrap also requires exactly one active profile: `development`
or `validation`. Enabling it without that profile, with mixed/production profiles,
or without nonempty email/password fails before creating an account. The normal
application defaults to bootstrap disabled. Production must not enable local/test
profiles or bootstrap. Passwords are encoded with the same standard encoder used
by login; no credentials are logged. TTL is also forwarded by development Compose.

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
`HealthIntegrationIT,AuthenticationPersistenceIT,AuthenticationIntegrationIT,AuthenticationBootstrapIT,UsersFoundationIT,UsersIntegrationIT`
against its own PostgreSQL,
with real Flyway and JDBC. The persistence tests verify V1, V2 and V3 exactly once,
no authentication seed data, JDBC round trips, constraints, and token lookup
eligibility at expiry and for disabled users. Tests roll back their fixture data.
Authentication integration tests additionally verify login, real bearer use through
the security chain, hash storage, deterministic expiry and equivalent failures.
Bootstrap integration verifies encoded local/admin provisioning, idempotence and
preservation of existing accounts. USERS-001 adds synchronized real-PostgreSQL
concurrency checks and V2-to-V3 upgrade preservation. Bootstrap remains off for
the native suite. Ordinary fixtures roll back; committed concurrency fixtures
and the owned upgrade schema are cleaned explicitly in the isolated database.
The development database is never used.
The context and strict login/user MVC tests remain in the regular native suite.
User integration tests exercise HTTP creation with real hashing, disabled-token
rejection, re-enabling, promotion/demotion with existing tokens, self-updates
and last-admin error mapping.

Java starts against the separate API database. Bounded health waiting, real
Actuator HTTP and SQL assertions verify V1, V2 and V3 once, no failed migrations,
marker 1 and both auth tables. `bin/check` replaces any supplied test credentials
with a per-run validation email and a password generated from 32 bytes of host
`/dev/urandom` (hex encoded). Validation Compose injects them only into the app's
explicit validation bootstrap; the runner receives them by environment names.
No credentials are build arguments or image contents. SQL checks exactly one
enabled administrator fixture account, the PBKDF2 encoding and inequality with the clear password,
without printing the account, password or hash. The
script then invokes the existing contract interface with its generated network:

```bash
"$ERBAS_CONTRACT_DIR/bin/test" --network "$validation_network" http://app:8080
```

This illustrates the internal call, not another complete validation entry point.
The sole shared collection is invoked with PostgreSQL available, without skips.
The pinned draft 0.4.0 contains 82 requests and 313 named checks, including
USERS-001 and COLLECTIONS-001. It requires disposable isolated validation data and creates unique
users; do not run this mutating suite against production. No conformance skip
switch is provided. See [COLLECTIONS-001 evidence](verification/collections-001.md).
The runner withholds detailed Bruno output to protect credentials and tokens.
After shared conformance, SQL verifies that the validation admin is preserved,
created disposable users remain enabled non-admins, all password hashes use
PBKDF2, token references are valid, at least one enabled admin remains and
Flyway history is valid. It does not assume a fixed total user count.
`bin/check` then stops
only API PostgreSQL and inspects its stopped state. It then
directly verifies `/health`: HTTP 200, `application/json`, exact body
`{"status":"ok"}`. Login/Bruno is not repeated with DB down. Actuator's dependency
health is inspected separately and can return 503 without invalidating liveness.

A separate login probe keeps one real token only in process memory for log
checks. EXIT cleanup inspects app/database logs for the generated password,
password hash, emitted token, credential JSON fields, Authorization/Bearer
headers and digest patterns, withholding any matched content. Native auth and
bootstrap tests also capture output and assert that their known secrets are absent.
Log-check failure makes validation fail even if resource cleanup succeeds.
Validation never inherits or provisions development credentials.

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
| Optional database-down Actuator probe | 45 seconds (HTTP 40 seconds) |

Contract limits are HTTP 2 seconds, Bruno 30 and lint 30. Host watchdogs allow
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
