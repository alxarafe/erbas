# COLLECTIONS-001 — Java paged user responses

This follow-up adapts only `GET /api/users` to the merged unpublished draft
`0.4.0`, pinned at `42e0c5ad81902a355fe01d6635466717f46b9dfd`.
The existing USERS-001 commits and [draft 0.3.0 report](users-001.md) remain
unchanged. OpenAPI and Bruno remain exclusively in the shared contract checkout.

## Behavior and implementation

The successful JSON object contains exactly `items`, `offset`, `limit`, `total`
and `order`. Items retain the closed public User representation, including opaque
string IDs. The fixed order is `[{"field":"id","direction":"asc"}]`.

Optional offset defaults to 0 and is nonnegative. Optional limit defaults to 50
and ranges from 1 to 100 inclusive. Limit denotes capacity, never item count.
Total counts all users before pagination, including disabled users. Empty and
at/beyond-total windows return 200 with an empty array and complete metadata.
No response timestamp or performance fields are added; HTTP Date remains
protocol metadata. Custom ordering, filters and search are not implemented.

The controller receives query values as strings and explicitly checks decimal
integer syntax and bounds. It does not rely on Spring scalar conversion or
clamping. Empty, negative, nonnumeric, fractional and excessive values return
exactly `400 {"code":"invalid_request"}`. BigInteger preserves nonnegative
offsets beyond the Java long range without adding an undocumented maximum.
An at/beyond-total offset needs no SQL window query and is echoed unchanged.

`UserAdministration` returns a small concrete `UserWindow(items, total)`.
Its existing Store boundary now exposes `countUsers()` and
`listUsers(offset, limit)`. The existing JDBC adapter executes `COUNT(*)` and:

```sql
SELECT id, email, enabled, admin FROM auth_user
ORDER BY id ASC LIMIT ? OFFSET ?
```

SQL parameters remain bound. ID representation/comparison remains a backend
implementation detail; clients do not reproduce it. No pagination framework,
generic repository, new dependency, migration or authorization abstraction is
introduced. The current count and window are separate database reads; the shared
suite runs in its required isolated environment without external mutations.

Central Spring Security ADMIN authorization still precedes MVC/query validation.
Missing/invalid bearers produce the existing closed 401 unauthorized response
and Bearer challenge; authenticated non-admins receive 403 forbidden without
that challenge, even for invalid pagination. Successful and contractual error
responses remain application/json and Cache-Control no-store. Other endpoints,
password hashing, bootstrap, token-state lookup and serialized last-admin
updates are unchanged.

## Native verification

The authoritative Docker-only check runs **135 native/unit/MVC tests** and
**62 real-PostgreSQL integration tests**, with no failures, errors or skips.
UserControllerTests now contains 86 cases; UsersIntegrationIT contains 18.
The task adds 18 MVC cases and six PostgreSQL cases without removing previous
AUTH-001, USERS-001 or deterministic concurrency coverage.

MVC coverage checks exact envelopes, defaults, custom/max limits, custom offset,
empty and beyond-total windows (including offsets beyond long range), malformed
query values with no persistence calls, and authorization precedence.
The SQL integration fixture creates several users including a disabled user,
moves the oldest row's heap version after later inserts, then verifies ordered
defaults, limit 1, offset 1/limit 1 and beyond-total metadata. Total remains four
throughout and listing does not modify user state. Five invalid query cases
also preserve state. Existing bearer invalidation, promotion/demotion,
self-changes and last-admin concurrency tests all pass.

## Shared and operational verification (2026-10-10)

Executed from the Java repository:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 ./bin/check
git diff --check
```

OpenAPI validation and the real shared suite pass: **82 requests / 313 named
checks**, including Health, AUTH-001, USERS-001 and COLLECTIONS-001. Counts were
independently checked in the pinned sole collection. No tests are skipped,
copied into Java or replaced with a synthetic backend.

Both validation databases start empty. V1–V3 apply successfully from scratch,
the runtime image builds and starts, and Actuator returns HTTP 200. Existing
post-conformance semantic SQL checks pass unchanged: the validation administrator
remains enabled/admin, disposable users finish enabled/non-admin, PBKDF2 storage
and token references are valid, an enabled administrator remains and Flyway
history is successful. COLLECTIONS-001 adds reads, not additional user creation.
After API PostgreSQL stops, exact Health still returns 200 independently of it.
Actuator returns the expected dependency-health 503 in that phase.

The check exits 0 with `LOG_SECRECY=passed`, `CLEANUP_STATUS=passed`,
`CLEANUP_CODE=0`, `CLEANUP_EXIT=0` and `CHECK_EXIT=0`, for validation run
`erbas-check-575336-14480-5510`. Owned containers, networks, volumes and temporary
image tags are removed; unrelated development resources are untouched.
`git diff --check` passes. Existing non-failing Maven metadata authorization and
Mockito/JDK dynamic-agent warnings do not require dependency or JVM changes.
There is no remaining Java implementation/conformance blocker. This task creates
only a local commit; pushing and merging still require separate authorization.

Scope, documentation and sensitive-data reviews cover only the contract pin,
user listing implementation, its tests and current documentation. Historical
AUTH-003/USERS-001 evidence is preserved. Related repository README references
were reviewed read-only; coordinated ecosystem status updates require a separate
task after merge. No contract, .NET or Angular file is changed.

## Files changed

```text
README.md
contract.revision
docs/README.md
docs/usage.md
docs/verification/collections-001.md
src/main/java/com/alxarafe/erbas/auth/application/UserAdministration.java
src/main/java/com/alxarafe/erbas/auth/http/UserController.java
src/main/java/com/alxarafe/erbas/auth/infrastructure/JdbcAuthenticationStore.java
src/test/java/com/alxarafe/erbas/AuthenticationIntegrationIT.java
src/test/java/com/alxarafe/erbas/UsersIntegrationIT.java
src/test/java/com/alxarafe/erbas/auth/http/UserControllerTests.java
```
