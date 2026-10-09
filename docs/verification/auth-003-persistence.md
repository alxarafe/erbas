# AUTH-003 task 1: authentication persistence

Scope approved on 2026-10-09: incremental V2, minimum tables, JDBC access and
isolated PostgreSQL integration verification. Login, Spring Security, password
encoding, token generation, bootstrap accounts and AUTH-001 conformance remain
pending subsequent tasks. No dependency or image versions changed.

## Schema and JDBC decisions

V1 is unchanged. V2 creates schema only, without authentication seed data:

| Table | Columns and constraints |
| --- | --- |
| `auth_user` | Identity `BIGINT` primary key; unique, nonempty `TEXT` email; nonempty `TEXT` password hash; explicit non-null boolean `enabled` |
| `auth_access_token` | Primary key containing a lowercase 64-character hexadecimal SHA-256 digest; non-null FK to user; non-null `TIMESTAMPTZ` creation and expiry; expiry strictly after creation |

Email comparison is exact and case-sensitive; no trim, normalization, format
regex or maximum login length is introduced. The password column accepts an
encoded representation without binding the schema to one password algorithm.
The persistence layer cannot prove that an arbitrary supplied value was hashed;
encoding and token hashing must be enforced by their callers in the next task.

The email unique constraint and token primary key provide lookup indexes.
Additional indexes cover the referencing user ID and expiry. The FK uses the
default restrictive deletion behavior; no automatic user/token deletion policy
is introduced.

`JdbcAuthenticationStore` uses existing `JdbcTemplate`, bound parameters and
PostgreSQL `INSERT ... RETURNING id`. It creates users from supplied hashes,
reads credentials, stores supplied token digests and resolves user identity
only when the user is enabled and expiry is strictly later than the supplied
instant. Java `Instant` maps to PostgreSQL timestamps through JDBC; no clock or
TTL policy is introduced. Credential diagnostic output omits email and hash.
No speculative application interfaces, security APIs, ORM or HTTP routes are added.

Sources: [PostgreSQL 18 constraints](https://www.postgresql.org/docs/18/ddl-constraints.html)
and [Spring JDBC core](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html).

## Verification

Docker-only execution used standalone `docker/compose.validation.yaml`, an
explicit unique project and `/dev/null` as env file. No development overlay,
host port or development volume was used. With `compose` denoting that explicit
Compose invocation, the executed commands were:

```bash
"${compose[@]}" config --quiet
"${compose[@]}" build native-tests app
"${compose[@]}" run --rm --no-deps native-tests
"${compose[@]}" up --detach --wait --wait-timeout 110 postgres-native postgres-api
"${compose[@]}" run --rm --no-deps native-integration
"${compose[@]}" up --detach --no-deps --wait --wait-timeout 120 app
"${compose[@]}" exec -T app curl --fail --silent --show-error --max-time 5 http://localhost:8080/health
"${compose[@]}" exec -T app curl --fail --silent --show-error --max-time 5 http://localhost:8080/actuator/health
```

Both databases were checked via SQL to have zero public tables before startup.
Native tests executed `./mvnw --batch-mode --no-transfer-progress clean verify`:
2 tests passed without PostgreSQL, with no failures, errors or skips.
Native integration explicitly selected `HealthIntegrationIT,AuthenticationPersistenceIT`:
16 tests passed (2 Health, 14 persistence), with no failures, errors or skips.

Persistence coverage includes credential round trips with a synthetic pre-encoded
fixture, token digest/timestamp round trips, exact expiry, disabled-user lookup,
unknown lookup, unique email and digest, FK insertion/deletion, digest format,
positive lifetime and mandatory user fields. Transactional fixtures roll back.
These tests do not claim password hashing or bearer authentication implementation.

Migration checks assert exactly successful versions `1,2`, no failed migration,
marker 1, empty authentication tables and the expected token indexes. Calling
Flyway again executes zero migrations. The separately deployed final runtime
image is checked with HTTP health probes and SQL against the API database.

Final run `erbas-auth003-533881-14354-12654` exited 0. After checking ownership,
only that project's containers, network, volumes and two image tags were removed.
Final Docker inventories reported zero remaining containers, networks, volumes
and temporary image tags. Shared build cache remains permitted. The preceding
run `erbas-auth003-527078-29202-7378` also exited 0 with empty final inventories.
Development containers retained their original IDs and remained running.
A read-only development database dump, excluding PostgreSQL's random psql
restriction markers, had the same SHA-256 fingerprint before and after:
`c08da0b0530496c210e7a1a0ae4f7ee56ed23719eaa5f45f04289eb45ce548d0`.
The development volume was not mounted or removed by validation.
The contract checkout remained clean. `git diff --check` passed; no repository
Bash scripts changed. No commit, push or PR was made.

Maven reported HTTP 401 warnings fetching optional repository prefix metadata
from Flyway/Redgate feeds; builds succeeded. Tests also reported the existing
Byte Buddy dynamic-agent warning on Java 25.

Complete `bin/check` and shared Bruno are not executed in this persistence-only
task: the supplied AUTH-001 checkout differs from the still unchanged Health
revision, and login is deliberately pending. No full AUTH-003 conformance is claimed.
