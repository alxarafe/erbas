# ERBAS Agent Working Agreement

This document defines the project-specific working rules for AI agents and
human collaborators working in this repository.

System, platform, security, and higher-priority instructions always take
precedence over this document. User instructions for the current task take
precedence over this document when they are more specific or more recent.

## Project scope

ERBAS is a progressive laboratory for designing and building an API-only ERP
core, initially based on Java and Spring Boot.

The long-term target may include identity, authentication, authorization,
permissions, an HTTP/JSON API, module management, capabilities published by
modules, auditing, events, observability, AI-agent integration, and independent
test modules. These capabilities are not automatically approved for
implementation.

Do not implement business capabilities unless the current task explicitly
approves them.

## Mandatory task lifecycle

Work must be split into small, independently verifiable vertical tasks. Do not
implement a large modification as one task.

For every task, follow this order:

1. Study the repository and the relevant official sources.
2. Define one small task with one verifiable outcome.
3. State acceptance criteria.
4. Identify risks and pending decisions.
5. Obtain explicit human approval.
6. Implement only the approved task.
7. Verify it with the applicable tests.
8. Deploy it to the applicable environment.
9. Run post-deployment checks.
10. Present evidence.
11. Obtain approval before starting another task.

If a request combines independent capabilities, stop and split it into ordered
tasks. Request approval for the first task only. Do not silently expand the
scope of an approved task.

## Decision status

Do not attribute undocumented decisions to the team. Clearly label decisions as
one of:

- **Approved**: explicitly accepted by the human collaborator.
- **Proposed**: suggested but not yet accepted.
- **Pending**: requires information or a decision before implementation.
- **Discarded**: considered and rejected.
- **Antecedent**: historical context that must not be treated as a current
  decision.

A technology mentioned in a proposal, prototype, recommendation, or previous
conversation is not a final decision unless explicitly approved.

## Technology and dependency policy

The current technical baseline is a proposal unless separately approved:

- Java 25 LTS;
- a stable Spring Boot release officially compatible with Java 25;
- Maven and Maven Wrapper;
- real PostgreSQL;
- JUnit;
- Java integration tests against real PostgreSQL;
- Bruno CLI for deployed API integration tests;
- Docker and Docker Compose as the only supported project environment.

Before introducing or changing a framework, library, image, or tool:

1. Consult current official documentation.
2. Check compatibility with the project baseline.
3. Check maintenance status.
4. Check licensing.
5. Record the exact version.
6. Explain why it is needed.
7. Request approval when it introduces a decision not already approved.

Do not replace Java 25 with another Java version without explicit approval. Do
not use milestone, release-candidate, snapshot, preview, or Docker `latest`
versions.

## Docker-only environment

Docker and Docker Compose are the only supported environment for building,
running, testing, and deploying the project.

The host may be expected to provide only Git, Docker, Docker Compose, and Codex.
Do not require host-installed Java, Maven, PostgreSQL, Node.js, Bruno, Ollama,
or other project dependencies.

Documented commands must run directly through Docker or Docker Compose, or
through versioned scripts that invoke Docker or Docker Compose.

Application images must:

- use a multi-stage build;
- separate build and runtime stages;
- contain only the required runtime contents;
- run as a non-privileged user;
- contain no secrets;
- contain neither source code nor Maven in the final image;
- provide a verifiable health check;
- use explicitly versioned and reproducibly pinned images.

Keep development, unit testing, integration testing, deployed API testing, and
local verified deployment as separate concerns and environments.

## Docker port policy

Application containers should keep conventional internal service ports, but
host ports must not be assumed to be available. ERBAS uses container port 8080
and publishes it on host port 48080 by default, bound to loopback only. The host
port is configurable through `ERBAS_JAVA_PORT`. Follow the shared convention in
`erbas-contract/docs/development-ports.md`; these ports are infrastructure, not
HTTP contract requirements. Ephemeral verification and CI
should avoid fixed host ports when they are unnecessary; when host HTTP access
is required, dynamically assigned host ports should be preferred and discovered
programmatically. Infrastructure services such as PostgreSQL must not publish
host ports unless host access is explicitly required.

## Pragmatic hexagonal architecture

