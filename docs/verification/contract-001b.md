# CONTRACT-001B verification

Verified locally on 2026-10-08, Linux amd64, with Docker 29.8.1 and Compose 5.5.1.
Only `erbas` was modified. No commit, push, PR, merge, tag, release, image
publication or persistent deployment was performed.

## Scope, identity and acceptance

The approved outcome is one Java GET /health, an explicit immutable one-field
record, native checks and real shared conformance with owned ephemeral resources.
Actuator retains its operational purpose. PostgreSQL and Flyway are not part of
the public probe's representation or runtime dependency after HTTP startup.

- Java starting HEAD: `3694724b3381d759b653dd7382335b33de463220`.
- Java work branch: `feature/contract-001b-java-health`; changes remain uncommitted.
- Contract origin: `git@github.com:alxarafe/erbas-contract.git`.
- Exact contract HEAD: `3ceade3a19a2545545cf58a2eb027d587554aac0`, main, clean.
- No contractual version is published; the commit is declared in contract.revision.
- Before branching, all four identities, branches, HEADs, working-tree states and
  tracked/nonignored file hashes were recorded in `/tmp/contract-001b-before.json`.
- Java, contract and client were initially clean. .NET had 12 unstaged tracked
  changes, 35 untracked files and zero staged changes, preserved throughout.

The implementation uses unchanged Java 25, Spring Boot 4.1.1 and Maven 3.10.0
(Wrapper 3.3.4). Java runtime reported 25.0.4.1; PostgreSQL reported 18.6. Shared
Bruno is 4.2.0 and Redocly 2.5.1. Existing Java image versions/digests are listed
in [usage](../usage.md); none was changed. The contract runner base remains
`node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94`.

## Commands

Executed with these explicit host settings:

```bash
export ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract
export ERBAS_CONTRACT_ALLOW_UNRELEASED=1
./bin/check
./tests/check-lifecycle.sh
./tests/check-lifecycle.sh concurrent
bash -n bin/check tests/check-lifecycle.sh
git diff --check
```

The initial implementation's lifecycle verification was repeated after hardening
the Git-status read. Its concurrency check was executed once. After final review,
the four authorized script corrections were verified with a new lifecycle run
and exactly one new concurrency run, detailed below. Concurrency is never part
of the ordinary bin/check path.

## Initial implementation results (before final-review corrections)

| Required check | Actual result |
| --- | --- |
| Existing context test | 1 passed, no failures/errors/skips |
| New focal MVC test | 1 passed; HTTP 200, exact JSON media type and strict parsed object |
| Regular native suite | 2/2 passed in a fresh Docker container, independent of build cache |
| Java integration | 2/2 passed with a separate clean PostgreSQL and actual Flyway/JDBC |
| Image build | Existing multi-stage Dockerfile built successfully; its native tests also passed |
| Empty databases | Both public schemas contained 0 tables before any application migration |
| Flyway | V1 successfully applied from scratch; no failed migration rows |
| Technical marker | Marker 1 present in native integration and deployed API database |
| Actuator | Native integration asserted HTTP 200/status UP; historical curl returned `{"status":"UP"}`, but the script printed 200 as a constant rather than measuring it |
| Shared Bruno, PostgreSQL available | HTTP 200, valid JSON media type, exact closed parsed body: 3/3 passed |
| Shared Bruno, PostgreSQL stopped | API PostgreSQL running=false; same collection: 3/3 passed |
| Startup timeout | Paused only owned app; configured 5-second wait exhausted; exit 124 propagated |
| Contractual failure | Stopped only owned app; Bruno reported `getaddrinfo EAI_AGAIN app`; overall exit 1, without the ordered stage checks now required |
| Cleanup after success/failure | Old script inventories were empty but excluded stopped containers; the corrected all-state inventories are verified below |
| Two concurrent checks | Both exited 0, distinct projects/networks/storage/image tags, separate cleanup |
| Host script syntax | bash -n passed for both scripts |
| Local documentation links | All targets resolved using pinned Node Docker with read-only source and no network |
| Whitespace | git diff --check passed; new files separately audited with no-index checks |
| Other repositories | HEAD, branch, status and tracked/nonignored file hashes unchanged |

