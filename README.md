# ERBAS Java

> One shared API contract. An independent Java implementation.

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java CI](https://github.com/alxarafe/erbas/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/erbas/actions/workflows/ci.yml)
[![.NET CI / shared Bruno](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxarafe/alxarafe-dotnet/actions/workflows/ci.yml)

ERBAS Java is the Java/Spring Boot implementation of an API-only ERP laboratory.
Java and .NET converge on a neutral contract so consumers can use either stack.

| Repository | Responsibility |
| --- | --- |
| [erbas-contract](https://github.com/alxarafe/erbas-contract) | Shared OpenAPI and the sole Bruno collection |
| [erbas](https://github.com/alxarafe/erbas) | Java implementation with PostgreSQL and Flyway |
| [alxarafe-dotnet](https://github.com/alxarafe/alxarafe-dotnet) | Independent .NET implementation; CI includes shared Bruno |
| [erbas-client](https://github.com/alxarafe/erbas-client) | Planned shared Angular consumer |

The scope includes JDBC authentication persistence, minimal login and opaque
bearer authentication through Spring Security. `GET /health` returns
`{"status":"ok"}`: HTTP process liveness after startup. `/actuator/health`
remains Spring's operational probe. Java implements shared AUTH-001 login with
PBKDF2 passwords and opaque bearer tokens stored only as digests. Local accounts
require [explicit bootstrap](docs/usage.md#local-login-account); no accounts are
provisioned by default. Registration, logout and refresh are outside this scope.

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
is separate from the existing general CI badge, which does not run shared Bruno.

The .NET badge reports the .NET workflow, including its shared Bruno checks;
it does not establish Java conformance.

Browse the [documentation index](docs/README.md),
[integration decision](docs/decisions/0001-java-health-conformance.md) and
[working agreement](AGENTS.md). CONTRACT-002 will add mandatory CI conformance
and deliberate consumption of a published contract version.

## License

Copyright (c) 2026 Alxarafe. Licensed under [Apache-2.0](LICENSE).
