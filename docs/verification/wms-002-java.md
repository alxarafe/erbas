# WMS-002 Java: Storage Attributes and published contract v0.7.0

## Source identity and authority

Java base: `4af73d9bf49294f0668ab0849049c2eb5a47c699`, clean `main` before this task.
Branch: `feature/wms-002-java-storage-attributes-v0.7.0`.

The sole external authority is published immutable `alxarafe/erbas-contract`:

- Release: `v0.7.0`.
- Annotated tag object: `c6cdbe0d90c63fb79b1bb36a96d67d822243ffdb`.
- Target/checkout HEAD: `76618b4d6776dca5e13466e7fde44af675d5b08f`.

The explicit sibling checkout was clean, with matching origin/tag/HEAD. Java
only reads and runs that checkout. No contract, .NET, client or laboratory files
were modified. `contract.revision`, preflight and CI now select v0.7.0. Existing
origin, root, cleanliness, immutable target and HEAD checks remain mandatory
before provisioning and immediately before the full shared suite.

## Architecture and exact behavior

WMS remains the sole affected functional module, `erbas.wms`, with no dependencies.
Normal composition includes UoM and Storage Attributes. Core stays business-neutral.

- Domain: `StorageAttribute(UUID id, String code, String name)` validates non-null,
  non-nil identity, exact `^[A-Z][A-Z0-9_]{0,29}$` ASCII codes and normalized name.
  No trim/uppercase conversion of code or historical `IS_*` veto is introduced.
- Application: `StorageAttributeCatalogue` generates UUIDs and delegates create/list
  through its nested Store port; outcomes are CREATED, INVALID_REQUEST or
  CODE_CONFLICT. Domain and application have no Spring, HTTP, JDBC or locale dependency.
- JDBC: `JdbcStorageAttributeStore` uses `INSERT ... ON CONFLICT (code) DO NOTHING`.
  Concurrent duplicate attempts have exactly one winner and one conflict without
  changing the original. COUNT and page use one REPEATABLE_READ snapshot. Code ASC
  ordering precedes parameterized LIMIT/OFFSET; offsets beyond total, including
  beyond Long.MAX_VALUE, return empty windows without overflowing JDBC. No full
  catalogue is loaded for slicing.
- HTTP: `StorageAttributeController` implements only create/list on
  `/api/wms/storage-attributes`. Strict duplicate detection, trailing-token rejection
  and tree/type/field validation prevent Jackson coercion. POST accepts no query
  parameters and returns closed id/code/name with 201, no Location. Duplicate code
  returns exact 409 `wms_storage_attribute_code_conflict` without mutation.
  `StorageAttributeExceptionHandler` maps unreadable/empty bodies to the same closed
  400 `invalid_request`. GET reuses `PagedCollection` and its validation/defaults.

The only shared domain extraction is package-private `CatalogueLabel`, retaining
UoM's exact edge/scalar algorithm and a single 255-scalar maximum. Only consecutive
SPACE, TAB, CR and LF are removed at either boundary before counting. NUL and
unpaired high/low surrogates are rejected; a valid surrogate pair counts once.
Internal whitespace, NBSP, EM SPACE, case and all other valid Unicode stay exact.
UoM keeps its fields, code grammar, errors and observable normalization unchanged;
its existing tests and all shared UoM requests remain in complete validation.
No general validator framework or new dependency was introduced.

## Migration V6

`V6__wms_storage_attributes.sql` follows unchanged V1–V5 and creates only:

```sql
id UUID PRIMARY KEY,
code TEXT COLLATE "C" NOT NULL UNIQUE,
name TEXT NOT NULL
```

The table is `wms_storage_attributes`; there are no future columns. PostgreSQL C
collation provides exact code uniqueness and ordinal ASCII ordering. Integration
covers a fresh V1–V6 database and V5-to-V6 upgrade preserving an existing UoM UUID,
code/description content and disabled module state. A second migration call is a no-op.
API verification requires all six successful migrations, both WMS tables, prior
CORE/auth invariants and initially empty WMS catalogues before Bruno.

## Security, gating and i18n

`WmsConfiguration` extends the existing single native ADMIN customizer to both
routes. Both controllers use `@ModuleEndpoint(WmsModule.ID)`. Bearer authentication
and administrator authorization precede the dynamic MVC module gate, which precedes
body/query validation. Invalid requests therefore produce 401 anonymously, 403
for a real non-admin bearer, 503 for an admin with WMS disabled and 400 for an
admin with WMS enabled. CORE module PATCH disables both catalogues immediately;
re-enabling needs no restart and retains both catalogues' data.