The contract was checked for exact revision and tracked/nonignored cleanliness
before provisioning and immediately before each bin/test invocation. The source
checkout was never pulled, checked out, staged or edited. Git-ignored artifacts
are permitted and untouched. Preflight experiments for missing opt-in, missing
directory, wrong origin and unexpected arguments all returned 2 before resources
were created. No dirty-checkout fixture was injected into the read-only contract.

## Run and cleanup inventory

Each project created separate PostgreSQL containers `postgres-native-1` and
`postgres-api-1`, app container `app-1`, transient native test containers,
`<project>-network`, volumes `<project>_native_data` and `<project>_api_data`, and
local image tags `<project>-build` / `<project>-app`. Project containers, network
and volumes carried `org.erbas.validation.run=<project>` plus Compose identity.
The contract's public runner owned its own temporary containers and removed them.

| Project | Verification phase | Exit | Containers / networks / volumes remaining |
| --- | --- | --- | --- |
| `erbas-check-573745-13454-24517` | initial / normal | `0` | 0 / 0 / 0 |
| `erbas-check-583704-5123-5207` | initial-lifecycle / normal | `0` | 0 / 0 / 0 |
| `erbas-check-588646-1472-23153` | initial-lifecycle / startup-timeout | `124` | 0 / 0 / 0 |
| `erbas-check-592691-2896-22958` | initial-lifecycle / contract | `1` | 0 / 0 / 0 |
| `erbas-check-601133-29352-13303` | final-lifecycle / normal | `0` | 0 / 0 / 0 |
| `erbas-check-607933-25102-7383` | final-lifecycle / startup-timeout | `124` | 0 / 0 / 0 |
| `erbas-check-611868-9009-4748` | final-lifecycle / contract | `1` | 0 / 0 / 0 |
| `erbas-check-619298-14552-25254` | concurrent / normal | `0` | 0 / 0 / 0 |
| `erbas-check-619299-18680-20431` | concurrent / normal | `0` | 0 / 0 / 0 |

Every project's temporary image tags were removed. Final global ownership-label
inventories found zero validation containers/networks/volumes and zero contract
runner containers. Only local build cache and shared layers remain. No prune,
broad deletion, development port or external resource was used.

A read-only inventory during verification recorded unrelated resources; final
comparison preserved their exact IDs, names and labels: 51 containers, 17 networks
(including erbas_default), and 44 volumes. Their health/restart state was not
asserted, because those unrelated services run independently. No task command
stopped or removed them.

## Evidence artifacts

Full local console output is retained outside repositories:

- `/tmp/contract-001b-first-check.log`
- `/tmp/contract-001b-lifecycle.log`
- `/tmp/contract-001b-lifecycle-final.log`
- `/tmp/contract-001b-concurrent.log`
- `/tmp/contract-001b-final-git.json`
- `/tmp/contract-001b-final-docker.json`

Harness logs also remain in the temporary evidence directories printed in these
logs. These are local artifacts, not published CI results. The table above records
the durable outcomes; logs may be removed independently by host temporary cleanup.

## Final-review corrections and verification

Only `bin/check`, `tests/check-lifecycle.sh` and this evidence document changed
during the correction pass. The pre-correction four-repository snapshot is
`/tmp/contract-001b-corrections-before.json`. No extra repository file was created.

The four corrections are:

1. Every container inventory uses `--all`, including collision checks, ownership
   checks, final cleanup verification and Compose container identity queries.
   Cleanup verifies labels for stopped containers before removing project resources.
2. Docker query output and exit status are captured separately. An empty output
   proves absence only after exit 0. Inventory, label/state inspection, image
   removal inventories and final image checks explicitly reject query failures.
   Cleanup reports its own result separately from the original check exit code;
   an original failure is preserved, and cleanup failure also prevents success.
