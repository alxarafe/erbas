# USERS-001 — Java HTTP implementation

Task 2B completes basic user administration in Java against unpublished contract
revision `d50673851bd98d9fd20d4bc940eb032a998f1539`, draft `0.3.0`.
The shared contract and collection are unchanged. The
[Task 2A report](users-001-foundation.md) remains historical foundation evidence.

## HTTP and application operations

| Endpoint | Authorization | Success |
| --- | --- | --- |
| `GET /api/auth/me` | Authenticated enabled user | 200, current User |
| `GET /api/users` | Authenticated ADMIN | 200, User array without guaranteed order |
| `GET /api/users/{id}` | Authenticated ADMIN | 200, User |
| `POST /api/users` | Authenticated ADMIN | 201, newly enabled User |
| `PATCH /api/users/{id}` | Authenticated ADMIN | 200, updated User |

`UserController.User` is the sole HTTP user representation: exactly string `id`,
string `email`, Boolean `enabled` and Boolean `admin`. Credentials never enter
it. `/auth/me` maps the existing `UserIdentity` principal without another lookup.
External IDs remain opaque; unparseable/out-of-range/nonpositive Java IDs map
to not found rather than exposing conversion errors.

`UserAdministration` orchestrates identity reads, creation and state updates.
Its concrete persistence boundary is implemented by the existing JDBC store;
its password encoding function comes from the existing `PasswordEncoder` bean.
The application class has no Spring, SQL or HTTP dependencies. Existing state
outcome types now belong to that application boundary; Task 2A's transactional
implementation is unchanged. The store adds only an unordered identity list.
No migrations or dependencies changed, and no second user model/table was added.

## Input, passwords and errors

The local Jackson tree parser rejects malformed/trailing JSON and duplicate keys.
Explicit field/type inspection rejects unknown, missing or null fields and
coercions. Creation permits exactly `email`, `password`, `admin`; PATCH permits
only `enabled` and/or `admin` and cannot be empty. Missing bodies also receive
the same closed invalid-request response through controller-scoped advice.
The parser does not introduce an email length/format/normalization policy.

Creation checks `password.codePointCount(0, password.length())` for inclusive
12–256 limits. The second argument is a UTF-16 boundary, not the measured length.
Supplementary Unicode characters count once. Whitespace and Unicode remain
unchanged; no composition or normalization rule is added. The existing single
PBKDF2-HMAC-SHA256 encoder (600,000 iterations, 16-byte random salt) serves both
bootstrap and creation. Login's nonempty-string validation remains unchanged.

| Status | Exact JSON code | Source |
| --- | --- | --- |
| 400 | `invalid_request` | Body structure or creation password length |
| 401 | `unauthorized` | Missing/invalid/expired/disabled protected bearer |
| 403 | `forbidden` | Authenticated non-admin administrative request |
| 404 | `user_not_found` | Missing or unusable ID after authorization |
| 409 | `email_conflict` | Conflict-safe exact-email insertion outcome |
| 409 | `last_admin` | Existing transactional state-update outcome |

Bodies contain only `code`; responses are `application/json` and no-store.
Protected 401 adds `WWW-Authenticate: Bearer`; 403 does not. Login retains its
distinct `invalid_credentials` response. Controllers and advice set no-store
explicitly; the bearer filter covers these resource paths before authorization,
and security handlers set exact JSON/cache/challenge headers on rejected access.
No validation, exception, SQL or credential details enter error responses.

## Authorization and state changes

Spring Security centrally requires the existing `ADMIN` authority for
`/api/users` and its descendants before MVC validation or lookup. There are no
controller-local admin checks, RBAC or permission abstractions. Bearer lookup
still joins current PostgreSQL user state on every request. Disabling invalidates
existing tokens without deleting their digests; promotion/demotion changes
authorization on the next request without a new token.

All PATCH state changes use Task 2A's READ COMMITTED transaction and
`SHARE ROW EXCLUSIVE` table lock before reading/updating users. The last enabled
admin cannot be demoted or disabled. With another enabled admin, self-changes
return success for the already authenticated request, then yield 403 after
demotion or 401 after disable. Disabled admins never count toward the invariant.
The synchronized real-PostgreSQL concurrency tests remain intact.

Bootstrap stays disabled by default and limited to controlled development/test
profiles. Re-running it never overwrites an existing identity or silently
escalates it; mismatched admin configuration fails safely. No new bootstrap,
registration, DELETE, password/email management, frontend or .NET work is included.

