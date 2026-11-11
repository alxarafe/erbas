# Internal document processing

Trusted composition calls `DocumentProcessingService.process(InputStream,
contentType)` and passes the complete `DocumentContent` to the existing
`JdbcDocumentStore.create` or `append`. These are internal APIs, not HTTP
endpoints or grants. Consumers still use `AuthorizedDocumentReader` before
disclosure. CORE and AI are unchanged.

## Optional processors

`DocumentProcessor` defines identity, supported media types and synchronous
extraction under a `ProcessingBudget`. Register implementations explicitly as
Spring beans. Duplicate identities or competing handlers for a media type fail
startup. Absent/disabled handlers reject the format; there is no dynamic plugin
loading, filesystem scan or request-selected implementation.

The built-in `TextDocumentProcessor` calls the existing normalization for
`text/plain` and `text/markdown`: strict UTF-8, removal of one leading BOM,
CRLF/CR to LF, Unicode NFC and rejection of NUL. Whitespace and Markdown syntax
are preserved. Its recipe is `knowledge-text`, version `1`, without page spans.

`modules/knowledge-pdf` produces the optional `erbas-knowledge-pdf` adapter.
It alone depends on **Apache PDFBox 3.0.8**, a maintained stable release under
Apache License 2.0. Its listed native Spring Boot auto-configuration registers
the PDF processor when present and enabled. It is not a functional module or
permission authority. Knowledge has no dependency on this adapter or PDFBox.
The packaged PDFBox components `fontbox` and `pdfbox-io` are also 3.0.8;
Commons Logging resolves to the existing Spring-managed 1.3.6. No optional image
codecs, cryptographic providers, PDFBox tools or Preflight components are added.

The host's `knowledge-pdf` Maven profile includes the adapter by default. Add
`-Derbas.knowledge.pdf.exclude=true` to Maven inside the existing Docker build
environment to omit it from the host dependency graph and package. Explicitly
deactivating that profile also works. The reactor can still build the adapter.
Runtime disabling removes its bean but leaves the libraries packaged. New
adapters need only implement the same interface and register in composition.

Official sources: [release/dependency](https://pdfbox.apache.org/3.0/getting-started.html),
[license](https://pdfbox.apache.org/),
[Java requirements and random access](https://pdfbox.apache.org/3.0/migration.html),
[security model](https://pdfbox.apache.org/security.html). The PDFBox 3 guide
requires Java 8 or higher but does not certify Java 25 specifically; the focused
tests exercise this integration in the project's pinned Java 25 Docker image.

## PDF recipe and provenance

The adapter accepts `application/pdf` with a textual layer. It uses strict
parsing, rejects encryption, damaged text operators and documents with no
non-whitespace extracted text. It provides no OCR, rendering, embedded-file
export, password API, DOCX, network lookup or LLM calls. It is not a full PDF
conformance/signature validator and does not guarantee visual reading order.
PDFBox extracts in content-stream order with explicit LF line/page endings.
Each page is normalized by the existing text function, then concatenated.
Original bytes are never transformed.

`ProcessingDetails` retains identity, actual processor/library version, recipe
options (including normalizer version, effective indent/drop settings and Java
runtime version) and cumulative page ends. Ends are exclusive **UTF-16 character offsets**
into `DocumentContent.text()`, in original one-based page order, not UTF-8 byte
offsets. Page 1 starts at zero; later pages start at the preceding end. Equal
ends describe empty spans. Pages without content streams remain represented.
Normalized content is stored as UTF-8 through `DocumentStorage`.

V10 extends the immutable revision with this recipe/page map and widens V9's
MIME constraint. V9 is unchanged. Existing rows receive the exact historical
text recipe via column defaults, without updating their content or originals;
new writes supply all fields explicitly. Reproduction requires the retained
original, implementation, recorded version/options and the same PDFBox font
environment. Reprocessing after an upgrade creates a new revision.

Reads verify both object digests and sizes through the storage SPI, strict
normalized UTF-8 and page coverage. The historical text recipe additionally
retains its original-to-normalized comparison. PDF reads return the recorded
result without rerunning a processor, even when that adapter is disabled or
absent. SHA-256 detects byte corruption, not malicious replacement of both
objects and metadata by a trusted administrator.

## Configuration and failure behavior

Properties share the prefix `erbas.knowledge.processing`:

| Suffix | Default | Meaning |
| --- | --- | --- |
| `max-original-bytes` | `10485760` | Original binary/text byte limit |
| `max-normalized-bytes` | `4194304` | Normalized UTF-8 limit; also bounds staged characters and PDF glyphs |
| `max-pages` | `500` | PDF page limit |
| `timeout` | `PT10S` | Positive ISO-8601 cooperative deadline |
| `text.enabled` | `true` | Register TXT/Markdown processor |
| `pdf.enabled` | `true` | Register PDF processor when packaged |

Environment examples: `ERBAS_KNOWLEDGE_PROCESSING_MAX_ORIGINAL_BYTES` and
`ERBAS_KNOWLEDGE_PROCESSING_PDF_ENABLED`. Nonpositive limits fail startup.
Input is read in bounded chunks, consuming at most one extra byte to detect
overflow. Caller streams stay open. The existing materialized immutable read
model is retained with finite limits, not an unlimited-size/streaming HTTP API.
PDF random access uses bounded original bytes, without physical storage paths
or temporary document files. Lowering read limits below historical object
sizes makes those reads fail closed until the limit is restored.

The deadline is **cooperative, not a hard CPU/heap sandbox**. Checks run during
input/parser reads, PDF operators, glyphs, pages and before returning. A blocking
input read or an individual PDFBox operation between callbacks cannot be forcibly
interrupted. Compressed PDF structures may consume more memory than input or
output. Apply deployment resource limits for hostile documents. Isolated or
distributed workers are outside this task; no background thread keeps extracting
after the service returns a timeout.

Technical `ProcessingFailure.Kind` values are `UNSUPPORTED`, `INVALID`, `LIMIT`,
`TIMEOUT` and `NO_TEXT`, without parser exceptions or document text. Extraction
finishes before persistence and the coordinator verifies original preservation.
A failure has no publishable result, including a failure after an earlier page.
The existing transaction confirms a revision only after both immutable storage
objects publish. Partial writes/reads cannot produce a successful document.
Already published but unreferenced objects retain the existing
[offline orphan procedure](knowledge-storage.md#offline-inventory-and-safe-orphan-cleanup).

## Verification

`DocumentProcessingTests` covers TXT/Markdown regression, size/deadline limits,
partial input, UTF-8/NUL rejection, ambiguous/absent processors and original
mutation. `PdfDocumentProcessorTests` covers extraction, original SHA-256,
empty-page provenance, corruption, late errors, encryption/no-text rejection
and limits. `PdfCompositionTests` verifies enabling/disabling.
`PdfPersistenceIT` uses real PostgreSQL for a populated V9 upgrade, immutable
recipes, digests, reload without reprocessing, authorized reads/revocation and
partial storage failures without published revisions. Existing storage,
authorization, host persistence and migration checks remain applicable.

Complete validation remains external `./bin/check` with an explicit approved
contract checkout. Focused tests do not establish shared Bruno conformance,
final-image validation or deployment readiness.
