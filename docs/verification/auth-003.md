# AUTH-003: Java operation and shared conformance

Verified on 2026-10-09. Approved scope is complete in three independently
verified tasks:

1. [Persistence foundation](auth-003-persistence.md), local commit
   `fa497fb5925ff6b82a660199e0f14135f48285d4`.
2. [Login and Spring Security](auth-003-login.md), local commit
   `a1bb6ff8ec00fc911925f51be191e7a2a87eadc7`.
3. Explicit account bootstrap, operational validation and shared conformance,
   documented here. Earlier task documents retain their historical scope/results.

No authentication redesign, new dependency, migration modification, production
test endpoint, contract change or AGENTS.md change is included in task 3.
Register, logout, refresh, roles, permissions, JWT and CORS remain outside scope.

## Contract authority

`contract.revision` pins `5f814dfb21732ba20c31c66bf62e66caadbdda58`.
The supplied `erbas-contract` checkout had that exact HEAD, main and origin/main,
an executable `bin/test`, and no tracked/nonignored changes before and after
verification. Main ancestry was checked. The existing origin, repository-root,
exact SHA, clean-checkout and unpublished-opt-in guards remain unchanged and run
again immediately before invoking the shared runner. No floating branch is
consumed, and no external repository is modified.

Java implements the shared `POST /api/auth/login` contract with strict local
JSON parsing and closed 200/400/401 bodies. Password hashing remains native
PBKDF2-HMAC-SHA256, 600,000 iterations. Bearer tokens remain opaque 256-bit random
secrets, persisted only as SHA-256 digests with an internal configurable expiry
(default `PT1H`, `ERBAS_AUTH_ACCESS_TOKEN_TTL`). Java and .NET tokens are not
interoperable. Details and security evidence are in task 2's document.

## Explicit account provisioning

An ApplicationRunner executes after Flyway initialization. Bootstrap defaults
to disabled and requires all three settings:

```text
ERBAS_AUTH_BOOTSTRAP_ENABLED=true
ERBAS_AUTH_BOOTSTRAP_EMAIL=<local or validation email>
ERBAS_AUTH_BOOTSTRAP_PASSWORD=<local or validation password>
```

Exactly one active profile must be `development` or `validation`. Missing
credentials or an inappropriate/mixed profile fails before insertion. The normal
Spring Security encoder hashes the password. Parameterized JDBC uses
`ON CONFLICT (email) DO NOTHING`; repeated startup reuses the same enabled account
only when its password matches. Existing hashes/IDs are never overwritten and
disabled accounts are never reactivated. No credential is logged or seeded by
Flyway. Bootstrap tests exercise these guards and idempotence.

