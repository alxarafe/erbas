# MODULES-001E-JAVA: published contract v0.5.0 adoption

## Result and immutable contract identity

Java declares `v0.5.0` in `contract.revision`. The release tag and validation
checkout HEAD both resolved to:

```text
35be07fe002c364f758ae50419d0ecb86d1f0c43
```

On 2026-10-13, the complete Docker validation passed against a separate clean
checkout of the published tag:

```bash
ERBAS_CONTRACT_DIR=/tmp/erbas-modules-001e-contract-v0.5.0 ./bin/check
```

`bin/check` still verifies the repository root, GitHub origin, exact immutable
tag target, matching HEAD and tracked/nonignored cleanliness before provisioning
and immediately before shared conformance. CI explicitly checks out `v0.5.0`
and uses the same authority. No following of contract `main`, unpublished fallback,
contract copying or modification of the supplied contract repository is introduced.
An additional preflight probe against a checkout whose HEAD differed from the
release correctly exited 2 before creating validation resources.

## Implemented behavior

`ModuleController` exposes CORE `GET /api/modules` and
`PATCH /api/modules/{id}`, without functional module gating. The existing bearer
filter and central `ADMIN` authority run before body/query validation or lookup.
AUTH/USERS observable behavior remains unchanged.

The closed Module DTO contains exactly descriptor ID, Module API version, sorted
dependency IDs and freshly read persistent enabled state. Availability and
case-sensitive ID order come from `ModuleRegistry`, not historical state rows.
The extracted `PagedCollection` record and pagination parser serve both Users
and Modules with the existing defaults, bounds, arbitrary-size offsets and closed
COLLECTIONS-001 metadata.

PATCH strictly accepts only a boolean enabled value. Narrow unavailable and
dependency-conflict exceptions map to the exact contract 404/409 responses;
malformed bodies/pagination map to 400. Security retains exact 401/403 responses,
with a Bearer challenge only on 401. All specified responses are JSON and no-store.
Missing state and unexpected failures are not mapped to 404/409.
`ModuleStateService` and the unchanged JDBC store remain the transactional
authority. Snapshot validation precedes writes, preserving atomicity, idempotence
and dependency rules without cascades.

`ConformanceModulesConfiguration` contributes one source only with the
`validation` profile and the explicit property
`erbas.modules.conformance.enabled=true`
(`ERBAS_MODULES_CONFORMANCE_ENABLED`, default false). Validation Compose sets
both. The source contributes precisely Math and Statistics with their centralized
canonical IDs and required dependency, using `ModuleApi.CURRENT_VERSION`.
Normal/default/production composition has no canonical modules and supports
zero modules. Descriptors remain unchanged; these modules have no functional
endpoints or mathematical/business behavior.

The normal registry/graph/runtime pipeline synchronizes their missing state rows
as initially enabled before initialization. No manual canonical DB seed, hidden
endpoint, reset API, loader or module bus is introduced.

## Validation and coverage

| Suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| Native unit/MVC/context tests | 213 | 0 | 0 | 0 |
| Native PostgreSQL integration tests | 80 | 0 | 0 | 0 |
| Total distinct Java test executions across those suites | 293 | 0 | 0 | 0 |

The native suite also runs during the existing Docker image build; it is counted
once in this table. The final check separately reran it in a fresh container.

New coverage comprises 46 MVC cases, three composition tests, seven real HTTP
administration tests and one additional PostgreSQL state regression. The existing
application context test now verifies normal zero-module startup; the functional
gating integration test also verifies that CORE module listing remains accessible
while its functional module is disabled.

Coverage includes closed representations/errors, zero and canonical modules,
natural case-sensitive ordering before pagination, sorted dependencies, defaults,
boundaries and beyond-total offsets, invalid pagination/bodies, current enabled
reads, unavailable historical rows, idempotence, both dependency conflicts,
unchanged persisted snapshots after conflicts, security precedence and headers,
and CORE recovery with every module disabled. Actual synchronization is checked
before any HTTP mutation. Missing dependency state remains an internal failure,
with no partial persisted change.

