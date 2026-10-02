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

The initial approved baseline provides a minimal Spring Boot application,
Maven Wrapper, JUnit context test, multi-stage runtime image, non-privileged
execution, and Actuator health check. PostgreSQL, migrations, authentication,
authorization, Bruno collections, and business modules require separate tasks
and separate human approval.

