# WMS-001J: Java UoM and contract v0.6.0

## Scope and contract identity

Java adopts the immutable published `erbas-contract v0.6.0` at
`92d1282154f340aadcfdf93f52a82a1f3472b335`. `contract.revision`,
the immutable preflight in `bin/check` and CI's explicit tag checkout agree.
Origin, root, cleanliness, tag target and matching HEAD checks remain mandatory;
Java never fetches, checks out or modifies the supplied contract.

The only new business operations are `POST /api/wms/uoms` and
`GET /api/wms/uoms`. No single-resource GET, update or delete was added.
Previous verification reports retain their historical revisions and results.

## Module and architecture

`WmsModule.ID` is the single production identity source for `erbas.wms`,
shared by its descriptor and `@ModuleEndpoint`. The descriptor uses
`ModuleApi.CURRENT_VERSION` with no dependencies. Normal production registers
the module through `SpringModuleSource -> ModuleRegistry -> ModuleGraph ->
ModuleRuntime`; synchronization and technical initialization use the existing
pipeline. Math and Statistics remain explicit validation-only descriptors.

The WMS package owns the framework-independent `Uom` model, `UomCatalogue`
application and persistence port, JDBC adapter, Spring composition and HTTP
adapter. The module contributes a native Spring Security `Customizer` using
the existing ADMIN authority; CORE consumes framework customizers without
knowing WMS types or paths. No new dependency, permission system, loader or bus
was introduced.

## Persistence and behavior

Flyway `V5__wms_uoms.sql` creates only `wms_uoms(id, code, description)`.
V1–V4 are unchanged. PostgreSQL UUID identity is server generated; code is unique,
NOT NULL and uses C collation for deterministic ASCII ordering. Description is
NOT NULL and stored normalized. `ON CONFLICT (code) DO NOTHING` handles concurrent
duplicates without exposing database diagnostics or overwriting the original.

Codes match exactly `^[A-Z][A-Z0-9]{0,9}$`, without trimming or uppercasing.
Description removes only edge ASCII space, TAB, CR and LF before checking
1–255 Unicode scalar values. NUL and isolated surrogates are rejected; padding
does not count toward the limit. Internal whitespace, case, other Unicode
characters and stored user content are preserved and never translated.

Strict JSON tree parsing follows the existing Users strategy, retaining exact
types, duplicate detection and trailing-token rejection without refactoring
existing parsers. POST accepts no query parameters. GET accepts only one offset
and one limit and reuses `PagedCollection.pagination`, its defaults and bounds.
The JDBC adapter orders by code before LIMIT/OFFSET and reads total and window
in a repeatable-read snapshot. At/beyond-total and arbitrarily large offsets
return an empty window with complete metadata.

Security order is bearer authentication, existing administrator authorization,
dynamic module gate, then validation/operation. Closed errors remain 400
`invalid_request`, 401 `unauthorized`, 403 `forbidden`, 409
`wms_uom_code_conflict` and 503 `module_disabled`. JSON/no-store and the
401-only Bearer challenge are preserved. CORE disable/re-enable takes effect
on the next WMS request without restart and retains catalogue data.
No human error fields or empty localization bundles were added.

## Verification (2026-10-14)

Executed from the Java checkout:

```bash
ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract ./bin/check
git diff --check
bash -n bin/check
```

The final complete run, `erbas-check-554540-32171-29010`, passed with exit 0.

| Check | Result |
| --- | --- |
| Native Java tests, Docker build and fresh test container | 261 passed; no failures, errors or skips |
| Real PostgreSQL integration | 88 passed; no failures, errors or skips |
| New WMS coverage | 36 native cases and 8 integration tests |
| Fresh native/API databases | Both started with zero public tables |
| Migrations | V1–V5 successful; CORE/auth/module-state and WMS tables verified; no failed migrations |
| V4-to-V5 upgrade | Existing module state and CORE tables preserved; migration repeat is a no-op |
| JDBC/HTTP | Normalized Unicode persistence, constraints, simultaneous unique-code inserts, ordinal ordering, pagination and strict JSON/query inputs passed |
| Security and gating | Real bearer/admin precedence, exact headers/errors and disable/re-enable with data preservation passed |
| Shared published Bruno collection | Exit 0; 182 requests / 714 named checks, retaining the previous 130 / 506 |
| Operational probes | Actuator 200 with DB up; exact Health 200 after stopping DB; Actuator 503 with DB down |
| Secrets and cleanup | Secret-log probe passed; ownership-checked cleanup passed with exit 0 and zero owned containers, networks, volumes or temporary image tags |
| Final source/shell checks | `git diff --check` and `bash -n bin/check` passed |

The shared runner intentionally withholds detailed Bruno diagnostics.
Request/check totals were independently counted from the unchanged published
collection. Native coverage includes exact code syntax, Unicode scalar boundaries,
edge trimming and application outcome/error propagation. Integration uses real
PostgreSQL and HTTP, including normal WMS-only composition. Existing Health,
AUTH, Users, Collections, Modules and I18N tests remain in complete validation.

An initial attempt stopped at test compilation because of ambiguous wildcard
imports; these were corrected, and its cleanup passed. A subsequent complete
run passed before the final security composition review; the final complete
run above revalidated that review. Maven emitted non-blocking metadata-feed
HTTP 401 warnings; builds and tests completed successfully.

## Scope review

No Item, ItemFamily, Warehouse, Location, Inventory, Stock, Handling Unit, AI,
new RBAC, frontend, event bus or plugin loader was implemented. No contract,
OpenAPI, Bruno or .NET file was modified. No push, PR, merge, tag, release or
deployment was performed.