Sources: [Spring Security request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
and [Java 25 String codePointCount](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#codePointCount(int,int)).

## Local verification — 2026-10-10

The final authoritative run used only versioned Docker infrastructure:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check
git diff --check
bash -n bin/check
```

All commands exited 0. The final run identity was
`erbas-check-465311-16671-15589`; `RESULT=passed` and `CHECK_EXIT=0`.

| Native/unit suite | Tests |
| --- | --- |
| Health MVC | 1 |
| Password/token policy | 6 |
| Bootstrap unit | 8 |
| Login MVC | 31 |
| User MVC | 68 |
| Login application | 2 |
| Application context | 1 |
| **Total** | **117** |

| Real PostgreSQL integration suite | Tests |
| --- | --- |
| Health/Actuator | 2 |
| Authentication persistence | 15 |
| Authentication security/login | 9 |
| Bootstrap | 6 |
| USERS-001 foundation | 12 |
| USERS-001 HTTP | 12 |
| **Total** | **56** |

No failures, errors or skips occurred. Native tests ran during image build and
again in a fresh container. The 68 user MVC cases cover exact public bodies,
authorization before lookup/validation, protected 401 headers, no-store on
success/errors, invalid IDs, request types/keys/nulls/duplicates/trailing JSON,
creation conflicts, supplementary password boundaries and update outcomes.
The 12 new HTTP/PostgreSQL tests cover real creation/login with unchanged Unicode
passwords, disable/re-enable, promotion/demotion with existing bearers, allowed
self-updates, last-admin rejection and disabled bearer rejection on all five
operations. The foundation suite preserves its deterministic concurrent update
and concurrent bootstrap tests against PostgreSQL, with no sleep-based race.

Both databases started with empty public schemas. V1–V3 applied successfully;
the marker and authentication tables were verified, and the existing V2-to-V3
preservation test passed. No migration or contract pin changed in Task 2B.
Image build/startup and Actuator HTTP 200 passed. Shared OpenAPI validated and
the actual Java runtime passed the sole shared collection: **67 requests,
253 named checks** (Health/AUTH-001: 15/45; USERS-001 additions: 52/208).
Collection counts were independently inspected. No request was skipped and no
synthetic backend was substituted. AUTH-001 login/Health regressions passed.

After conformance, SQL verified the unique enabled validation admin and its
encoding, disposable users' final enabled/non-admin state, PBKDF2-only stored
passwords, valid token references, the enabled-admin invariant and successful
Flyway history. Assertions use semantic state rather than total user counts.
After stopping only API PostgreSQL, `/health` still returned exact JSON/HTTP 200;
Actuator independently returned 503, as expected for dependency health.

`LOG_SECRECY=passed`, `CLEANUP_STATUS=passed`, `CLEANUP_CODE=0` and
`CLEANUP_EXIT=0`. Owned containers, networks, volumes and image tags were removed;
development resources were untouched. Tokens/passwords stayed in process memory
and no credential material appears in application logs or this report. Existing
Mockito/JDK warnings about dynamic test-agent attachment remain non-failing;
no dependency or JVM configuration change was needed.

Scope and documentation reviews found only Java HTTP operations, their tests,
required validation changes and current documentation. Historical AUTH-003 and
Task 2A reports remain unchanged. README, the documentation index and usage now
describe completed USERS-001 without changing CI claims. Related repository
README status references were reviewed read-only: a coordinated status update
belongs to a separately authorized task after merge. No contract, frontend or
.NET file was modified. Java USERS-001 has no remaining implementation or
conformance blocker. No push, PR, merge, release or deployment was performed.

## Files changed

The atomic Task 2B change contains these 16 Java-repository files:

```text
README.md
bin/check
docker/compose.validation.yaml
docs/README.md
docs/usage.md
docs/verification/users-001.md
src/main/java/com/alxarafe/erbas/auth/application/UserAdministration.java
src/main/java/com/alxarafe/erbas/auth/http/UserController.java
src/main/java/com/alxarafe/erbas/auth/http/UserExceptionHandler.java
src/main/java/com/alxarafe/erbas/auth/infrastructure/AuthenticationConfiguration.java
src/main/java/com/alxarafe/erbas/auth/infrastructure/BearerAuthenticationFilter.java
src/main/java/com/alxarafe/erbas/auth/infrastructure/JdbcAuthenticationStore.java
src/test/java/com/alxarafe/erbas/AuthenticationIntegrationIT.java
src/test/java/com/alxarafe/erbas/UsersFoundationIT.java
src/test/java/com/alxarafe/erbas/UsersIntegrationIT.java
src/test/java/com/alxarafe/erbas/auth/http/UserControllerTests.java
```
