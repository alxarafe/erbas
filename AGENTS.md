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
8. Deploy it to the applicable environment only with separate express authorization.
9. Run post-deployment checks when deployment is authorized and applicable.
10. Present evidence.
11. Review the diff to confirm it contains only the approved task's changes.
12. Review documentation impact and update relevant docs when needed.
13. Close the completed, validated task with a local atomic commit.
14. Obtain approval before starting another task.

Explicit task approval also authorizes staging and its final local commit once
implementation, applicable validations and scope review are complete. No second
authorization is required solely for that commit. Use one atomic commit, or the
minimum number of atomic commits the task's structure requires, with clear
messages consistent with repository conventions.

Do not commit incomplete tasks or tasks with failed validations. Correct and
revalidate any defect found before closing a task. Never start the next task
with uncommitted changes from the previous one; if a task exceptionally starts
with another task's pending changes, separate their commits correctly before
continuing. Preserve all other repository-specific rules.

Task approval does not authorize push, PR creation, merge, tag, release,
publication or deployment; each requires separate express authorization.

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

### Efficient validation execution

During implementation, prefer the narrowest applicable validation that gives
useful feedback — focused unit tests, focused Java integration tests, or the
scoped portions of the suite — before running broader validation.

Do not run the full `./bin/check` — or another long-running repository-wide,
integration, image, deployment, or conformance command — inside an AI-agent
session merely to wait for it to finish when the developer or CI can run it
directly. Repeatedly polling such a process is specifically discouraged: each
poll may cost another model turn that reprocesses the existing conversation and
repository context even though the external process itself needs no reasoning,
consuming effort without adding engineering value.

Whether a command is "long-running" depends on the repository and the task; no
fixed duration threshold applies. When the complete `./bin/check` is required and
is expected to take materially longer than the focused checks, the agent normally
provides the exact command for the developer or CI to execute. Because
`./bin/check` remains the sole complete backend validation entry point and shared
Bruno conformance is mandatory, the agent stops and waits for the real result
before completing or committing an affected task, continues only once it is
provided, and never claims the check passed unless its result is actually known.

If a long-running command genuinely must be executed by the agent, avoid repeated
polling, wait as efficiently as the available tooling permits, and do not
repeatedly reread unchanged files or rerun unchanged validations.

This changes execution responsibility, not validation authority. The mandatory
Java integration, PostgreSQL, image, migration, health, Bruno and cleanup
requirements above are unchanged, and a failed or missing required validation
still blocks task completion or commit exactly as before.

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
AUTH-003 adds completed AUTH-001 login, JDBC authentication persistence, PBKDF2
passwords and opaque bearer tokens stored as digests. Further authentication,
authorization and business capabilities require separate approval.

## Shared contract and validation

`alxarafe/erbas-contract` owns the external API: OpenAPI is formal specification,
and its single Bruno collection is executable conformance. This backend cannot
change it unilaterally or duplicate the collection. Never add implementation
branches to the shared contract.

Declare the implemented published release in `contract.revision`, currently
`v0.14.0`. Require an explicit contract directory. Verify repository root, GitHub
origin, tracked/nonignored cleanliness, the release tag's immutable commit
(`9919ad3725e50d63e7a19befbe0d81b4d91df0cc` for `v0.14.0`) and matching HEAD
before provisioning and every shared test call. The annotated tag object must
be `22191afb8ab477fef319093b8c212264f4019816`. No unpublished-checkout opt-in
is required. Allow ignored files and preserve them. Never fetch, pull, checkout
or modify the supplied contract repository.

`./bin/check` is the sole complete backend validation entry point: native tests,
Java integration with clean PostgreSQL, final image, real migrations, Actuator,
and shared Bruno with a different clean PostgreSQL. Keep bounded waits, nonzero
failure propagation, exact ownership and cleanup on success and failure. Fault
controls belong only to host verification, never production application behavior.

