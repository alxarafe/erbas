# WMS-003J Java verification

Verified on 2026-10-16 from exact main
`1abc7c36f3bd3acb425b510705509bdb8bd2f290`, on
`feature/wms-003-java-item-families-v0.8.0`. Initial fetch confirmed HEAD and
origin/main were identical, with a clean index and working tree.

## Immutable contract authority

- `contract.revision`: `v0.8.0`.
- Annotated tag object: `303a61b0207d3984780539da4068ab68c1264a8d`.
- Target commit: `9dcb1605bcddbd450d4c009285313a142ab9fb35`.

OpenAPI and the sole Bruno collection at this release were read before
implementation. A separate clean, detached release checkout was supplied to
validation. Neither the original contract repository nor its files were changed.
Java's preflight verifies root, GitHub origin, cleanliness, tag object, peeled
commit and matching HEAD before provisioning and before shared conformance.
CI selects the same published release. No main-based contract consumption is used.

## Implementation and boundaries

WMS adds only ItemFamily POST/GET and association GET/POST/PUT/PATCH/DELETE.
Families are closed `{id, code, name}` catalogues with server-generated non-nil
canonical UUIDs, exact `^[A-Z][A-Z0-9_]{0,29}$` codes and normalized user names.
`CatalogueValues` shares code validation with StorageAttribute, UUID handling,
four-character ASCII boundary normalization and Unicode scalar validation.
UoM retains its distinct code rule. `CatalogueHttp` supplies the same strict,
duplicate-aware JSON parser, pagination and response mechanics to all catalogues.
No alternate parser or catalogue envelope is introduced.

`FamilyAssociations` evaluates commands against an immutable pre-operation
snapshot. It reports missing family first, then missing master references in
request order (add before remove), before evaluating any state conflict. PATCH
reports every selected-stage conflict; overlap across its arrays is structurally
valid. No accepted subset is applied on error. WMS HTTP adapters map those issues
to their own technical codes, resource context and exact JSON Pointer locations.
Association creation returns the complete existing attribute and exact relative
Location. Collections are ordered by ordinal code in PostgreSQL before slicing.

`JdbcFamilyAssociationStore` locks the owning family row with `FOR UPDATE`, even
when its relation is empty. The snapshot, decision and accepted delta execute in
one transaction. This serializes concurrent operations for that family without
locking unrelated families. Read-only repeatable-read windows keep existence,
count and page consistent. Composite uniqueness and foreign keys enforce pair
integrity. Removing/replacing pairs never deletes masters.

All functional code, SQL and Spring composition belong to `modules/wms`.
Dependencies remain host → CORE/WMS and WMS → CORE. The host production code,
Maven module graph, module identity/version and installed-versus-disabled pipeline
are unchanged. WMS contributes authorization through the existing generic
security extension and `PlatformAuthorities.ADMINISTRATOR`.

## Global error representation

CORE's generic `ContractError` owns only the closed `{code, errors}` shape, with
nonempty details requiring code and optional resource/resourceId/path. Auth,
Users, Modules, bearer security, module gating, UoM and StorageAttribute now emit
that shape. Their existing statuses, technical codes, success responses and
header behavior remain unchanged. WMS maps its own association problems; CORE
contains no ItemFamily, relation, path, persistence or WMS error-code knowledge.
No message, timestamp, duplicated status or internal diagnostic is added.
Existing implementation-specific i18n validation and Actuator behavior remain
separate from the shared API. English/Spanish checks preserve technical fields
and persisted user content.

## Migration and persistence evidence

`modules/wms/src/main/resources/db/wms/V7__wms_item_families.sql` creates:

- `wms_item_families`: UUID primary key, non-null name and unique non-null
  C-collated code.
- `wms_item_family_storage_attributes`: two non-null foreign keys, composite
  primary key and an index on the attribute reference.

V1–V6 were compared byte-for-byte with the exact task base and are unchanged.
CORE still owns only V1–V4. WMS owns V5–V7.

