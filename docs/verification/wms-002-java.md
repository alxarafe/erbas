# WMS-002J Java verification

Verified on 2026-10-16 from exact Java main
`e2db7c08ec611f340ccc90e44a1104ce4d2bb342`, on
`feature/wms-002-java-storage-attributes-v0.7.0`.

## Published authority

`contract.revision` is `v0.7.0`. Its annotated tag object is
`c6cdbe0d90c63fb79b1bb36a96d67d822243ffdb`, resolving to commit
`76618b4d6776dca5e13466e7fde44af675d5b08f`.
OpenAPI and the sole Bruno collection were read from that immutable release.
The supplied checkout was clean and remained unchanged. Java neither downloads
nor changes that checkout. CI selects the same release, and `bin/check` verifies
root, GitHub origin, cleanliness, annotated tag object, peeled commit and HEAD
before provisioning and again before conformance.

## Implementation and ownership

Only `POST /api/wms/storage-attributes` and `GET /api/wms/storage-attributes`
are added. The closed representation is `{id, code, name}`. Server UUIDs are
non-nil and canonical lowercase. Codes match `^[A-Z][A-Z0-9_]{0,29}$` exactly;
`IS_FOOD` is valid. Names remove only boundary SPACE/TAB/CR/LF, then require
1–255 Unicode scalar values without NUL or unpaired surrogates. Other Unicode
whitespace, internal whitespace and case remain unchanged.

`StorageAttribute` and `StorageAttributeCatalogue` are framework-independent.
The catalogue owns creation outcomes and a small persistence port, following the
existing UoM pattern. `JdbcStorageAttributeStore` uses PostgreSQL
`ON CONFLICT (code) DO NOTHING`; duplicates never overwrite existing content.
Its read-only repeatable-read window uses one snapshot for count and page,
and returns empty windows for offsets beyond total without overflowing JDBC.
The two short typed stores retain explicit SQL and row mappings rather than
introducing a generic persistence framework.

UoM and Storage Attributes share `CatalogueValues` for identity, ASCII-boundary
normalization and scalar validation, and `CatalogueHttp` for strict duplicate-aware
JSON parsing, collection query validation, JSON/no-store responses and errors.
`CatalogueExceptionHandler` covers unreadable bodies for both controllers.
CORE's existing `PagedCollection` remains the pagination authority. UoM code
rules, response fields, application logic and persistence remain unchanged.

WMS composition imports its two controllers and supplies their catalogue/store
beans. Both routes contribute administrator authorization through the existing
Spring Security extension. Authentication precedes administrator authorization,
then the existing module interceptor, then validation and business operations.
Invalid requests therefore produce 401/403/503/400 according to that precedence.
Only 401 carries a Bearer challenge; errors remain closed technical codes, with
no Retry-After or v0.8 envelope. Spanish negotiation never translates user data.

All feature production code and resources belong to `modules/wms/`. CORE and
host production sources, Maven dependencies, Dockerfile and pinned images are
unchanged. Dependency direction remains host → CORE/WMS and WMS → CORE.
Installed WMS retains schema/data while disabled and resumes access immediately
on re-enable, without restart.

## Migration and data preservation

CORE still owns V1–V4 under `db/core`; WMS owns unchanged V5 plus
`modules/wms/src/main/resources/db/wms/V6__wms_storage_attributes.sql`.
V6 creates `wms_storage_attributes` with UUID primary key, non-null name and
non-null unique code using PostgreSQL C collation for exact comparison and
ordinal ASCII ordering. The host retains one validated Flyway history over
`classpath:db/core,classpath:db/wms`.

Fresh real PostgreSQL schemas migrate through V6 and start the host. The separate
API conformance database also starts empty, applies V1–V6, and passes both UoM
and Storage Attribute HTTP conformance.

`MigrationCompatibilityIT` creates the historical V1–V5 database using preserved,
byte-identical release fixtures. After host startup, all previous history rows,
checksums and installation metadata compare equal. UoM table OID, UUID and
content, CORE marker, authentication rows/token data and disabled module state
are preserved. Exactly one successful V6 history row and its empty table appear;
subsequent migration calls execute zero migrations. Existing login and UoM HTTP
remain operational, and the upgraded host creates/lists Storage Attributes.
No repair, reset, baseline or disabled validation is used.

## Executed validation

```bash
ERBAS_CONTRACT_DIR=/tmp/wms-002j-contract-v070 ./bin/check
```

Exit **0**, wall time **192 seconds**, run
`erbas-check-542818-3366-21496`. All project builds/tests ran inside Docker.

| Validation | Result |
| --- | --- |
| Entire Maven reactor build and fresh native run | Passed: 305 tests, zero failures/errors/skips |
| CORE-only `-pl erbas-core clean verify` | Passed: 224 tests without selecting WMS/host |
| WMS native suite | 74 passed: existing 36 plus new 38 |
| Host native suite | 7 passed, including all 6 unchanged architecture guards |
| Integration profile | 100 passed: 98 real-PostgreSQL/HTTP tests plus 2 packaged-artifact guards |
| Storage Attribute integration | 7 passed: constraints, ordering/windows, concurrency, commit visibility, HTTP/security/gating, strict JSON/query and locale behavior |
| Existing UoM integration | All 8 retained and passed |
| Fresh and historical-V5 host migration tests | Both passed |
| Shared live Bruno v0.7.0 | Complete conformance passed: 266 requests / 1050 named checks |
| Published collection inventory/order guard | Passed separately in Docker with network disabled |
| OpenAPI lint | Passed, without contract changes |
| Runtime | UID 10001, one `/app/erbas.jar`, port 8080, read-only container, healthy existing healthcheck; no Maven, javac or sources |
| Operational checks | Actuator 200 with database; process Health 200 and Actuator 503 with database stopped |
| Secrets and cleanup | `LOG_SECRECY=passed`, `CLEANUP_STATUS=passed`, `CHECK_EXIT=0`; owned containers/network/volumes/image tags removed |
| Shell/diff | `bash -n bin/check` and `git diff --check` passed |

The base's 267 native tests and 93 integration-profile tests are retained;
38 native and 7 PostgreSQL/HTTP cases are added. The v0.7.0 collection retains
all previous 182 requests/714 checks and adds 84/336 for Storage Attributes.
The preflight also rejected nested roots, incorrect origin, nonignored untracked
files and historical HEAD with exit 2 before provisioning, using private copies.
Build logs contained non-fatal 401 metadata warnings from existing configured
Maven repositories; dependency versions and repository configuration were not
changed, and all reactor commands succeeded.

The synchronized HTTP race proves one 201 and one contractual closed 409, one
persisted row and unchanged normalized content. Independent JDBC reads immediately
after HTTP 201 prove commit visibility and UUID persistence. Native boundary tests
cover 1/255/256 BMP and supplementary scalars, long removable padding, NBSP/EM
SPACE preservation, NUL/surrogates, code lengths 1/30/31 and all requested invalid
code categories. Existing architecture and migration-fixture guards remain intact.

## Scope and documentation review

Modified current documentation: README, documentation index, usage, modularity,
i18n and the working agreement's current contract references. This report is new.
Historical verification reports and ADR 0002 are unchanged. Meaningful new values
have named local/domain constants or reuse existing platform/framework facilities;
fixture values remain local. No unrelated changes or dependency/image upgrades
are included.

There is no WMS-003, ItemFamily, association, exclusive group, individual resource
GET, update/delete, RBAC, frontend, AI, event system or dynamic module loading.
OpenAPI and Bruno are unchanged; no assertions were copied into JavaScript.
No push, PR/MR, merge, tag, release, publication or deployment was performed.
