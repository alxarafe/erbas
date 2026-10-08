# ADR 0001: Java shared health conformance

Status: Approved for CONTRACT-001B; actual results are recorded separately.

## Decision

The neutral contract repository owns OpenAPI and the sole Bruno collection.
Implement the approved GET /health using a standalone HTTP controller returning
an explicit immutable one-field record. No database, Flyway or Actuator lookup
is performed. Preserve Actuator, its image health check and application properties.
The route and closed representation are decisions of the existing contract ADR.

Declare the exact unpublished commit in `contract.revision`. Require an explicit
contract directory and unreleased-checkout opt-in. Verify identity, HEAD and
tracked/nonignored cleanliness before provisioning and each public contract call.
Allow and preserve ignored files. Never modify that checkout or follow main.

`bin/check` is Java's complete authority and invokes public `bin/test --network
NETWORK URL`. Copy no OpenAPI, collection or executable contract suite into Java.
Native tests are complementary implementation checks.

Keep development Compose and existing tools unchanged. Standalone validation
uses unique resources, no host ports, and separate clean PostgreSQL instances for
native integration and deployed Bruno. Assert empty schemas, real migrations and
marker 1. Select `HealthIntegrationIT` explicitly with existing Surefire so the
ordinary build does not require an external database; always execute native tests
fresh outside cached image layers.

Repeat the same Bruno collection after stopping only API PostgreSQL. Host-only
fault modes prove timeout and error propagation. The harness invokes public
interfaces rather than duplicating orchestration. Verify concurrency once during
final verification. Traps verify ownership, preserve failures and prove cleanup.
No socket is mounted. Shared build cache can remain locally.

## Delivery boundaries

README is a brief ecosystem landing page; details reside in docs. Distinguish
CI, local conformance and declared revision. CONTRACT-002 owns CI integration
and releases. No .NET conformance, Angular implementation or published contract
version is claimed. Authentication, modules, AI/MCP and other endpoints are out
of scope. Commit, push, publication and deployment need separate authorization.

## Sources

- [Spring MVC testing](https://docs.spring.io/spring-boot/how-to/testing.html)
- [Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [Surefire selection](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html)
- [Compose isolation](https://docs.docker.com/compose/how-tos/project-name/)
