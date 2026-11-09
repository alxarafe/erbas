# Internal document storage (AI-RAG-001A-1)

`modules/knowledge` owns the `erbas-knowledge` library, `erbas.knowledge`
descriptor, document models, JDBC storage and V9 migration in `db/knowledge`.
The host explicitly composes its configuration and Flyway location. Knowledge
depends only on CORE; CORE, AI, WMS and Assets do not depend on Knowledge.
No dependency or tool version changes are introduced.

## Storage and integrity

`JdbcDocumentStore` is trusted internal infrastructure. Creation atomically
inserts a document and its first revision; append locks that document row and
allocates the next revision number inside the same transaction. Retrieval uses
the document UUID and explicit revision number. Missing records return empty.
No update or deletion service exists. A PostgreSQL trigger rejects revision
updates and deletes; a unique constraint prevents duplicate revision numbers.
Original bytes, SHA-256, content type, normalized text, provenance, creator,
timestamps and authorization metadata are persisted. SHA-256 is calculated over
the original bytes and also checked by PostgreSQL. Reads verify the digest and
normalized text. Byte arrays are copied at the model boundary.

Only `text/plain` and `text/markdown` with strict UTF-8 are accepted. Normalization
removes one leading BOM, converts CRLF/CR to LF and applies Unicode NFC.
Whitespace and Markdown syntax remain intact. NUL is rejected because PostgreSQL
text cannot represent it. Original content remains byte-for-byte unchanged.

Each revision retains nonempty required permissions and the module-policy flag;
the document retains its immutable owner through the internal API. These are
trusted metadata snapshots, never grants. The creator is an opaque attribution
supplied by trusted composition, not an authenticated principal established by
this storage adapter. No visibility rules or functional permissions are invented.

## Security boundary and limits

There is no HTTP endpoint, public document resource, READ capability, AI tool or
provider integration. Raw storage is not an authorization service. Future callers
must apply the existing cumulative [document authorization](document-authorization.md)
before any disclosure, including metadata, and resolve revocation consistency
before exposing retrieval. This task neither registers default visibility nor
changes module-owned policy authority. A persisted reference is not evidence
that access is currently allowed.

RAG, embeddings, vector search, OCR, PDF processing, relations, UI and LLM calls
are outside this slice. No dynamic loading or migration removal is supported.

## Verification

`DocumentContentTests` covers normalization, defensive copies and invalid input.
`KnowledgePersistenceIT` uses an isolated real PostgreSQL schema and actual host
startup to upgrade V8 to V9, preserve migration history/data, retrieve original
content and metadata, append concurrently, reject mutation and roll back failures
after document insertion. `MigrationCompatibilityIT` also exercises fresh host
migrations and historical upgrades. Source/POM and packaged-artifact guards check
module boundaries. Focused checks do not replace complete validation:

```sh
ERBAS_CONTRACT_DIR="$HOME/Desarrollo/tmp/contracts/erbas-contract-v0.14.0" ./bin/check
```

Focused Docker validation on 2026-11-09 passed 49 tests: 2 content, 12 source
architecture, 1 host context, 1 CORE migration, 22 existing document authorization,
3 host migration compatibility, 7 module administration and 1 storage integration.
Run `erbas-knowledge-19143-11541` ended with `FOCUSED_EXIT=0` and
`CLEANUP=passed`. Maven emitted HTTP 401 warnings while probing optional Flyway
metadata repositories; dependency resolution and both selected test phases
succeeded. Those focused checks preceded the complete validation below.

Integration regression checks subsequently passed 42 tests after updating exact
module/migration expectations. A separate connection-pressure diagnostic reproduced
exhaustion with nine retained default pools (90 connections) against PostgreSQL's
100-connection limit, without a Knowledge connection leak. The temporary Knowledge
test context now budgets its pool for its two concurrent writers; production and
global test limits are unchanged. Three focused tests then passed, including the
same storage scenario under that pressure, with connection counts restored and
ephemeral Docker resources removed.

The developer reported successful complete `./bin/check` validation on 2026-11-09:
`RESULT=passed`, `CHECK_EXIT=0`, `CLEANUP_EXIT=0`,
`RUN_ID=erbas-check-43501-6455-16218`. This is the complete validation authority,
including packaged-artifact guards, final image, migrations, health and shared
Bruno conformance. Final local closure was explicitly authorized; publication and
deployment remain separately controlled.