3. Actuator's HTTP code is captured with curl's `%{http_code}`, checked against
   exactly 200 and printed as the measured value. There is no redirect following;
   the HTTP deadline is 5 seconds and the Docker command deadline is 15 seconds.
   A connection error fails the query; 3xx, 4xx and 5xx fail the exact-code check.
4. The real public `erbas-contract/bin/test` call has preflight, start and
   success/failure markers. The lifecycle harness requires the failure markers
   in order, equality between the runner exit and actual bin/check exit, and an
   independently successful cleanup. It does not reproduce contract assertions.

Executed with the same explicit contract directory and opt-in shown above:

```bash
./tests/check-lifecycle.sh
./tests/check-lifecycle.sh concurrent
bash -n bin/check tests/check-lifecycle.sh
git diff --check
ERBAS_CHECK_ROOT="$PWD" ERBAS_CHECK_RUN=erbas-check-static-audit \
  docker compose --env-file /dev/null --file docker/compose.validation.yaml config --quiet
env -u DOCKER_CONTEXT \
  DOCKER_HOST=unix:///tmp/erbas-001b-unavailable-daemon.sock ./bin/check
```

The lifecycle harness invoked the complete `./bin/check` once, then its public
startup-timeout and contract-failure exercises. The separate concurrency harness
invoked two complete checks simultaneously, once. Both harnesses exited 0, which
means their expected negative outcomes and cleanup assertions were satisfied.

| Correction-pass project | Exercise | Actual bin/check exit | Measured Actuator HTTP | All-state containers / networks / volumes / temporary image tags remaining |
| --- | --- | --- | --- | --- |
| `erbas-check-724770-26137-27715` | Complete check | 0 | 200 | 0 / 0 / 0 / 0 |
| `erbas-check-731616-24326-13224` | Startup timeout, 5 seconds | 124 | Not reached | 0 / 0 / 0 / 0 |
| `erbas-check-735879-2464-11628` | Shared contractual runner failure | 1 | 200 | 0 / 0 / 0 / 0 |
| `erbas-check-742199-29538-28961` | Concurrent complete check, first | 0 | 200 | 0 / 0 / 0 / 0 |
| `erbas-check-742200-19818-7147` | Concurrent complete check, second | 0 | 200 | 0 / 0 / 0 / 0 |

Every project passed the regular native suite (2/2) and real PostgreSQL integration
(2/2), with no failures, errors or skips. Both database schemas initially had
zero public tables. Image builds passed. The three complete checks each verified
Flyway V1 and marker 1, measured Actuator 200 with `{"status":"UP"}`, and passed
the shared Bruno tests 3/3 both before and after stopping only API PostgreSQL.
The contract-failure exercise also measured Actuator 200 and verified the API
migration/marker before stopping only its own application container.

The Java harness automatically verifies that contractual preflight passed, the
public `erbas-contract/bin/test` was invoked, it returned exit 1, Java propagated
that same exit, and cleanup completed successfully. The ordered markers below
prove those guarantees at the public runner boundary:

```text
CONTRACT_PREFLIGHT=passed PHASE=database-up
CONTRACT_RUN=start PHASE=database-up
CONTRACT_RUN=failure PHASE=database-up EXIT=1
FAILURE_SOURCE=contract-runner PHASE=database-up EXIT=1
CLEANUP_STATUS=passed CLEANUP_CODE=0 RUN_ID=erbas-check-735879-2464-11628
CHECK_EXIT=1 RUN_ID=erbas-check-735879-2464-11628
CONTRACT_FAILURE_VERIFIED=bin/test EXIT=1 CLEANUP_CODE=0
```

In this specific execution, the recorded output also showed successful OpenAPI
validation and Bruno attempting the `health` request, which failed with
`getaddrinfo EAI_AGAIN app`. Its summary showed `Requests: 1 (1 Failed)` and
tests/assertions 0/0 because the request failed. This observed output confirms
that Bruno was reached in this execution. Java does not parse that internal text
or make it part of the runner's stable public interface; it consumes `bin/test`
and its exit code.

