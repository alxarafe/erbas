# ERBAS Java

> One shared API contract. An independent Java implementation.

[![CI](https://github.com/alxarafe/erbas/actions/workflows/ci.yml/badge.svg)](https://github.com/alxarafe/erbas/actions/workflows/ci.yml)

ERBAS Java is the Java/Spring Boot implementation of an API-only ERP laboratory.
Java and .NET converge on a neutral contract so consumers can use either stack.

| Repository | Responsibility |
| --- | --- |
| [erbas-contract](https://github.com/alxarafe/erbas-contract) | Shared OpenAPI and the sole Bruno collection |
| [erbas](https://github.com/alxarafe/erbas) | Java implementation with PostgreSQL and Flyway |
| [alxarafe-dotnet](https://github.com/alxarafe/alxarafe-dotnet) | Independent .NET implementation; conformance pending |
| [erbas-client](https://github.com/alxarafe/erbas-client) | Planned shared Angular consumer |

The scope is a persistence foundation and `GET /health` returning
`{"status":"ok"}`: HTTP process liveness after startup. `/actuator/health`
remains Spring's operational probe. No business modules or authentication exist.

## Get started

Build and run native tests with Docker:

```bash
docker compose run --rm build
```

Run complete isolated validation against the explicitly supplied contract:

```bash
ERBAS_CONTRACT_DIR=/path/to/checkout/erbas-contract \
ERBAS_CONTRACT_ALLOW_UNRELEASED=1 \
./bin/check
```

No host Java, Maven or Bruno is needed. See [usage](docs/usage.md).

## Status and documentation

The [declared revision](contract.revision) is an unpublished commit; there is no
contract release or `v0.1.0` tag. [Local evidence](docs/verification/contract-001b.md)
is separate from the existing general CI badge, which does not run shared Bruno.

Browse the [documentation index](docs/README.md),
[integration decision](docs/decisions/0001-java-health-conformance.md) and
[working agreement](AGENTS.md). CONTRACT-002 will add mandatory CI conformance
and deliberate consumption of a published contract version.