Failed conformance must block publication/deployment. GitHub Java CI invokes
the same `./bin/check` authority with a separate `alxarafe/erbas-contract` checkout
at the published `v0.14.0` tag. Shared conformance and cleanup are mandatory.
Task approval authorizes its final local commit under the lifecycle rules above.
Do not push, create PRs, merge, tag, release, publish or deploy without separate
express authorization. Preserve unrelated changes and resources.

## Public documentation

Keep README a brief public landing page linking every ERBAS repository. Detailed
operations belong in docs, indexed by docs/README.md, without duplicate manuals.
Public documentation is in English. Badges must reflect verifiable facts or real
workflows. CI, local conformance and contractual revision are different facts;
keep links and states current and never use a static green conformance badge.

## Documentation impact review

Every task must review its documentation impact after implementation, validation
and scope review, before the final local commit. If it changes public behavior,
architecture, configuration, usage, development workflow, API capabilities,
repository status or documented limitations, update the relevant documentation
within the same task and the same future pull request. Otherwise, do not modify
documentation merely to record that the review occurred.

README.md is a stable, brief public landing page for project purpose, high-level
capabilities, architecture and ecosystem relationships, essential quick start,
onboarding and configuration, principal limitations, verifiable badges and links
to detailed documentation. It is not a changelog, task history, endpoint catalog,
record of individual WEB-003 slices or exhaustive inventory of internal checks.
Do not change it merely because an endpoint, field, validation or test is added
or changed, or an internal task is completed. Change README only when its
high-level description, onboarding, architecture, essential configuration,
principal capabilities or limitations become materially false or incomplete.

Functional and technical detail belongs preferably in `docs/`. Feature code and
documentation normally belong in the same task and pull request; use separate
functional and documentation commits when that improves review, for example
`feat(...): ...` and `docs: ...`. Do not automatically create a follow-up DOCS task
or coordinated documentation synchronization after each feature.

Cross-repository documentation synchronization is necessary only for a shared
policy change, a contractual or published version change, an architecture or
ecosystem change, materially false information in another repository, or a
significant milestone that actually affects several repositories. Review related
status references when one of these conditions applies; obtain task approval
for necessary changes in other repositories and keep each repository's commits
separate. Preserve historical references and reports as historical; never rewrite
them to appear current. Change badges only when the verifiable fact they represent
changes. Keep CI, local contract conformance and real full-stack demo verification
distinct.

Write public documentation in English. Complete necessary documentation updates
before the final local commit, then obtain approval before starting the next task.
Independent authorization for push, PR creation, merge, tag, release, publication
and deployment remains required.

## API internationalization

Route every user-facing runtime literal through native i18n. Initial languages
are `en` and `es`; English is the base and deterministic fallback. Select the
locale per request through standard `Accept-Language` negotiation only, including
regional variants. Never introduce a language query parameter, custom header,
mutable global locale or persistent user preference for this purpose.

Use Spring `MessageSource` and Jakarta Validation, including Spring's native
integration and recursive `MessageSourceResolvable` resolution. Domain and
Application must not depend on localization, HTTP or request locale. Translate
structured errors at their HTTP boundary with native `ProblemDetail` where that
representation is applicable. The closed shared-contract `{code, errors}` envelope remains technical and
locale invariant; adding human fields requires a separately approved revision.

Resources belong to the module/feature owning the message and ship in its JAR.
Core contains only transversal HTTP/validation text. Use distinct explicit
basenames such as `i18n/core/messages` and `i18n/contact/messages`; the unsuffixed
bundle is English and `_es` is Spanish. Composition registers known basenames;
do not build dynamic discovery or a translation framework. Use complete phrases
with native parameters, never concatenated translated fragments.

Do not translate routes, JSON properties, canonical field names/paths, error
codes, IDs, permissions/policies, claims, events, module names, configuration or
other technical keys, database identifiers, or internal operational/development
logs. Test negotiation, fallback, parameter interpolation and stable technical
fields through HTTP. Test-only controllers and resources must stay in `src/test`
and must never enter production artifacts.

## Functional module boundaries

