# USERS-001 Task 2A: Java identity foundation

Approved scope: user persistence and authenticated identity only. No current-user
or user-administration HTTP endpoint is added. Health and AUTH-001 remain the
public application API. Contract pin `d50673851bd98d9fd20d4bc940eb032a998f1539`
targets unreleased draft `0.3.0`; it is not a claim of completed implementation.
The supplied contract checkout remains clean and unchanged.

## Persistence and identity

V1 and V2 are unchanged. `V3__user_administrator.sql` adds
`admin BOOLEAN NOT NULL DEFAULT FALSE` to the existing `auth_user`. Its columns
are now `id`, `email`, `password_hash`, `enabled`, `admin`. V3 preserves existing
accounts and credentials, making existing accounts non-admin. There is no second
user table, ORM, JPA, role/permission table or new dependency.

`UserIdentity(userId, email, enabled, admin)` contains no credential and implements
JDK `Principal`; `getName()` retains the existing stable user-ID name. Diagnostic
output omits email. The bearer provider puts this identity in Spring Security's
authenticated principal, with null credentials and one `ADMIN` authority only
for administrators. Future HTTP authorization can use the provider's central
`ADMIN_AUTHORITY` constant with standard Spring Security authority checks.
There is no RBAC or permission abstraction.

Opaque tokens and their SHA-256 digest persistence are unchanged. Each protected
request joins the token with current user state and requires `u.enabled` and an
unexpired token. Disabling rejects an already issued bearer without deleting its
stored digest; promotion/demotion is visible on the next authentication with
the same bearer. No identity information is trusted from token contents.

The existing JDBC store additionally provides explicit admin creation, identity
lookup by ID, enabled-user insertion with `ON CONFLICT (email) DO NOTHING`, and
partial enabled/admin updates. Optional identity lookup expresses found/not
found. Optional insertion expresses created/exact-email-conflict without an
aborted transaction. State updates return `UPDATED` with identity, `NOT_FOUND`,
or `LAST_ADMIN`. Empty internal updates fail before SQL mutation. No generic
exception hierarchy or HTTP mapping is introduced. Email uniqueness remains
exact and case-sensitive, without trimming or normalization.

## Atomic administrator changes

`updateUserState` uses Spring's transaction proxy at READ COMMITTED. Before
reading user state it locks `auth_user` in `SHARE ROW EXCLUSIVE` mode. This
self-exclusive PostgreSQL table lock serializes mutations (including conflicting
inserts) while ordinary login/identity reads can proceed. Under that lock it
rejects disabling/demoting the last enabled admin, otherwise applies the update
and returns the updated identity in the same transaction. Disabled admins do
not count. No identity-specific self-update exception exists.

The primitive checks actual database isolation and rejects a joined higher-
isolation transaction rather than trusting a potentially stale pre-lock
snapshot. Call the Spring-managed store and acquire this lock before other user
mutations in an enclosing transaction. All administrative state mutations must
use this primitive; arbitrary maintenance SQL is outside that application
guarantee. The controlled installation/bootstrap must first create an enabled
administrator. V3 itself never escalates an existing user or seeds credentials.

This deliberately coarse lock fits the small current administration workload;
it serializes user writes until commit. No distributed lock, Redis or generic
locking framework is introduced. Keep transactions short.

