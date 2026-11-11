# Internal document storage

`modules/knowledge` owns the `erbas-knowledge` library, `erbas.knowledge`
descriptor, document models, JDBC storage and V9 migration in `db/knowledge`.
The host explicitly composes its configuration and Flyway location. Knowledge
depends only on CORE; CORE, AI, WMS and Assets do not depend on Knowledge.
The optional PDF adapter and V10 extension are described in
[document processing](knowledge-processing.md).

## Storage and integrity

`JdbcDocumentStore` is trusted internal infrastructure. Creation atomically
inserts a document and its first revision; append locks that document row and
allocates the next revision number inside the same transaction. Retrieval uses
the document UUID and explicit revision number. Missing records return empty.
No update or deletion service exists. A PostgreSQL trigger rejects revision
updates and deletes; a unique constraint prevents duplicate revision numbers.
PostgreSQL stores only structured data: ownership, storage scope, revision IDs,
content type, provenance, creator, timestamps, authorization metadata and two
opaque object UUIDs with sizes and SHA-256 digests. Original bytes and
normalized UTF-8 text are separate immutable objects outside PostgreSQL.
The physical provider verifies both digests and sizes; the JDBC adapter also
checks strict normalized UTF-8 and page coverage, and compares the historical
text recipe against the unchanged normalization algorithm.
Byte arrays are copied at the model boundary.

The built-in text processor accepts `text/plain` and `text/markdown` with strict UTF-8. Normalization
removes one leading BOM, converts CRLF/CR to LF and applies Unicode NFC.
Whitespace and Markdown syntax remain intact. The existing rejection of NUL
is retained. Original content remains byte-for-byte unchanged. The optional PDF
processor extracts a text layer with page provenance, without OCR.

Each revision retains nonempty required permissions and the module-policy flag;
the document retains its immutable owner through the internal API. These are
trusted metadata snapshots, never grants. The creator is an opaque attribution
supplied by trusted composition, not an authenticated principal established by
this storage adapter. No visibility rules or functional permissions are invented.

Historically, AI-RAG-001A-1B deliberately revised V9 directly while no document
data was in production and the earlier PRs were unmerged. That slice had no V10,
old-V9 compatibility layer or content migration. A database from before that
revision has a V9 checksum/schema mismatch; never repair it automatically or
delete a persistent database/volume to make it start. The integrated V9 is now
immutable; document processing upgrades it through V10. Validation continues to
use disposable databases.

## Storage contract and local configuration (AI-RAG-001A-1B)

The internal `DocumentStorage` SPI has only `write(context, InputStream)` and
`read(context, object, OutputStream)`. The caller owns its streams; adapters close
their own resources. `write` returns a `StoredObject` only after publishing the
complete immutable object: an opaque UUID, SHA-256 and byte length. `read` checks
that expected integrity and streams into a private staging sink. A failed read
may have written partial bytes into that sink: discard them, never disclose them.
`JdbcDocumentStore` stages both objects and constructs the existing
`DocumentContent` only after success. The provider streams with a bounded buffer;
the existing document/authorized-read model still materializes content in memory.
This is not a streaming HTTP API or an unlimited-size document implementation.

`StorageContext(module, scopeUUID)` is trusted placement metadata. By default,
creation uses the owning module and document UUID as an isolated scope. The
explicit-context creation overload permits trusted composition to supply a
tenant/scope UUID; it does not create a tenant model or grant access. PostgreSQL
persists this context; appends and reads resolve it from the document, not from
untrusted read parameters. Owner/visibility policies still decide business access.

`KnowledgeConfiguration` registers the local adapter unless composition has
already registered a `DocumentStorage` bean. Exactly one provider is active for
the dataset; provider selection per tenant and a multi-provider registry are not
implemented. `erbas.knowledge.storage.root` (environment
`ERBAS_KNOWLEDGE_STORAGE_ROOT`) defaults to `/var/lib/erbas/knowledge`. The root
must already exist, be absolute and normalized, and be private to the service
account. The adapter never creates a fallback root or silently uses `/tmp`.
Missing/inaccessible roots fail storage operations without disclosing paths.

The runtime image prepares that directory for UID 10001. Development Compose
mounts the persistent `knowledge_data` volume there; validation Compose uses its
own run-owned volume with the same ownership labels as other validation resources.
The rest of the application filesystem stays read-only. Normal stops preserve
development volumes. Never use `down --volumes` against development to clean a
test run. Mount a different existing directory at the same container path when
using a bind mount, with UID 10001 access and no symlink components. Keep storage
outside Git, build contexts and public web roots; no HTTP file serving exists.

### Logical objects, publication and failures

Local names are an adapter detail:
`SHA256(UTF8(module) || 0x00 || UTF8(scopeUUID))-objectUUID.blob` directly below
the root. Only typed UUIDs enter names; module text is hashed, never treated as
a path. Changing either module or scope cannot resolve the same object. The
database contains UUID references, not these names or absolute paths.