ERBAS follows a pragmatic hexagonal architecture that emerges from real use
cases. Domain and application logic must not depend directly on Spring, Docker,
Flyway, PostgreSQL, HTTP, or other infrastructure technologies. Infrastructure
should remain replaceable behind appropriate boundaries when actual use cases
require them. Controllers, persistence implementations, and external
integrations belong to adapters or infrastructure rather than business logic.
Do not create speculative ports, adapters, repositories, domain entities,
services, or empty package structures before a real use case needs them. Do not
create architecture for visual package symmetry; avoid both framework-driven
domain design and unnecessary abstraction.

## Database isolation

Never use the development database for destructive tests. Maintain clearly
separate databases or instances for development, Java integration tests, and
Bruno/API integration tests.

API integration tests must run against PostgreSQL created from an empty state,
without reusing previous data. The end-to-end process must demonstrate:

1. clean PostgreSQL creation;
2. application of all migrations from scratch;
3. application startup;
4. successful health check;
5. Bruno execution over HTTP;
6. response and effect verification;
7. service shutdown;
8. removal only of the ephemeral containers, networks, and volumes created by
   that run.

Never perform destructive operations with empty project names, unvalidated
variables, broad paths, wildcards, or resources that cannot be unambiguously
identified as belonging to the test environment.

## Verification requirements

Compilation alone does not complete a task. Run the applicable checks, which
may include:

1. compilation;
2. static analysis;
3. unit tests;
4. integration tests;
5. security tests;
6. Bruno API integration tests;
7. final image build;
8. Docker Compose deployment;
9. health check;
10. post-deployment smoke test.

Java integration tests verify components, persistence, and transactions.
Bruno verifies the deployed application through HTTP. They are complementary
and must not be treated as interchangeable.

Report commands, results, relevant warnings, environment limitations, and
cleanup evidence. Do not claim a check that was not actually executed.

## Safe repository changes

Preserve unrelated user changes. Inspect the working tree before editing. Use
`apply_patch` for repository edits.

Do not use destructive commands such as broad recursive deletion, hard resets,
or checkout-based overwrites unless the human explicitly requests the exact
operation. Resolve targets before destructive actions and prefer recoverable
operations where practical.

Do not expose secrets, credentials, tokens, or private data in source code,
logs, documentation, commits, or final reports.

## Language and documentation

Comments and versioned public documentation are written in English.

Detailed Spanish working documentation may be kept under the local `private/`
directory. That directory must remain excluded by `.gitignore` and must not be
included in Docker build contexts or deployed artifacts. It is not a security
boundary and must not be used to store secrets.

## Current repository baseline

The approved baseline includes Spring Boot, Maven Wrapper, a context test,
multistage image, non-privileged execution, Actuator, PostgreSQL, JDBC and Flyway's
infrastructure marker. CONTRACT-001B adds shared liveness and isolated validation.
Authentication, authorization and business modules require separate approval.

## Shared contract and validation

`alxarafe/erbas-contract` owns the external API: OpenAPI is formal specification,
and its single Bruno collection is executable conformance. This backend cannot
change it unilaterally or duplicate the collection. Never add implementation
branches to the shared contract.

Declare the implemented revision in `contract.revision`. The current commit is
unpublished, not a released version. Require an explicit directory and unpublished
checkout opt-in. Verify identity, exact HEAD and tracked/nonignored cleanliness
before every shared test call. Allow ignored files and preserve them. Never pull,
checkout or modify the supplied contract repository.

`./bin/check` is the sole complete backend validation entry point: native tests,
Java integration with clean PostgreSQL, final image, real migrations, Actuator,
and shared Bruno with a different clean PostgreSQL. Keep bounded waits, nonzero
failure propagation, exact ownership and cleanup on success and failure. Fault
controls belong only to host verification, never production application behavior.

Failed conformance must block publication/deployment. CONTRACT-002 will integrate
this authority into CI; current general CI does not claim shared conformance.
Do not commit, push, create PRs, merge, tag, release, publish or deploy without
express authorization. Preserve unrelated changes and resources.

## Public documentation

Keep README a brief public landing page linking every ERBAS repository. Detailed
operations belong in docs, indexed by docs/README.md, without duplicate manuals.
Public documentation is in English. Badges must reflect verifiable facts or real
workflows. CI, local conformance and contractual revision are different facts;
keep links and states current and never use a static green conformance badge.
