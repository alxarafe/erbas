# CLEANUP-001: Core and canonical development modules

## Audit and scope

Started from clean main at fb1cf9e (I18N-001 merged), updated by fast-forward.
The empty feature/contact-002 branch was deleted locally. This task uses
feature/cleanup-001-remove-experimental-modules.

Java had no Catalog or AiAgent domain, functional endpoints, permissions,
repositories, migrations, resources or suites to remove. No business module was
invented for symmetry. The obsolete concrete example in modularity documentation
was generalized, and the ecosystem README now reflects current .NET Core adoption.
AGENTS.md permanently forbids business logic in Core and functional dependencies
on the canonical dev descriptors; future modules own their complete boundaries.

## Preserved architecture and contract

Normal Java composition has zero modules. Explicit validation profile plus
erbas.modules.conformance.enabled registers only erbas.dev.math and
erbas.dev.statistics (Statistics depends on Math). They have no functional API.
ModuleSource/Registry/Graph/Runtime, persistent state, dependency conflicts,
atomic enable/disable and HTTP gating remain unchanged.

Health/Auth/Users/Modules, JDBC/Flyway, bearer/admin security, native Jakarta
Validation and MessageSource, Accept-Language and English fallback are preserved.
No migration, production code, test or dependency was changed. Existing v0.5.0
closed errors retain their exact shape.

contract.revision remains v0.5.0. The clean supplied release checkout was verified
at 35be07fe002c364f758ae50419d0ecb86d1f0c43. erbas-contract was not modified.

## Authoritative verification (2026-10-13)

```bash
ERBAS_CONTRACT_DIR=/tmp/erbas-modules-001e-contract-v0.5.0 ./bin/check
```

Run erbas-check-1518939-25910-403 exited 0: RESULT=passed, CHECK_EXIT=0,
CLEANUP_STATUS=passed and CLEANUP_EXIT=0. All 305 tests passed: 225 native and
80 real PostgreSQL/HTTP integration, with zero failures, errors or skipped.
No tests were deleted or transferred. Existing coverage includes canonical
composition, registry/graph/runtime, persistent dependencies/atomicity, dynamic
gating and security precedence, CORE recovery, and HTTP locale/validation probes.

Full shared conformance passed: 130 requests / 506 named checks. Image build,
clean migrations, credential/state invariants, log secrecy, database-down Health
and exact owned-resource cleanup passed. Development storage was not provisioned
or removed. Existing Mockito/JDK dynamic-agent warnings remain unrelated to this
documentation/governance change.

git diff --check passed. Meaningful literals added are documentation of existing
canonical IDs/configuration, not new runtime values. Remaining broad search hits
are ordinary words such as translation catalog or a future AI scope; no retired
module implementation exists. WMS and new AI remain outside this task.

## Files and limits

Changed AGENTS.md, README.md, docs/README.md, docs/modularity.md and this report.
No files/projects/migrations were removed. No OpenAPI or Bruno was copied or
changed; no revision bump, push, PR, merge, tag, release or deployment occurred.
The repository is prepared for a separately approved WMS-001 design.