Sources: [PostgreSQL 18 explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html),
[Spring declarative transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
and [Spring Security authentication architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html).

## Controlled bootstrap and passwords

Bootstrap still defaults off and requires exactly the development or validation
profile. Explicit email/password are required; `ERBAS_AUTH_BOOTSTRAP_ADMIN`
defaults false. Development Compose forwards it; validation explicitly sets
true for its isolated generated identity. Insertion reuses the conflict-safe
JDBC primitive. Repeated startup only reuses a matching enabled account with
matching password and matching admin flag. Any mismatch fails safely without
overwriting credentials, activating, promoting or demoting the existing account.
Use a new controlled email to create an administrator if an old bootstrap
account is non-admin. There is no production admin or public bootstrap endpoint.

Bootstrap and future creation use the existing single `PasswordEncoder` bean:
PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt. Persistence accepts
encoded passwords only; Task 2B must encode using this same bean before insertion.
Existing password tests establish full Unicode/whitespace support. No creation
validator is added here: Task 2B must enforce 12–256 Unicode code points using
`codePointCount`, not UTF-16 `String.length()`, without trim/composition rules.

## Test design and validation boundary

Native integration uses real PostgreSQL 18.6, Flyway, JDBC, the configured
password encoder and the actual bearer filter/provider. Existing coverage is
retained. New cases cover default/non-null admin semantics, identity, exact
conflict, V2 upgrade preservation, admin bootstrap and mismatch behavior,
dynamic bearer admin state, last-admin rejection and concurrency.

Four concurrent state-change cases cover disable, demote, both fields and mixed
disable/demote. Transaction A changes one of two enabled admins, then holds its
commit behind a latch. Transaction B attempts the other change. The test
observes B's ungranted `auth_user` lock through `pg_locks` before releasing A;
there are no timing sleeps. Exactly A succeeds, B returns `LAST_ADMIN` and one
enabled admin remains. The bootstrap race uses a barrier at the test encoder
delegate so both runners observe absence before either inserts; both succeed
and exactly one encoded enabled admin remains. All synchronization is bounded.

The V2 upgrade test owns a separate schema in the fresh native database and
removes it afterward. Ordinary test fixtures roll back; committed concurrency
fixtures are deleted by exact IDs/email after both workers terminate. No
development database or external repository is used for writes.

`./bin/check` remains the authoritative complete validator, with no skips or
alternate permanent switch. It runs native tests, real empty-database
integration, image/startup/health/migration checks and then the unchanged full
shared runner. At Task 2A the shared run must fail because `/api/auth/me` and the
administration endpoints are deliberately absent. MockMvc tests explicitly
assert 404 for all five operations using a valid administrator bearer. The real
servlet runtime returns 401 after its missing-handler error redispatch passes
through the currently protected error path; it does not supply a User response.
Task 2B must implement handlers and protected-resource JSON error mappings.
Failure is preserved
and must block publication/deployment until Task 2B completes conformance.

Task 2B must also adapt the post-conformance fixture assertion: the shared suite
creates disposable users, so the current original-account count of one cannot
describe the whole database after successful conformance. No runner or contract
change is authorized or needed here.

## Final verification (2026-10-10)

Diagnostic command, unchanged complete validator:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check
```

Final project: `erbas-check-401779-22020-32074`. The diagnostic exited **1**,
at `FAILURE_SOURCE=contract-runner PHASE=database-up EXIT=1`, as expected for
this approved foundation-only task. No failed native validation is waived.

| Applicable check | Actual result |
| --- | --- |
| Native build/unit suite | 49 passed, 0 failures/errors/skips, during image build and in a fresh container. |
| PostgreSQL integration | 44 passed: 2 Health, 15 persistence, 9 authentication, 6 bootstrap, 12 foundation. |
| Concurrent last-admin cases | All four passed with observed lock contention; exactly one operation succeeded per case. |
| Concurrent bootstrap | Passed; one enabled admin, no credential overwrite. |
| Flyway from empty | Both native/API schemas started with zero public tables; successful V1/V2/V3 and marker verified. |
| Existing V2 upgrade | V3 preserved existing user, enabled state and encoded password; admin defaulted false. |
| Image build, application startup, Actuator | Passed; Actuator HTTP 200. |
| Validation identity | Exactly one enabled admin with the expected PBKDF2 encoding; no cleartext storage. |
| AUTH-001 regression | Existing login/use-case/policy tests passed, including closed 200/400/401 exchanges, Unicode, expiry and secrecy; real runtime login returned 200. |
| Real HTTP Health | 200. |
| Full shared Bruno | Failed at deferred USERS-001 surface; full conformance is not claimed. |
| Log secrecy | Passed. |
| Ownership-checked cleanup | Passed; no run containers, networks, volumes or temporary validation image tags remain. |
| Development Compose configuration | `docker compose --env-file /dev/null --project-name erbas-users-001-config --file compose.yaml config --quiet` passed without starting services. |
| Diff and shell syntax | `git diff --check` and `bash -n bin/check` passed. |

A temporary host observer invoked the complete `bin/check` and probed the real
app during its validation lifetime; it changed neither validator nor contract.
It obtained a token without printing/persisting it. The existing protected
`/error` handler returned its default 500 when authenticated, versus 401 with an
invalid bearer, confirming bearer usability independently of absent API handlers.
All five deferred operations returned 401 in the runtime; the first shared
USERS-001 operation requires `GET /api/auth/me` to return 200 and a User. The
MockMvc missing-handler tests return 404; the servlet error redispatch explains
the observed runtime difference. The original observer assumed runtime 404;
that diagnostic assumption was corrected and its repeat passed. No production
endpoint or security workaround was introduced.

Local evidence: `/tmp/users-2a-final-check.log`, `/tmp/users-2a-final-probe.log`
and `/tmp/users-2a-final-verify.sh`. The observer reports applicable foundation
validation passed while retaining `DIAGNOSTIC_CHECK_EXIT=1`; it does not advertise
conformance success or provide a permanent skip mechanism. The later
database-down/lifecycle-harness phases are not reached and remain Task 2B work.
Earlier diagnostic runs also cleaned their owned resources.

Existing Java 25 Mockito/Byte Buddy dynamic-agent warnings remain nonblocking.
No dependency/image change, development deployment, contract edit, backend
publication, push or release is included. Scope and documentation impact were
reviewed; historical AUTH-003 reports are preserved.
