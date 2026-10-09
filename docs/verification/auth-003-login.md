# AUTH-003 task 2: login and Spring Security

Approved scope: minimum login, password hashing, opaque tokens, Spring Security
and native/PostgreSQL integration tests. Operational account bootstrap, contract
revision update, shared Bruno and changes to the contractual `bin/check` flow
remain pending task 3. V1 and V2 are unchanged by this task. No commit, push or
PR is authorized or made.

## Dependency and password decision

Added only `spring-boot-starter-security`, managed by the existing Spring Boot
4.1.1 parent. The resolved Spring Security modules are version 7.1.1, the stable
version in Boot's [versioned BOM source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/platform/spring-boot-dependencies/build.gradle).
No extra authentication framework, password library, ORM or test dependency is
introduced. Spring Security uses Apache-2.0; Java 25 and pinned images are retained.

Native `Pbkdf2PasswordEncoder` uses PBKDF2-HMAC-SHA256, 600,000 iterations,
16-byte random salt and 256-bit derived output. `DelegatingPasswordEncoder`
prefixes the salt/hash representation with `{pbkdf2-sha256-600000}` to identify
the exact parameters. It accepts only that encoding; future encodings can be
added deliberately. No own password cryptography, truncation or prehash is used.
The parameters can be tuned in future with a new encoding identifier.