Shared `bin/test URL` from the published `v0.5.0` checkout passed with exit 0:
the complete 130-request / 506-named-check collection, including unchanged
Health/AUTH/USERS/COLLECTIONS behavior and CONTRACT-003/003A. No Java-specific
Bruno collection or copied OpenAPI exists. The supplied contract and the separate
release checkout remained clean.

The final validation run was `erbas-check-411051-32563-25042`:

```text
CONTRACT_PREFLIGHT=passed PHASE=initial
CONTRACT_RUN=success PHASE=database-up EXIT=0
POST_CONFORMANCE_DATABASE=verified
HEALTH_RUN=success PHASE=database-down HTTP=200 JSON=exact
LOG_SECRECY=passed
CLEANUP_STATUS=passed CLEANUP_CODE=0
CLEANUP_EXIT=0
CHECK_EXIT=0
```

Both PostgreSQL instances started with empty public schemas, migrated from
scratch and remained separate from development. API state was disposable.
Owned containers, networks, volumes and temporary image tags were removed;
success was reported only after zero remaining inventories. The earlier complete
run also passed (213 native / 79 integration tests), before adding the missing-state
regression.

Maven emitted nonfatal optional vendor metadata HTTP 401 warnings, and Mockito
emitted the existing dynamic-agent warning. Deliberate negative startup/gating
tests also emitted expected exception diagnostics. Builds and every validation
stage passed; no dependency, image or warning-suppression changes were made.

## Scope and documentation review

The reviewed change is limited to Java module administration, explicit
conformance composition, reuse of pagination, precise domain-error classification,
native tests, the published release pin/CI configuration and necessary documentation.
New meaningful literals use named scope-local constants or existing HTTP/platform
facilities: canonical IDs, profile/property, routes, error codes, pagination
thresholds and lexical/order policy. Test fixture values and structural literals
remain local where their meaning is clear.

`git diff --check`, `bash -n bin/check` and local documentation link checks passed.

Public current-state references were updated in README, usage, modularity,
documentation index and the working agreement. Prior v0.4.0 verification reports
remain historical and unchanged. No other repository was modified.

Files in the task:

```text
.github/workflows/ci.yml
AGENTS.md
README.md
bin/check
contract.revision
docker/compose.validation.yaml
docs/README.md
docs/modularity.md
docs/usage.md
docs/verification/modules-001e-java.md
src/main/java/com/alxarafe/erbas/adapter/http/PagedCollection.java
src/main/java/com/alxarafe/erbas/auth/http/UserController.java
src/main/java/com/alxarafe/erbas/auth/infrastructure/AuthenticationConfiguration.java
src/main/java/com/alxarafe/erbas/modularity/ConformanceModulesConfiguration.java
src/main/java/com/alxarafe/erbas/modularity/ModuleDependencyConflictException.java
src/main/java/com/alxarafe/erbas/modularity/ModuleStateService.java
src/main/java/com/alxarafe/erbas/modularity/ModuleUnavailableException.java
src/main/java/com/alxarafe/erbas/modularity/http/ModuleController.java
src/main/java/com/alxarafe/erbas/modularity/http/ModuleExceptionHandler.java
src/main/resources/application.properties
src/test/java/com/alxarafe/erbas/ErbasApplicationTests.java
src/test/java/com/alxarafe/erbas/ModuleAdministrationIT.java
src/test/java/com/alxarafe/erbas/ModuleGatingIT.java
src/test/java/com/alxarafe/erbas/ModuleStateIT.java
src/test/java/com/alxarafe/erbas/modularity/ConformanceModulesConfigurationTest.java
src/test/java/com/alxarafe/erbas/modularity/http/ModuleControllerTests.java
```

No AUTH/USERS behavior change, mathematical functionality, business module,
external plugin/hot loader, install/uninstall API, .NET or Angular change is included.
Local validation and commits do not authorize push, PR creation, merge, tags,
release or deployment.