The contractual repository owns the runner's internal behavior. Its own tests
must guarantee that `bin/test` correctly prepares and executes Bruno and maintain
stable exit-code meanings for consumers. Backend validation must not depend on
Bruno's internal output format.

With the inaccessible daemon, the actual bin/check exit was 1. Docker reported
`connect: no such file or directory`; the script printed `ERROR: Docker query
failed: container inventory ... (exit 1)`. The contractual preflight had passed,
but no runtime resource-creation stage was reached. The failed query was not
interpreted as an empty inventory. No daemon was stopped or reconfigured.

Final read-only Docker inventories included containers in all states. They found
zero task-labelled validation containers/networks/volumes, zero shared-runner
containers, and zero exact temporary image tags for all five new projects.
The before/after comparison preserved all 51 unrelated containers, 17 networks,
44 volumes and 54 images, including their recorded IDs, names, labels and image
tags. No prune or unrelated resource removal was performed. Build cache may remain.

Bash syntax, static Compose validation, whitespace checks (including untracked
files), executable modes and local documentation links passed. A final snapshot
comparison confirmed only the three authorized files changed in this pass and
no Java files were added or removed. The other three repositories retained their
HEADs, branches, Git states and tracked/nonignored contents, including .NET's
existing user changes. The seven excluded Java files remained byte-for-byte
unchanged. Nothing was staged or committed.

Correction-pass local evidence, outside all repositories:

- `/tmp/contract-001b-corrections-unavailable.log`
- `/tmp/contract-001b-corrections-lifecycle.log`
- `/tmp/contract-001b-corrections-concurrent.log`
- `/tmp/erbas-lifecycle.wWXM9l8s/` (normal, timeout and contract logs)
- `/tmp/erbas-lifecycle.OHRsOi6C/` (the two concurrent logs)
- `/tmp/contract-001b-corrections-docker-before.json`
- `/tmp/contract-001b-corrections-docker-final.json`
- `/tmp/contract-001b-corrections-git-final.json`

The contract stayed clean at `3ceade3a19a2545545cf58a2eb027d587554aac0` and was
rechecked immediately before each real runner call. No version was published.
These are local results, not continuous CI conformance; CONTRACT-002 is pending.

## Repository preservation and limits

Final Java HEAD remains its starting revision on the approved feature branch:
2 tracked documentation modifications and 11 new approved files, all unstaged.
The existing pom.xml, Dockerfile, development compose.yaml, application properties,
V1 migration, original test and workflow are byte-for-byte unchanged.

The other three repositories retain their branches, HEADs, states and contents:

| Repository | HEAD | Final state |
| --- | --- | --- |
| erbas-contract | `3ceade3a19a2545545cf58a2eb027d587554aac0` | main, clean |
| alxarafe-dotnet | `f4cf266b67b386327e9a78cda18d68934e088c18` | main; same 12 unstaged changes and 35 new files |
| erbas-client | `b187f4ae606d68cc28bf64663991b89ca0e7a683` | main, clean |

Maven emitted optional metadata-feed HTTP 401 warnings, and Mockito/Byte Buddy
reported dynamic-agent/CDS warnings on Java 25. All native checks passed without
suppressing them. Bruno emitted an OpenSSL certificate-directory warning; the
verified URLs were HTTP, and TLS was not tested. No dependency or excluded build
file was changed to address these existing tool warnings.

Cleanup is bounded per operation and fails if ownership/inventory cannot be
established. SIGKILL or an unavailable daemon can prevent traps; the printed exact
run identity supports scoped investigation. Existing CI still has its original
controls, including its unbounded readiness loop and nonasserting migration query;
mandatory bin/check integration and fixes there belong to CONTRACT-002.

All 18 required verification items were completed. No unauthorized file or scope
change was needed. .NET/Angular adaptation, joint closure, published contract
consumption and definitive CI remain separate tasks. Changes await review before
any commit or push authorization.
