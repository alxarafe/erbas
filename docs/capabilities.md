# Effective capability discovery and READ invocation (CORE-CAP-001/002)

Java introduced CORE-CAP-002 against published immutable **erbas-contract v0.10.0**.
Its behavior remains unchanged under the current [v0.13.0 adoption](resources.md).
The original CORE-CAP-002 publication identity is:

- Annotated tag object: `a55a8d25beef5c24bc9cdce956a2eb2b14f41fb3`.
- Target commit: `1b7b182374ad432637dd41f46b94b4004470eba0`.
- Normative [design](https://github.com/alxarafe/erbas-contract/blob/v0.10.0/docs/core/capabilities-002.md),
  [OpenAPI](https://github.com/alxarafe/erbas-contract/blob/v0.10.0/openapi/core/capabilities/contract.yaml)
  and [WMS declarations](https://github.com/alxarafe/erbas-contract/blob/v0.10.0/openapi/modules/wms/capabilities.json).

The tagged preparation documentation retains its historical pre-publication
wording; the published annotated tag is immutable. This adoption preserves all
v0.8.0 APIs. Previous verification reports remain historical.

## Architecture and registration

Provider modules contribute immutable `Capability` records through explicitly
composed Spring `CapabilityProvider` beans. CORE `CapabilityRegistry` validates
owners against the existing available `ModuleRegistry`, schema targets and global
ID uniqueness, then snapshots declarations in ordinal ID order. Invalid records,
unknown owners/targets and duplicate IDs abort startup, consistently with module
registration; no last-registration-wins behavior exists.

The descriptor has exactly id, module, version, name, description, effect,
inputSchema, outputSchema and requiredPermissions. ID and version follow the
published grammars. Schema references have exactly `$ref` and permit only local
`#/components/schemas/Name` references. Permissions must be unique and ordered;
effect is only read/write. Neither field grants authority or associates an
executable handler. Stable declaration names/descriptions remain English.

The Host packages only a component-name index from the published OpenAPI root
document in `contracts/capability-schema-components.txt`. `bin/check` compares it
exactly against the supplied immutable checkout before provisioning and before
shared conformance. CORE receives that immutable name catalogue without WMS
knowledge. No schema bodies, copied OpenAPI/Bruno collection, HTTP schema server,
inline schemas or remote resolver are introduced.

## WMS ownership and v0.9 permission policy

`WmsCapabilities` owns exactly these declarations, all version `1.0.0`/effect `read`,
owner `erbas.wms`, input ref `CapabilityListInput`:

| ID | Stable name | Output schema component | Required permission |
| --- | --- | --- | --- |
| wms.uoms.list | List units of measure | WmsUomCollection | wms.uoms.read |
| wms.storage-attributes.list | List storage attributes | WmsStorageAttributeCollection | wms.storage-attributes.read |
| wms.item-families.list | List item families | WmsItemFamilyCollection | wms.item-families.read |

Descriptions are exactly the published existing GET-route descriptions. A native
WMS test compares all nine fields against the release's normative declaration
fixture. There are no create/rotation/association-write capabilities.

The module also supplies its `CapabilityPermissionPolicy`: current enabled
administrators possess precisely these three functional permissions; ordinary
authenticated users possess none. Unknown permissions are not granted. CORE
checks every required permission through this policy seam, so a later approved
permission source can change without rewriting discovery. No grants, roles,
claims, token fields or permission assignment persistence/API is added.

Existing bearer lookup already resolves current persisted enabled/admin state
on each request. Demotion therefore affects the next discovery with the same
token. `ModuleStateService` reads persisted state on each discovery; disabling
WMS immediately hides its descriptors, restoring it immediately returns them.
Unavailable modules are hidden. Missing persisted state remains an internal
configuration error, not a falsely successful disabled state. CORE discovery
itself is not module-gated and stays accessible with an empty effective catalogue.

## HTTP

- `GET /api/core/capabilities`: bearer required; filter module/permissions before
  count/order/pagination. COLLECTIONS-001 items/offset/limit/total/order, id ASC,
  defaults 0/50, limit 1–100. Unknown/repeated keys, empty/whitespace/fraction/exponent
  values and invalid bounds are 400. Very large offsets safely return an empty page.
- `GET /api/core/capabilities/{capabilityId}`: exact case-sensitive identity, no
  query. Invalid syntax/query is 400; unknown, unauthorized, disabled and unavailable
  descriptors share 404 `capability_not_found` without existence hints.
- Authenticate before query/ID validation. Missing/invalid/expired/disabled-user
  tokens return 401 even for invalid input. Normal users receive 200 empty lists,
  never 403, and masked 404 details.
- Success/error objects are closed JSON and no-store. Only 401 carries the Bearer
  challenge. Detail equals its collection entry. No implementation/policy/role
  fields are exposed.

Existing WMS HTTP endpoints retain their administrator authorization, persistence,
transactions, pagination and dynamic module gates. No WMS catalogue refactor or
migration to a different runtime is involved.

## Validation and scope

Run the normal complete check with an explicit clean published checkout:

```bash
ERBAS_CONTRACT_DIR=/path/to/clean-v0.13.0-checkout ./bin/check
```

The same immutable release is selected by CI. Native tests cover descriptor
validation, duplicates/ownership/schema targets, fail-fast Spring registration,
all-permission filtering, stable ordering, missing/disabled modules and exact WMS
declarations. Real HTTP/PostgreSQL integration covers closed descriptors,
pagination and authentication precedence, ordinary users, current demotion and
real module PATCH disable/restore. Full shared conformance preserves v0.8.0 and
adds CORE-CAP-001/002. The original v0.10.0 inventory is 560 requests/2467 named checks; these
are distinct from Java native/integration test counts. Normal cleanup, migration,
architecture and sensitive-log checks remain mandatory.

Local verification on 2026-10-29 passed **573 distinct Java tests**: 419 native
and 154 real PostgreSQL integration cases, 89 more than the previous 484-case
baseline. The independent Core build also passed. Published v0.9.0 shared
conformance, migrations, existing WMS/auth/Users/modularity/architecture,
database-down liveness, sensitive-log checks and disposable infrastructure
cleanup all passed (`CHECK_EXIT=0`). This is local verification, not a claim
about a CI run for the unpushed commit.

## READ invocation (CORE-CAP-002)

`POST /api/core/capabilities/{capabilityId}/invocations` accepts exactly
`{"input":{}}`, with optional numeric integer `offset` and `limit` inside input.
Defaults and bounds reuse `PagedCollection`: offset 0, limit 50, maximum 100.
Both objects are closed. Duplicate keys, trailing JSON, missing/null/non-object
input, caller security fields and invalid pagination return `400 invalid_request`.
Integral JSON numbers such as `1.0` retain integer semantics; fractions do not.

Spring bearer authentication runs first, without an administrator route guard.
CORE validates ID syntax, calls the same `CapabilityDiscovery.findEffective`
policy as discovery, and reevaluates current module availability and permissions.
Only then does a deferred body supplier read and strictly parse JSON. Normal users,
unknown capabilities and unavailable/disabled providers receive the identical
`404 capability_not_found`, even for malformed bodies. Missing/invalid/expired or
disabled-user authentication remains `401 unauthorized`, before ID/body checks.
Discovery never grants lasting authorization. Existing direct WMS disabled-module
behavior remains `503 module_disabled`.

Descriptors remain immutable metadata with no executable fields. Spring explicitly
composes three provider-owned `WmsListInvocation` beans. CORE's
`CapabilityInvocationRegistry` rejects duplicate handlers, missing READ handlers,
unknown descriptors, mismatched ownership and non-READ bindings at startup.
The WMS beans call `UomCatalogue`, `StorageAttributeCatalogue` and
`ItemFamilyCatalogue` directly, then their stores and PostgreSQL; no controllers
or HTTP loopback participate. They reuse `PagedCollection` and the direct routes'
code-ascending order. All v0.9 descriptor fields, versions and schema refs remain
unchanged. No WMS business or pagination algorithm is duplicated.

CORE generates a fresh opaque invocation ID using UUID internally. Its trusted
`CapabilityInvocationContext` contains that ID, the authenticated subject, exact
capability ID, resolved owning module, effective granted permissions and
`caller=user`. Correlation is unavailable (null); no public correlation header or
client context is introduced. Only a successful closed envelope exposes
`invocationId`, `capabilityId` and `output`; identifier format is not contractual.

The provider validates descriptor input and exposes a generic output-validation
hook. WMS requires final validated domain records, a complete page matching the
requested pagination, nonnegative total, unique ascending codes and contractual
order. CORE rejects known invalid output, null/non-object output, provider exceptions
and serialization failures with exactly `500 capability_execution_failed` and
one matching error detail. Exceptions and internal invocation IDs never enter
error responses. Every response is JSON/no-store; only 401 has a Bearer challenge.

`CapabilityInvocationAuditSink` receives an entry for completed, denied and failed
attempts, including authentication rejection. Its default implementation logs only
the internal ID, available capability/module/subject, timestamp, duration, outcome
and safe error code. Correlation and unresolved/hidden metadata remain unavailable;
audit does not look up hidden metadata. Invalid IDs are omitted from audit.
The audit type has no payload, credentials, token, SQL or exception fields. No audit
API, persistence table or migration is added.

Execution is READ-only. WRITE invocation, Assistant, AI providers, tool selection,
READ primitives, persisted functional permissions, tenant, DataScope, Resource
Runtime, navigation and Assets remain outside this CORE implementation. The
separately composed [AI module](ai.md) now consumes this READ execution service.

## CORE-CAP-002 local verification

On 2026-10-30, from Java base `578ff2a2e7646035a93b0fb6160ca73489f5afd9`,
the complete command below passed with `CHECK_EXIT=0` (run
`erbas-check-44069-19557-2572`):

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract-v0.10.0 ./bin/check
```

There were **650 distinct Java tests**: 465 native and 185 real PostgreSQL
integration cases, 77 more than the 573-case baseline. Independent CORE validation
also passed (328 cases, not counted twice). Native coverage includes registration,
trusted context, generated IDs, reauthorization, strict parsing, safe provider/output
failures and all audit outcomes. HTTP/PostgreSQL coverage compares all three WMS
outputs with their direct GET results for defaults and offset=1/limit=1, and checks
authentication precedence, masked malformed requests, persisted user demotion and
module disable/restore without restart. The former assertion that no invocation
endpoint exists is replaced with the now-approved execution/equivalence test.

Published v0.10.0 shared live conformance passed separately from the Java cases.
The official read-only inventory test independently measured **560 requests /
2467 named checks**, including inherited folder checks; it passed with no skips.
Final image, fresh migrations, post-conformance database invariants, database-down
liveness, Actuator behavior, sensitive-log checks and exact-owned ephemeral
container/network/volume cleanup all passed. No migrations or dependencies were
added. The initial full attempt exposed an isolated MVC security-composition
dependency; it was corrected and CORE plus the complete check were rerun successfully.
Existing Mockito dynamic-agent, deprecated-test-API and optional Maven metadata
HTTP 401 warnings remain non-fatal; dependency resolution and all validations passed.
This is local evidence; remote CI, publication and deployment are separate.