The local adapter traverses every root component through Java 25
[`SecureDirectoryStream`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
with `NOFOLLOW_LINKS`, and performs subsequent operations relative to that open
directory handle. Symlink roots/ancestors/objects are rejected. Reads require
regular files. Supported deployments use Linux filesystems providing secure
directory handles, POSIX file permissions, atomic same-directory rename and
file/directory synchronization; unsupported facilities fail closed.

Writes exclusively create a private `*.blob.pending` file in the final storage
directory, stream/hash bytes, and force the completed file to disk. A second
exclusive creation reserves the final name; the adapter atomically moves its
completed file over its own reservation and synchronizes the directory before
returning the reference. Existing names are never reused or overwritten by a
new write. Files are private to the runtime UID. Administrators with filesystem
write access remain trusted and can corrupt/delete bytes; hash checks detect
such corruption on reads. Backups and permissions are still required.

Both objects must publish successfully before the revision INSERT/transaction
confirmation. Failure before publication returns no valid object reference.
Ordinary failed writes attempt to remove their temporary files/reservations.
Process death can leave pending files or empty unreferenced reservations. A
second-object failure or PostgreSQL rollback/commit failure can leave complete
unreferenced objects. Published objects are deliberately not deleted on a DB
exception: a caller may not know whether confirmation committed. There is no
distributed transaction, automatic garbage collector or destructive retry.

`StorageFailure.Kind` distinguishes `WRITE`, `READ`, `UNAVAILABLE`, `NOT_FOUND`
and `INTEGRITY`. Errors carry no content, physical location or underlying
credential-bearing exception. A failed authorized read returns no document;
physical failures propagate as technical storage failures, not partial results.
Successful later operations do not reuse failed streams or authorization grants.

### Offline inventory and safe orphan cleanup

Stop **all writers** and retain that maintenance window through inventory and
cleanup. Take a coherent backup first. Do not infer ownership from age alone or
delete during active transactions. Use the exact application database and exact
storage volume, never development resources for a test cleanup.

Export the complete expected local filename set from PostgreSQL (with `psql`
inside the PostgreSQL container). The query covers every revision, including
historical revisions:

```sql
SELECT encode(sha256(convert_to(d.owner_module, 'UTF8') || decode('00', 'hex')
       || convert_to(d.storage_scope::text, 'UTF8')), 'hex')
       || '-' || object_id::text || '.blob'
FROM knowledge_documents d
JOIN knowledge_document_revisions r ON r.document_id = d.id
CROSS JOIN LATERAL (VALUES (r.original_object), (r.normalized_object)) objects(object_id)
ORDER BY 1;
```

Save unaligned tuples-only output as `expected.txt` in a private audit directory
outside the repository/volume. Export actual top-level regular filenames using
the existing pinned Maven/Java container, mounting the **identified** storage
volume read-only at `/data`, and the audit directory at `/audit`. For example,
inside that container:

```sh
find /data -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort > /audit/actual.txt
LC_ALL=C sort -u /audit/expected.txt > /audit/expected.sorted.txt
LC_ALL=C comm -23 /audit/actual.txt /audit/expected.sorted.txt > /audit/orphan-candidates.txt
LC_ALL=C comm -13 /audit/actual.txt /audit/expected.sorted.txt > /audit/missing.txt
```

Review symlinks, subdirectories and unexpected names separately; never follow or
automatically remove them. A nonempty `missing.txt` means confirmed data is
missing: stop cleanup and restore/investigate. Review each candidate against the
same frozen database. Valid cleanup names match exactly
`^[0-9a-f]{64}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.blob(\.pending)?$`.
UUID and digest formats alone do not establish that deletion is authorized.

After explicitly approving a candidate list, quarantine those exact regular,
non-symlink files to a private backup outside the active root before removal.
Use the same identified volume mounted writable in the pinned container; verify
each filename against the regex, verify it is absent from `expected.sorted.txt`,
and remove only that single `/data/<validated-name>` after its quarantine copy
has been verified. No recursive deletion, wildcards or pruning of other volumes.
Re-run both inventories: expected files must remain, and approved orphans must be
absent. Keep manifests and quarantine through the backup retention window.
This is an operator maintenance procedure, not a runtime deletion SPI.

### Coherent backup and restore

Quiesce all writers, finish in-flight database transactions, then back up
PostgreSQL and the complete storage root in the same maintenance window. Record
the schema/application version, active provider configuration, ownership and
inventory alongside the backup. Use PostgreSQL's supported backup tooling and
the identified [Docker volume](https://docs.docker.com/engine/storage/volumes/);
encrypt/access-control both because document bytes and metadata are protected.

Restore both into isolated replacement resources while the app is stopped,
preserving object UUIDs, exact bytes, context and UID permissions. Verify missing
objects, sizes and hashes through the provider before enabling reads/writes.
Only then switch the application to that matched pair. Restoring PostgreSQL
alone can reference absent objects; restoring files alone loses revisions and
authorization metadata. Independent live snapshots and point-in-time recovery
across providers are not implemented. Never delete the source backup/resources
until restoration has been verified.

### Adding a provider: implemented extension point, future adapters

Implement `DocumentStorage` in an infrastructure adapter; keep domain models,
`AuthorizedDocumentReader` and CORE unchanged. Accept `StorageContext` and opaque
UUID references, stream bytes, generate new IDs, publish immutable complete
objects, return byte length/SHA-256, and verify both on every read. Respect caller
stream ownership and convert provider-specific errors into `StorageFailure`.
Partial output must remain private until the read succeeds. Do not expose raw
provider operations as capabilities or endpoints.

Register exactly one adapter bean **before** `KnowledgeConfiguration` is evaluated
(for example, explicitly register it in host composition before importing the
Knowledge configuration). Its presence suppresses the local default; the
composition test exercises this replacement. Credentials belong to deployment
secret configuration, never document records, references or logs. Run common
integrity/isolation/failure tests and real-provider tests before enabling it.
Switching an existing dataset requires an independently approved, verified
offline object transfer preserving references; no such migration is implemented.

**Future S3-compatible example, not implemented:** map trusted module/scope to a
private bucket prefix and object UUID to a key; stream uploads, complete multipart
publication before returning, prevent replacement through conditional creation
and the bucket's immutability controls, and verify an explicit SHA-256/length.
An ETag is not assumed to be SHA-256. Abort incomplete multipart uploads, stage
downloads until verification succeeds, and reconcile unreferenced completed
objects under a quiescent inventory procedure. The adapter would use its own
approved/pinned SDK and map availability/authentication failures to technical
storage failures. No SDK, network call, S3 provider or credential is added here.

**Future customer SaaS storage:** a trusted adapter could resolve each persisted
scope to customer-managed private storage, subject to explicit identity binding,
availability, credential custody, backup and residency decisions. Remote agents,
tunnels, administrative tenant routing and multiple simultaneous providers are
not implemented. Storage placement never replaces module-owned authorization.
Local filesystem scale limits, large-document limits, provider coexistence,
retention policy and online recovery remain pending designs.

## Security boundary and limits

There is no HTTP endpoint, public document resource, READ capability, AI tool or
AI provider integration. Raw storage remains trusted infrastructure, not an
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

RAG, embeddings, vector search, OCR, relations, UI and LLM calls remain outside
this storage API. PDF processing is a separate optional adapter. No dynamic
loading or migration removal is supported.

## Verification

`LocalDocumentStorageTests` exercises streaming, caller-owned resources, empty
objects, persistence across adapter recreation, independent immutable concurrent
writes, module/scope isolation, traversal-shaped contexts, symlink rejection,
tampering/truncation, missing storage and partial read/write failures.
`StorageCompositionTests` verifies replacement of the local provider without
changing document services. `KnowledgePersistenceIT` uses a fresh schema and
isolated file root, retains the existing revision/authorization checks, verifies
that PostgreSQL contains no content columns, and covers separate object integrity,
partial publication and deferred PostgreSQL confirmation failure with safe orphans.
The developer subsequently reported successful complete validation of
AI-RAG-001A-1B: `RUN_ID=erbas-check-72301-18942-2928`, `RESULT=passed`,
`CHECK_EXIT=0`, `CLEANUP_EXIT=0`. Local closure and ordered GitHub integration
were explicitly authorized after that result. Deployment remains unauthorized.

Focused Docker validation on 2026-11-10 passed 70 selected tests: 13 local
storage, 1 provider composition, 14 authorized reader, 2 content, 22 existing
WMS document authorization, 12 architecture, 1 CORE migration, 3 host migration
compatibility, 1 Knowledge persistence and 1 host context. Run
`erbas-storage-providers-20261110` ended with `FOCUSED_EXIT=0` and
`CLEANUP=passed`. The inventory SQL was exercised against real PostgreSQL;
unreferenced objects were quarantined and every confirmed revision remained
readable. No native provider/authorization test was skipped. Maven emitted
HTTP 401 warnings for optional Flyway metadata repositories and Mockito emitted
its existing dynamic-agent/CDS warnings; the selected build and tests succeeded.
That focused phase did not run the complete check or build the final application
image; the complete validation above was coordinated externally. No deployment
was performed.

The separate `erbas-storage-providers-volume-20261110` probe ran the local
adapter in two distinct Java containers as UID 10001 with a read-only root
filesystem and the same private Docker volume. It recorded
`VOLUME_WRITE=passed`, `VOLUME_RECREATED_CONTAINER_READ=passed`, `VOLUME_EXIT=0`.
Both Compose files passed configuration validation without starting services.
The PostgreSQL containers and anonymous volumes, named test/cache/object volumes,
and networks created for these runs were removed; final ownership-label queries
found no remaining test containers, networks or volumes. No persistent application
database or unrelated volume was modified.

### Historical AI-RAG-001A-2 evidence (original V9)

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

### Historical AI-RAG-001A-1 evidence (original V9)

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
