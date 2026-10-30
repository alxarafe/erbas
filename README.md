# ERBAS Java

> One shared API contract. An independent Java implementation.

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[![Java backend CI / shared conformance](https://github.com/alxarafe/erbas/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/erbas/actions/workflows/ci.yml)
[![.NET backend CI / shared conformance](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml)
[![Angular client CI](https://github.com/alxarafe/erbas-client/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/erbas-client/actions/workflows/ci.yml)

ERBAS Java is the Java/Spring Boot implementation of an API-only ERP laboratory.
Java and .NET converge on a neutral contract so consumers can use either stack.
Core provides a [generic Resource Runtime](docs/resources.md) and
[effective application navigation](docs/navigation.md), with module-owned
adapters and declarations. No Assets business module is implemented.

| Repository | Responsibility |
| --- | --- |
| [erbas-contract](https://github.com/alxarafe/erbas-contract) | Shared OpenAPI and the sole Bruno collection |
| [erbas](https://github.com/alxarafe/erbas) | Java/Spring Boot backend implementing Health, AUTH-001, USERS-001, COLLECTIONS-001, CORE module administration and WMS UoM with PostgreSQL and Flyway |
| [alxarafe-dotnet](https://github.com/alxarafe/alxarafe-dotnet) | .NET backend implementing Health, AUTH-001, USERS-001, COLLECTIONS-001 and CORE module administration; CI requires shared Bruno against v0.5.0 |
| [erbas-client](https://github.com/alxarafe/erbas-client) | Angular 22 client consuming Health, AUTH-001, USERS-001 basic user administration and COLLECTIONS-001 pagination from either backend |

The scope includes JDBC authentication persistence, minimal login and opaque
bearer authentication through Spring Security. `GET /health` returns
`{"status":"ok"}`: HTTP process liveness after startup. `/actuator/health`
remains Spring's operational probe. Java implements shared AUTH-001 login with
PBKDF2 passwords and opaque bearer tokens stored only as digests. Local accounts
require [explicit bootstrap](docs/usage.md#local-login-account); no accounts are
provisioned by default. Registration, logout and refresh are outside this scope.

USERS-001 provides current identity and basic administrator-only user creation,
listing and enabled/admin updates, with atomic last-admin protection. COLLECTIONS-001
adds offset/limit pagination and stable ordering to the user list. Role/permission
assignment, email changes and password management are outside this scope.
See [USERS-001 verification](docs/verification/users-001.md) and
[COLLECTIONS-001 verification](docs/verification/collections-001.md).

CORE module administration lists available descriptors and changes persistent
enabled state atomically, respecting dependencies. Normal startup includes the
production WMS module and the AI assistant module. The Maven reactor physically
separates CORE/platform, WMS, AI and the executable host, which composes them explicitly.
AI orchestrates effective READ capabilities through CORE. No real AI provider is
configured; normal requests return `assistant_unavailable`. See [AI](docs/ai.md).
See the [physical architecture decision](docs/decisions/0002-physical-module-boundaries.md).
Canonical development modules are explicitly enabled only in disposable
conformance composition. See [modularity](docs/modularity.md#core-module-administration).

CORE-CAP-001 adds effective capability discovery: WMS declares its three existing
READ catalogue operations; CORE filters descriptors by current user and module
state. Discovery never executes capabilities. See [capabilities](docs/capabilities.md).

The same client provides authenticated profiles and basic administrator user
management with pagination against either backend. Backend authorization remains
authoritative; the opaque bearer stays backend-bound and memory-only. See the
[client development guide](https://github.com/alxarafe/erbas-client/blob/main/docs/full-stack-development.md)
for behavior, error handling and verification scope.

## Get started

Start and stop the development environment with Docker:

```bash
./bin/up
./bin/down
ERBAS_JAVA_PORT=49080 ./bin/up
```

Java defaults to loopback `http://127.0.0.1:48080`; `GET /health` returns
`{"status":"ok"}`. PostgreSQL is not published, and its volume persists across
stops. See [development usage](docs/usage.md#development) for readiness and probes.

`bin/up` starts development; `bin/check` remains the complete isolated validation
authority, using the explicitly supplied contract:

```bash
ERBAS_CONTRACT_DIR=/path/to/checkout/erbas-contract \
./bin/check
```

No host Java, Maven or Bruno is needed. See [usage](docs/usage.md).

## Status and documentation

This checkout targets published ERBAS Contract `v0.14.0`, recorded in
[contract.revision](contract.revision); Java adoption requires publication and
successful authoritative CI. The supplied checkout must be clean and
its release tag and HEAD must resolve to the expected immutable commit.
`./bin/check` is authoritative locally and in GitHub Actions, where shared Bruno
conformance against the same published release is mandatory. The CI badge
reports workflow results; [AUTH-003](docs/verification/auth-003.md) and
[COLLECTIONS-001](docs/verification/collections-001.md) retain historical local
verification evidence against earlier revisions.
Current discovery/adoption validation is described in [capabilities](docs/capabilities.md).
[WMS-003J](docs/verification/wms-003-java.md) retains the earlier v0.8.0 evidence.

The .NET badge reports the .NET workflow, including its shared Bruno checks;
it does not establish Java conformance. Angular client CI verifies its own
tests, build, runtime and isolated shared API proxies.
The client's [real dual-backend demo evidence](https://github.com/alxarafe/erbas-client/blob/main/docs/full-stack-development.md#web-002-integration-verification-2026-10-09)
records WEB-002 integration separately from those CI results. Client CI does
not perform backend shared-conformance validation.

Browse the [documentation index](docs/README.md),
[integration decision](docs/decisions/0001-java-health-conformance.md) and
[working agreement](AGENTS.md).

## License

Copyright (c) 2026 Alxarafe. Licensed under [Apache-2.0](LICENSE).