All responses use application/json and Cache-Control no-store. Only 401 carries
WWW-Authenticate Bearer; 503 has no Retry-After. Error bodies contain only the
contractual technical code. No redirects, SQL details or new human/i18n messages
are introduced. Accept-Language cannot alter UUIDs, codes, stored names, property
names or errors; real HTTP checks compare persisted output and duplicate errors
under English/Spanish negotiation.

## Verification

The final complete run, `erbas-check-544152-4397-25818`, passed with exit 0.
The first run, `erbas-check-487661-4787-4780`, correctly failed an existing exact
migration-history expectation that still ended at V5; cleanup passed. Both prior
fresh-history assertions now require V1–V6, preserving all earlier invariants.
The complete command was rerun after that correction.

| Check | Actual result |
| --- | --- |
| Regular native suite | 301 passed, zero failures/errors/skips; also executed again in a fresh test container |
| Real PostgreSQL integration | 97 passed, zero failures/errors/skips |
| Distinct native cases | 398 total: 301 regular + 97 PostgreSQL/HTTP integration |
| Added Storage Attribute coverage | 35 domain cases, 5 application tests, 9 PostgreSQL/real HTTP integration tests |
| Existing UoM coverage | 32 domain + 4 application + 8 integration tests retained and passed |
| Fresh databases | Native and API public schemas each had zero tables before migrations |
| Flyway | V1–V6 successful, no failed migrations, both WMS tables and earlier CORE/auth invariants verified |
| Upgrade | V5-to-V6 preserves existing UoM identity/code/description and disabled module state; repeat is a no-op |
| JDBC | Required fields, PK, code uniqueness, normalized persistence, concurrent duplicate outcome, ordinal order, windows and huge offset passed |
| Native HTTP | Strict JSON/query validation, exact headers/errors, real bearer/admin precedence, locale invariance and gate toggles with both catalogues preserved passed |
| Shared published Bruno | Full live v0.7.0 conformance passed, exit 0: 266 requests / 1050 named checks; all previous 182 / 714 retained |
| Operational probes | Actuator 200 with DB up; exact Health 200 with DB down; Actuator 503 with DB down |
| Secrets | Sensitive-log probe and final log-secrecy check passed |
| Cleanup | Ownership-checked cleanup passed, exit 0; zero owned containers/networks/volumes and temporary image tags remained |
| Source/shell | `git diff --check` and `bash -n bin/check` passed |

The shared runner withholds detailed execution statistics as well as diagnostics.
Request/check totals were independently counted in Docker from the same published
collection mounted read-only, including inherited folder tests. They were not
added as a hardcoded backend success criterion. The runner executed the complete
collection without scope selection or skips.

The existing suite emits Mockito/Byte Buddy dynamic-agent and validation-fixture
deprecation warnings. Maven also reports HTTP 401 for optional Flyway metadata
feeds; pinned dependencies resolve and all builds/tests pass. Deliberate native
module failure fixtures log their expected exceptions. These do not indicate
failed tests or a WMS implementation error.

```bash
ERBAS_CONTRACT_DIR=../erbas-contract ./bin/check
git diff --check
```

Unit tests cover code boundaries/underscore/IS_FOOD, all name scalar/edge rules,
UUID null/nil, application outcomes, normalized persistence delegation, immutable
windows and huge offsets. `StorageAttributeIntegrationIT` uses real PostgreSQL,
Flyway, constraints, concurrent insertion and real HTTP with persisted bearer
accounts. It covers fresh/upgrade migrations, required columns/PK/unique code,
normalized stored text, ordinal pages, default/empty/beyond-total windows, strict
JSON/query validation, code/name boundaries, duplicate non-mutation, locale
invariance, security precedence and both catalogues across gate toggles.

The complete published Bruno collection runs against the live application and a
separate initially empty PostgreSQL database, never just the new Storage Attribute
requests. No request/check count is hardcoded as a success criterion. The runner
withholds detailed diagnostics to protect generated credentials and tokens.

## Compatibility and scope review

Health, AUTH, USERS, COLLECTIONS-001, module administration, canonical development
descriptors and all UoM HTTP schemas/errors/order/pagination remain unchanged.
Core has no WMS routes or types. Java 25, Spring Boot 4.1.1, JDBC, PostgreSQL,
Flyway and pinned Docker-only tooling are retained. No dependency/image changes
or host Java/Maven/Bruno requirements are introduced.

Named domain limits, patterns, route/JSON/error constants, JDBC table constant,
platform HTTP/media/header/status facilities and existing pagination constants
cover meaningful new values. Deliberate invalid JSON and incidental fixture data
remain local; there is no new global constants catalogue.

No exclusiveGroupCode, individual GET, Storage Attribute PATCH/DELETE, ItemFamily,
Item, family links, location/zone attributes, storage rules, topology, inventory,
AI, new RBAC, .NET/client changes, publication or deployment is included. Historical
WMS-001 verification remains untouched. This task authorizes only a local commit.