This deliberately avoids BCrypt's 72-byte encoding limit, which would conflict
with long passwords permitted by AUTH-001. Long Unicode passwords, whitespace
and differences after byte 72 are verified. Spring's PBKDF2 comparison delegates
to the native encoder and uses `MessageDigest.isEqual` internally.
See the [versioned PBKDF2 source](https://github.com/spring-projects/spring-security/blob/7.1.1/crypto/src/main/java/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.java)
and [BCrypt source](https://github.com/spring-projects/spring-security/blob/7.1.1/crypto/src/main/java/org/springframework/security/crypto/bcrypt/BCrypt.java).

## Responsibilities

| Class | Responsibility |
| --- | --- |
| `LoginUseCase` | Framework-independent credential check, enabled-state check and token issuance through three consumed interfaces |
| `JdbcAuthenticationStore` | Existing parameterized JDBC persistence; now implements the application's credential lookup interface |
| `SpringPasswordVerifier` | Standard password comparison and valid dummy hash for unknown or unusable stored credentials |
| `OpaqueAccessTokens` | SecureRandom issuance, SHA-256 digest, JDBC storage, clock/TTL and enabled/unexpired identity lookup |
| `BearerAuthenticationProvider` | Native AuthenticationProvider mapping opaque bearer credentials to stable user-ID Authentication |
| `BearerAuthenticationFilter` | Bearer header parsing, AuthenticationManager invocation and SecurityContext population within the security chain |
| `AuthenticationConfiguration` | Encoder, clock, use-case wiring, ProviderManager and explicit stateless SecurityFilterChain |
| `LoginController` | Local strict JSON parsing and closed contractual responses |
| `LoginExceptionHandler` | Controller-scoped unreadable/absent-body mapping to the contractual 400 |

The application credential record redacts hashes and email from diagnostic
output. No production account is created. Test users exist only inside rolled-back
integration-test transactions and use the configured real encoder.

## Tokens and security chain

Each issuance generates 32 bytes (256 bits) using Java `SecureRandom` and returns
43 Base64 URL-safe characters without padding. Only lowercase hexadecimal
SHA-256 of the ASCII token is persisted, with user ID and UTC timestamps. Raw
tokens are returned once and never passed to SQL. Incoming tokens from this
backend are hashed for indexed lookup; disabled users and expired tokens fail.
An injected clock permits deterministic expiry tests without waiting.

Internal TTL defaults to one hour. `erbas.auth.access-token-ttl` accepts a Duration
and reads `ERBAS_AUTH_ACCESS_TOKEN_TTL`; allowed values are 1 second through
30 days. Invalid internal TTL fails startup. Integration overrides TTL to two
minutes and verifies the stored duration and exact expiration boundary. Expiry
metadata is not in the HTTP response. Compose forwarding remains task 3.

Security is explicitly enabled with `@EnableWebSecurity`. Basic, form login,
logout, CSRF and request caching are disabled; session creation is STATELESS.
The real AuthenticationManager bean prevents Boot's default generated user.
Only POST login and GET Health/operational Actuator probes (including existing
liveness/readiness subpaths) are public; other requests require authentication.
No CORS, roles, business authorities, refresh or global JSON error contract is added.
Protected unauthenticated requests receive 401 with a Bearer challenge.

The bearer filter is instantiated only inside SecurityFilterChain, avoiding
automatic servlet registration twice. It runs before anonymous authentication,
returns an authenticated principal whose name is the stable numeric user ID,
retains no bearer credential, and delegates context cleanup to Spring Security.
Public probes and login skip bearer lookup entirely, even with Authorization
supplied, preserving database-independent HTTP liveness.

## HTTP and enumeration

Only the login parser uses a dedicated Jackson 3 JsonMapper. It requires exactly
one object with exactly `email` and `password`, both nonempty strings. No coercion,
aliases, trimming, email regex or password-strength validation occurs. Malformed
JSON, absent bodies and trailing JSON tokens become the closed 400 response.
The parser's default string-length ceiling is raised to Java's maximum string
length so it does not add an arbitrary login-field length policy. Other application
serialization is unchanged. Duplicate member names remain outside AUTH-001.

| Outcome | HTTP/body | Header |
| --- | --- | --- |
| Success | 200, exactly `{"accessToken":"..."}` | `Cache-Control: no-store` |
| Structural failure | 400, exactly `{"code":"invalid_request"}` | JSON content type |
| Credential failure | 401, exactly `{"code":"invalid_credentials"}` | `WWW-Authenticate: Bearer` |

All three use `application/json`. Errors contain no field lists, human messages,
Jackson details, stack traces or Problem Details. Unknown, wrong-password,
disabled and unusable-hash cases are generic. Every structurally valid credential
check invokes password verification. Unknown users compare against a valid
dummy encoding; unsupported/corrupt stored encodings also trigger dummy comparison.
This removes the trivial hashing-cost omission, without claiming complete timing
equivalence or advanced enumeration protection.

## Verification method

Docker execution uses the standalone validation Compose file with an explicit
unique project, `/dev/null` env file, bounded commands and ownership-checked
cleanup. The temporary orchestration invokes these existing services:

```bash
"${compose[@]}" build native-tests app
"${compose[@]}" run --rm --no-deps native-tests
"${compose[@]}" up --detach --wait --wait-timeout 110 postgres-native postgres-api
"${compose[@]}" run --rm --no-deps native-integration
"${compose[@]}" up --detach --no-deps --wait --wait-timeout 120 app
```

SQL asserts both databases initially have zero public tables. Native tests use
`clean verify`; integration explicitly selects
`HealthIntegrationIT,AuthenticationPersistenceIT,AuthenticationIntegrationIT`.
No native test requires PostgreSQL. Authentication integration uses real JDBC,
the actual encoder, actual login/controller/filter/provider and a test-only
protected controller, without mocked authentication. MockMvc runs the servlet
filter chain against the real ephemeral database.

`GET /__test/authenticated` exists only under `src/test`, explicitly imported
via test configuration and marked TestComponent to exclude general scanning.
The test obtains a real token through login, sends it as Bearer and verifies
200, stable ID, authenticated state, empty authorities, null credentials and no
HTTP session. A subsequent request without a token gets 401. The final JAR is
inspected to ensure none of the test controller/configuration/clock classes
are included.

Tests verify digest persistence, 32 decoded random bytes, different successive
tokens, configurable TTL, exact expiry, invalid tokens, disabling a user after
issuance, generic login failures and unchanged long Unicode passwords. Native
MVC tests cover 28 invalid request shapes plus exact success/error headers and
default protection. MockMvc output dumping is disabled for authentication tests.
Captured integration output is checked for passwords, password hashes, full
issued tokens, token digests and Authorization headers without printing secrets
in failed assertions.

The final image is deployed to the separate empty API database without users.
HTTP probes verify Health/Actuator and login's 400/401 representations. After
stopping API PostgreSQL and inspecting its stopped state, `/health` still returns
exactly HTTP 200, application/json and `{"status":"ok"}`, even with a bearer
header. Migration history still contains V1 and V2 once, no failures and no
authentication seed data. No shared Bruno conformance is claimed at this stage.

The initial build failed because MVC slices lacked HttpSecurity; explicit
EnableWebSecurity fixed that without adding a test dependency. Maven's optional
Flyway/Redgate prefix metadata emitted HTTP 401 warnings, and Mockito/Byte Buddy
emitted the existing dynamic-agent warning on Java 25. These do not block builds.

## Final results (2026-10-09)

Final run: `erbas-auth003-587678-24568-5725`, exit 0.

- Native suite: 41 tests, zero failures/errors/skips, both in the Docker build
  and in a fresh native-tests container without PostgreSQL.
- PostgreSQL integration: 23 tests (2 Health, 14 persistence, 7 authentication),
  zero failures/errors/skips. Captured-output secret checks passed.
- Empty databases, V1/V2 once, no failed migrations and no authentication seed
  data: verified on the isolated native and API databases.
- Final image build and isolated deployment: passed. Health and Actuator were
  operational; real HTTP login smoke returned closed 400/401 JSON with the
  expected content type and Bearer challenge.
- PostgreSQL stopped state was inspected; the exact Health response still
  passed with an Authorization header. Test endpoint classes were absent from
  the production JAR.
- Ownership-checked cleanup: zero remaining run containers, networks, volumes
  and temporary image tags. Shared build cache is permitted. The initial failed
  build and the intervening successful run also cleaned their resources.
- Development retained its original running container IDs and untouched volume.
  Read-only normalized pg_dump SHA-256 before and after was identical:
  `c08da0b0530496c210e7a1a0ae4f7ee56ed23719eaa5f45f04289eb45ce548d0`.
- `git diff --check` passed. No versioned Bash scripts changed; the temporary
  verification orchestrator passed `bash -n`.
- `erbas-contract` remained clean. `bin/check`, `contract.revision`, development
  Compose and both migrations were not edited in task 2. No shared conformance,
  failure-path runner checks, local user bootstrap or production deployment was
  performed. No commit, push or PR was made.