Fresh native and API PostgreSQL databases start empty, migrate through V7 and
start the real host. Upgrade tests retain the historical V1–V5 case and add
V1–V6, using immutable migration fixtures. Existing history rows, checksums and
installation metadata compare equal after startup; new migrations occur once
and subsequent migrate calls execute zero migrations. Authentication users,
password hashes/tokens, module state, CORE marker, UoM identity/content/table OID
and StorageAttribute identity/Unicode content/table OID are preserved. Login,
existing catalogues and new family/association HTTP operations remain usable.
No repair, reset, baseline or history rewriting is used.

Concurrent family creation produces exactly one 201, one 409 and one row.
Concurrent pair creation produces one 201, one closed contractual 409 and one
pair. Concurrent replacements remain complete sets; concurrent strict patches
retain both valid additions. PUT/PATCH validation failures are followed by GETs
proving the original set remains unchanged. A temporary PostgreSQL constraint,
restricted to one fixture family and removed in finally, additionally forces an
insert failure after original-pair deletion: the transaction rolls back the
whole replacement and a subsequent HTTP GET still returns the original set.

## Executed validation

```bash
ERBAS_CONTRACT_DIR=/tmp/wms-003j-contract-v080 ./bin/check
```

Final run: `erbas-check-1134054-25775-16273`, exit **0**, **248 seconds** wall time.
A preceding complete run also passed in 242 seconds; the final run additionally
includes the database-write rollback case. An earlier new-test compilation
ambiguity was corrected before these successful validations.

| Check | Final result |
| --- | --- |
| Entire Maven reactor and fresh native execution | 360 passed, zero failures/errors/skips |
| CORE independently: `-pl erbas-core clean verify` | 227 passed without selecting WMS or host |
| WMS native suite | 125 passed, including 38 ItemFamily domain/catalogue and 13 association/UUID cases |
| Host native suite | 8 passed, including 7 architecture/migration-integrity guards |
| Integration profile | 123 passed: 121 PostgreSQL/HTTP tests and 2 packaged-artifact guards |
| New catalogue HTTP/PostgreSQL suite | 7 passed |
| Association HTTP/PostgreSQL suite | 15 passed |
| Fresh and historical upgrade host cases | All 3 passed |
| Shared live Bruno against the ephemeral Java host | Complete 494 requests / 2190 named checks passed |
| Published inventory/order guard | Passed separately in Docker without network; one collection and contiguous unique sequences |
| OpenAPI validation | Passed unchanged through the published runner |
| Final runtime image | One executable host JAR, UID 10001, port 8080, existing healthcheck; no Maven, javac or sources |
| Runtime/operational behavior | Read-only application configuration retained; healthy startup and Actuator 200, then process Health 200 and Actuator 503 with PostgreSQL stopped |
| Secrets and cleanup | LOG_SECRECY passed, CLEANUP_STATUS passed, CLEANUP_EXIT=0, CHECK_EXIT=0 |
| Diff and shell | `git diff --check` and `bash -n bin/check` passed |

The base's 305 native and 100 integration-profile tests are retained. This task
adds 55 native cases and 23 integration-profile cases. Existing Health, Auth,
Users, Modules, UoM, StorageAttribute and i18n tests pass with only the required
closed-error representation expectations changed. Architecture guards are
extended, not reduced, to cover WMS business knowledge in generic CORE errors,
pinned V6 fixture integrity, V7 ownership and fixture exclusion from runtime.

All Java builds/tests and runtime checks used Docker and real PostgreSQL.
No timeouts were increased. Existing non-fatal Maven metadata authorization and
deprecated-API warnings did not prevent successful builds; no dependency or image
version changed. No .NET service or frontend was tested or changed.

## Documentation and scope review

Current README, documentation index, usage, modularity, i18n/error handling and
working-agreement contract references were updated. This report is new.
Historical reports and ADRs are unchanged. Source/resource moves, new Maven
modules and framework changes were unnecessary. Meaningful literals use existing
platform facilities or scoped WMS/error definitions; no unrelated refactor or
configuration change is included.

No contract/OpenAPI/Bruno files were changed, copied or excluded. The complete
published collection ran without backend-specific branches. There is no WMS-004,
Item/product, warehouse, stock, master update/delete, new module/version/RBAC,
frontend, .NET, AI, events, messaging, dynamic plugin loader or separate database.
No push, PR/MR, merge, tag, release, publication or deployment was performed.
