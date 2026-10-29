# Effective capability discovery (CORE-CAP-001)

Java formally adopts published immutable **erbas-contract v0.9.0**:

- Annotated tag object: `adcfad11c6fe51cb54a92a93f9c1e99a13fb7dc8`.
- Target commit: `138e2381e1010e994a0f9d6306af6f9d420658e5`.
- Normative [design](https://github.com/alxarafe/erbas-contract/blob/v0.9.0/docs/core/capabilities-001.md),
  [OpenAPI](https://github.com/alxarafe/erbas-contract/blob/v0.9.0/openapi/core/capabilities/contract.yaml)
  and [WMS declarations](https://github.com/alxarafe/erbas-contract/blob/v0.9.0/openapi/modules/wms/capabilities.json).

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
ERBAS_CONTRACT_DIR=/path/to/clean-v0.9.0-checkout ./bin/check
```

The same immutable release is selected by CI. Native tests cover descriptor
validation, duplicates/ownership/schema targets, fail-fast Spring registration,
all-permission filtering, stable ordering, missing/disabled modules and exact WMS
declarations. Real HTTP/PostgreSQL integration covers closed descriptors,
pagination and authentication precedence, ordinary users, current demotion and
real module PATCH disable/restore. Full shared conformance preserves v0.8.0 and
adds CORE-CAP-001. The release inventory is 522 requests/2315 named checks; these
are distinct from Java native/integration test counts. Normal cleanup, migration,
architecture and sensitive-log checks remain mandatory.

Local verification on 2026-10-29 passed **573 distinct Java tests**: 419 native
and 154 real PostgreSQL integration cases, 89 more than the previous 484-case
baseline. The independent Core build also passed. Published v0.9.0 shared
conformance, migrations, existing WMS/auth/Users/modularity/architecture,
database-down liveness, sensitive-log checks and disposable infrastructure
cleanup all passed (`CHECK_EXIT=0`). This is local verification, not a claim
about a CI run for the unpushed commit.

**Capability execution is explicitly outside v0.9.0 and this implementation.**
There is no invoker/executor, invocation endpoint, handler binding, execution
audit/context, Assistant, tool calling, READ primitive, tenant, DataScope,
Resource Runtime, navigation or Assets feature. Discovery describes existing
operations; capability IDs are not executable URLs.
