# ERBAS Java

> One shared API contract. An independent Java implementation.

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
Java backend CI: [![Java backend CI](https://github.com/alxarafe/erbas/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/erbas/actions/workflows/ci.yml)
.NET backend CI / shared conformance: [![.NET backend CI / shared conformance](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml)
Angular client CI: [![Angular client CI](https://github.com/alxarafe/erbas-client/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/erbas-client/actions/workflows/ci.yml)

ERBAS Java is the Java/Spring Boot implementation of an API-only ERP laboratory.
Java and .NET converge on a neutral contract so consumers can use either stack.

| Repository | Responsibility |
| --- | --- |
| [erbas-contract](https://github.com/alxarafe/erbas-contract) | Shared OpenAPI and the sole Bruno collection |
| [erbas](https://github.com/alxarafe/erbas) | Java/Spring Boot backend implementing Health, AUTH-001 and USERS-001 with PostgreSQL and Flyway |
| [alxarafe-dotnet](https://github.com/alxarafe/alxarafe-dotnet) | .NET backend implementing Health and AUTH-001 plus platform modules; CI includes shared Bruno |
| [erbas-client](https://github.com/alxarafe/erbas-client) | Angular 22 client consuming Health and AUTH-001 from either backend; WEB-002 completed |

The scope includes JDBC authentication persistence, minimal login and opaque
bearer authentication through Spring Security. `GET /health` returns
`{"status":"ok"}`: HTTP process liveness after startup. `/actuator/health`
remains Spring's operational probe. Java implements shared AUTH-001 login with
PBKDF2 passwords and opaque bearer tokens stored only as digests. Local accounts
require [explicit bootstrap](docs/usage.md#local-login-account); no accounts are
provisioned by default. Registration, logout and refresh are outside this scope.

USERS-001 provides current identity and basic administrator-only user creation,
listing and enabled/admin updates, with atomic last-admin protection. Roles,
permissions, email changes and password management are outside this scope.
See [USERS-001 verification](docs/verification/users-001.md).

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
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check
```

No host Java, Maven or Bruno is needed. See [usage](docs/usage.md).

## Status and documentation

The [declared revision](contract.revision) is an unpublished commit; there is no
contract release or `v0.1.0` tag. [AUTH-003 local evidence](docs/verification/auth-003.md)
records completed AUTH-003 and local AUTH-001 conformance against the pinned
historical revision. The current pin targets draft 0.3.0;
[USERS-001 local evidence](docs/verification/users-001.md) records full shared
conformance through `bin/check`. This is separate from the general Java CI
badge, which does not run shared Bruno.

The .NET badge reports the .NET workflow, including its shared Bruno checks;
it does not establish Java conformance. Angular client CI verifies its own
tests, build, runtime and isolated Health/login proxies. The client's
[real dual-backend demo evidence](https://github.com/alxarafe/erbas-client/blob/main/docs/full-stack-development.md#web-002-integration-verification-2026-10-09)
records WEB-002 integration separately from those CI results.

Browse the [documentation index](docs/README.md),
[integration decision](docs/decisions/0001-java-health-conformance.md) and
[working agreement](AGENTS.md). CONTRACT-002 will add mandatory CI conformance
and deliberate consumption of a published contract version.

## License

Copyright (c) 2026 Alxarafe. Licensed under [Apache-2.0](LICENSE).
