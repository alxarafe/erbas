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
provider integration. Raw storage remains trusted infrastructure, not an
authorization service. Application consumers use `AuthorizedDocumentReader`;
it exposes no repository accessor or caller-supplied authorization reference.
A persisted reference is never evidence that access is currently allowed.

## Authorized internal reads (AI-RAG-001A-2)

`AuthorizedDocumentReader.document(subject, owner, id)` returns the latest
revision; `revision(subject, owner, id, number)` returns a specific revision.
Both return the complete immutable revision (content and protected metadata)
only after authorization. Missing, invalid and denied requests return the same
empty result, without document identifiers, metadata or denial reasons.
The expected owner must match persisted ownership. Within an owner, scope
is enforced by the existing visibility and owner policies, not caller claims.

The application service depends on the narrow `DocumentReadStore` persistence
port, implemented by the existing JDBC adapter. Reference queries do not load
content. The service applies CORE's unchanged `DocumentAuthorization` to trusted
persisted references before content loading and again before returning. Historical
reads require both current and historical revision permissions and policies, so
an older permission snapshot cannot bypass a newer restriction. A concurrent
restrictive append observed after loading also denies the result.

Knowledge composition uses current `UserAdministration` lookup, module state and
existing functional permission beans. It collects native `DocumentVisibilityPolicy`
and module-owned `DocumentAccessPolicy` beans. No visibility beans denies all
reads; multiple visibility authorities must all allow. No permissive production
visibility or owner policy is supplied. Mandatory missing owner policies deny;
foreign policies cannot satisfy them. Every read reevaluates current authorities;
no authorization decisions are cached. Test policies remain under `src/test`.

Returned values are snapshots, not continuing grants. CORE authorities and
PostgreSQL do not share a lock or transaction: the final authorization check is
the decision boundary, not an atomic guarantee against concurrent revocation
after that check. A later disclosure requires a new authorized read. No new
CORE mechanism, schema, permission or functional module dependency is introduced.

RAG, embeddings, vector search, OCR, PDF processing, relations, UI and LLM calls
are outside this slice. No dynamic loading or migration removal is supported.

## Verification

The authorized reader adds 14 unit cases for positive reads, independent
authority revocation, missing/foreign policies, identifier isolation, current
and historical restrictions, and revocation/restrictive append during loading.
Denied initial checks never load content. The real PostgreSQL host test also
exercises Spring composition, absent production authorities, current/historical
reads, owner/scope isolation, membership revocation and persisted identity disable.

Focused Docker validation on 2026-11-10 passed 55 selected tests: 14 authorized
reader, 2 content, 22 existing WMS document authorization, 12 architecture,
1 CORE migration, 3 host migration compatibility and 1 Knowledge persistence.
Run `erbas-authorized-read-20261110` ended with `FOCUSED_EXIT=0` and
`CLEANUP=passed`, using isolated PostgreSQL without published ports. Optional
Flyway metadata repository probes emitted HTTP 401 warnings; the selected build
and tests succeeded.

The developer subsequently reported successful complete `./bin/check` validation:
`RUN_ID=erbas-check-45881-7070-14091`, `RESULT=passed`, `CHECK_EXIT=0`,
`CLEANUP_EXIT=0`. Local closure of AI-RAG-001A-2 was explicitly authorized after
that result. Publication and deployment remain separately controlled.

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