Development Compose sets the development profile and forwards optional bootstrap
settings and token TTL. `bin/up` already passes the environment to Compose and
needs no script change. Bootstrap is off by default. Follow the explicit
[local/demo startup and login example](../usage.md#local-login-account) for
WEB-002; no SQL or contract checkout is required. Ports, unpublished PostgreSQL,
persistent volume and `bin/down` behavior remain unchanged. The existing
development environment was not restarted to test this path; development-profile
provisioning is verified against the isolated integration database, and the
development Compose configuration was checked without starting services.

Validation generates a distinct validation-only email and 32 random bytes from
`/dev/urandom`, hex encoded as a new password for every run. Supplied test
credentials are replaced. Standalone validation Compose passes them only to the
app's explicit validation bootstrap; the public contract runner receives
`ERBAS_TEST_EMAIL` and `ERBAS_TEST_PASSWORD`. They are not build arguments or
image contents. SQL verifies exactly one enabled account and its expected PBKDF2
encoding, different from cleartext, before and after conformance without printing
the email, password or hash.

## Reproducible verification

Run from this repository with the clean exact contract checkout:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check

ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check --exercise-failure contract

ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check --exercise-failure startup-timeout
```

Both fault exercises intentionally exit nonzero. All three commands were
executed by the existing lifecycle harness, which independently verifies exit
codes, contract failure attribution and zero remaining owned resources:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./tests/check-lifecycle.sh
```

The harness exited 0. Local evidence is in
`/tmp/erbas-lifecycle.O1YWmZeM/{normal,startup-timeout,contract}.log` and
`/tmp/erbas-auth003-lifecycle.log`; these files are not required inputs or
versioned artifacts.

| Scenario | Isolated project | bin/check exit | Cleanup |
| --- | --- | --- | --- |
| Normal | `erbas-check-657942-11204-16766` | 0 | Passed |
| Startup timeout | `erbas-check-669007-9229-19751` | 124 | Passed |
| Contract connection failure | `erbas-check-676236-31624-2317` | 1 | Passed |

Each scenario completed 49 native tests and 27 PostgreSQL integration tests,
with zero failures, errors or skips. Native tests also ran during image build
and always in a fresh container. Integration comprises 14 persistence, 7 auth,
2 Health and 4 bootstrap tests. Native tests require no database. Real login
followed by Bearer authentication to the test-only protected controller returned
200; missing, invalid, expired and disabled-user tokens failed. No such endpoint
is added to production.

Both isolated PostgreSQL public schemas began empty. V1 and V2 were applied
exactly once successfully; no failed migration existed. The marker and both auth
tables were verified. Integration fixture transactions roll back; the separate
API database contains only its explicit bootstrap account.

The normal run built/deployed the production image, passed readiness and real
Actuator HTTP 200, then ran the entire shared collection against
`http://app:8080` in its isolated Docker network with real PostgreSQL. The pinned
collection contains 15 requests and 45 Bruno tests, all executed without skips.
The public runner exited 0 and reports conformance success; it deliberately
withholds detailed Bruno output, including runtime counters, to protect secrets.
Counts are obtained from the immutable collection, not a printed runner summary.
No local collection, mock backend or published host port is used.

After stopping API PostgreSQL and inspecting `State.Running=false`, the app
remained alive. A direct probe, including a bearer header, verified exactly
HTTP 200, `Content-Type: application/json`, body `{"status":"ok"}` for `/health`.
The shared login suite is not repeated with DB down. Operational Actuator was
separately observed as HTTP 503, as expected for a dependency outage.

The contract fault stops the real app immediately before the public runner:
`CONTRACT_RUN=failure PHASE=database-up EXIT=1` and
`FAILURE_SOURCE=contract-runner` prove attribution. The startup fault pauses
the app and triggers the bounded five-second timeout, exit 124. Cleanup safely
unpauses it before teardown.

## Secrets, preservation and cleanup

All three runs report `LOG_SECRECY=passed`. An extra real login keeps its returned
token only in process memory to check that app/database logs contain neither
that token nor the generated password/hash. Checks also reject Authorization/
Bearer headers, credential JSON fields, digest/hash patterns and the validation
database password. Matched content is never printed. Native auth/bootstrap
captured-output assertions passed. Runner detail output remains suppressed.

Each run independently verified zero owned containers, networks, volumes and
temporary validation image tags, including both failure paths. Shared build
cache/runner images are not owned temporary validation resources.

Development remained running with its original container IDs:

```text
app:      1f4f20189ebe17856cc6dd2d32ea0e16b79d44b3ee014f2178455df24b56ddfd
postgres: 2fb8a814d005e9dd8cd1d579ba7fe37567c12a0b27a2a22d4318f06cb8513bce
volume:   erbas_postgres_data
marker:   1|2026-10-09 17:25:51.602935+00
```

The marker matches the pre-run fingerprint. Development still contains only V1,
`erbas_persistence_marker` and `flyway_schema_history`; no auth migration or
bootstrap was applied there. No development volume/container was removed or
recreated. A prior full-dump checksum lacks recorded normalization options, so
no claim of reproducible full-dump checksum equality is made here.

`git diff --check`, `bash -n bin/check` and development Compose configuration
validation passed. Existing optional Flyway/Redgate HTTP 401 metadata and
Java 25 Mockito/Byte Buddy agent warnings did not cause test/build failures.
No objective AUTH-001 incompatibility was found. The unpublished-checkout opt-in
policy is retained. No push, PR, merge or external repository edit is performed.