Core must not absorb business domain logic. Future functional modules own their
domain, use cases, ports, persistence/migrations, HTTP endpoints, permissions,
resources and tests. No functional module may depend on removed examples.
`erbas.dev.math` and `erbas.dev.statistics` are exclusively conformance/testing
descriptors, registered only by explicit validation composition. They are never
business capabilities or functional dependencies. Normal composition includes
WMS UoM, Storage Attributes, Item Families and AI-001 READ orchestration through
the standard module pipeline. AI executes business tools only through CORE-CAP-002;
the default provider is unavailable and the deterministic provider is validation-only.
Further business capabilities and real AI providers require separately approved designs.

## Engineering simplicity

Prefer simple, explicit and maintainable solutions.

Apply these principles:

- **KISS** — keep solutions as simple as the requirements allow.
- **DRY** — avoid duplicated logic and duplicated sources of truth.
- **YAGNI** — do not build abstractions, extension points or infrastructure without a concrete current need.
- **Occam's razor** — when several solutions satisfy the requirements equally well, prefer the one with fewer concepts, dependencies and moving parts.
- **Reuse before invention** — prefer existing mechanisms, conventions and components before introducing new ones.

Do not introduce layers, helpers, factories, interfaces, services or abstractions merely for architectural symmetry or possible future use.

Simplicity must not compromise correctness, security, performance, clarity, testability or contractual behavior.

When duplication is small and removing it would create a more complex abstraction, prefer the clearer solution over mechanically applying DRY.

### No magic literals

Do not use magic literals.

Any literal with contractual, domain, protocol, configuration or behavioral
meaning must have one explicit and descriptive source of truth instead of being
scattered through implementation code, tests, templates, scripts or configuration.
This includes, where applicable:

- numeric and string values;
- endpoint paths, URLs and path fragments;
- HTTP header names, media types, HTTP/status/error codes and protocol tokens;
- state names, configuration keys and environment-variable names;
- contract field names and regular expressions with semantic meaning;
- timeouts, limits, sizes and thresholds;
- database/schema identifiers when they carry semantic meaning.

Prefer, in this order:

1. Existing framework/platform constants or enums.
2. Existing project/domain constants or types.
3. Existing configuration.
4. A narrowly scoped named constant or enum introduced for the concrete need.

Do not introduce local constants that merely duplicate an available and clearer
framework facility, such as HTTP status, header or media-type abstractions.
Examples of meaningful values that should not be scattered:

```text
USERS_PAGE_SIZE = 50
USERS_ENDPOINT = "/api/users"
JSON_MEDIA_TYPE = "application/json"
```

Do not mechanically replace every literal with a constant. A literal may remain
inline when it has no independent semantic meaning, its value is obvious from
the immediate local structure, naming it would reduce clarity, or it is incidental
fixture data whose exact value is irrelevant. Examples include simple array
indexes, trivial loop counters, structural zero/one values without domain meaning,
and arbitrary test values used only as distinct fixtures. Do not introduce
pointless constants such as `ZERO`, `ONE` or `TRUE` without a named domain or
protocol concept.

The objective is to eliminate hidden meaning and duplicated sources of truth,
prevent accidental inconsistencies, and improve readability and maintainability.
It is not a global constants catalog, needless abstraction, or replacement of
every string or number. Apply KISS, DRY, YAGNI, Occam's razor and reuse before
invention; this rule must reinforce simplicity.

Tests are subject to the same rule for production, contractual, protocol or
domain behavior. Do not duplicate production-significant literals unnecessarily:
prefer an exported `USERS_PAGE_SIZE` when asserting the production page size.
A deliberate contractual boundary such as 100 may use a named test/contract
constant when that improves semantic clarity. Arbitrary fixture values may
remain local. Do not couple tests unnecessarily to implementation internals;
correctness and test independence remain important.

The shared contract standardizes observable behavior, not implementation
internals. Each stack may represent contractual values idiomatically in its own
constants, enums or types. This rule does not make erbas-contract a general
constants package or require shared cross-language source-code constants or
generated constants infrastructure.

During scope review, explicitly inspect newly introduced meaningful literals and
confirm that no magic literals were added.
