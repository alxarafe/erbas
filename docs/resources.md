# Generic Resource Runtime (CRUD-CONTRACT-001)

This checkout targets the published Git reference **erbas-contract v0.14.0**:
annotated tag `22191afb8ab477fef319093b8c212264f4019816`, target
`9919ad3725e50d63e7a19befbe0d81b4d91df0cc`. Historical preparation wording in
the release does not supersede that immutable reference. The normative
[protocol](https://github.com/alxarafe/erbas-contract/blob/v0.13.0/docs/core/resources-001.md)
and [OpenAPI](https://github.com/alxarafe/erbas-contract/blob/v0.13.0/openapi/core/resources/contract.yaml)
remain owned by erbas-contract.

The v0.14.0 adoption is ready for local review only after complete validation.
Published Java adoption requires publication of the Java commit and successful
authoritative Java CI; a local pin or test result alone does not establish it.

## Core protocol and module ownership

Core owns generic HTTP routes, registry, effective metadata, scalar/constraint
validation, query grammar, authorization orchestration and safe error mapping.
Modules supply `ResourceRegistry.Source` beans containing immutable
`ResourceDefinition` declarations. They separately register
`ResourceExecutionAdapter` and `ResourceActionExecutor` beans by declared identity.
Registration fails on duplicate identities, unknown owners, invalid ID fields,
constraints, relations, actions or presentation references.

The adapter receives trusted current identity and typed values/queries. It owns
persistence and business rules. Core contains no generic record table, ORM,
module-specific SQL or arbitrary HTTP executor. A real JDBC adapter is exercised
in test sources against the existing PostgreSQL persistence marker. Production
registration is empty until a functional module declares a resource. WMS legacy
catalogues, schemas, collections and services are unchanged. No Assets module or
business model is implemented.

## Routes and precedence

- `GET /api/meta/resources` discovers enabled definitions, including operations
  the caller cannot execute. `GET /api/meta/resources/{resource}` returns detailed
  effective metadata, even when its module is disabled.
- `GET/POST /api/resources/{resource}` lists or creates records.
- `GET/PATCH/DELETE /api/resources/{resource}/{id}` reads, partially updates or deletes.
- `POST /api/resource-actions/{resource}/{action}/execute` executes a declared action.
- `POST /api/resource-actions/{resource}/availability` obtains advisory states.

CRUD authentication precedes definition lookup, transport parsing, execution/
operation lookup, module/context guards, operation authorization, runtime
validation and module execution/output validation. This **scoped exception** can
produce 404 for unknown definitions or 400 for malformed transport before
functional permission evaluation, and 503 for disabled modules before 403.
Users, Modules, WMS, capabilities and AI retain their existing precedence.

`FunctionalPermissionPolicy` evaluates each declared operation/action permission
against the current persisted user; definitions do not grant permissions.
`UserAdministration.find` refreshes enabled/admin state for each execution.
Adapters can deny their execution context without introducing a tenant engine.
There is no administrator bypass beyond existing module-owned permission policies.

## Values and CRUD

The scalar catalogue is BOOLEAN, STRING, TEXT, INT8, UINT8, INT16, UINT16, INT32,
UINT32, INT64, DECIMAL, UUID, DATE, TIME, DATETIME, ENUM and BINARY. Integer ranges
are exact, and DECIMAL uses BigDecimal throughout. Adapters cannot return binary
floating point for decimal fields. UUID text is canonical, DATE has no timezone,
TIME emits seven fractional digits, DATETIME is normalized to UTC and BINARY is
base64. Numeric transport, body size and Unicode validity are bounded at the
protocol boundary. Constraints include exact numeric bounds/exclusivity,
multipleOf, precision/scale, code-point text lengths, pattern and declared enum values.

CREATE and PATCH use flat field objects. Unknown/read-only inputs are rejected.
CREATE defaults apply only to absent writable fields, followed by required
presence checks. Explicit null never triggers a default and requires nullable.
PATCH preserves omitted fields and never reapplies defaults; it validates the
merged record. Valid immutable inputs conflict even when unchanged; malformed
types remain invalid input. Identifier changes are invalid. Explicit business
conflicts use `ResourceFailure`, never interpretation of exception messages.

CREATE returns the persisted record with 201 and Location. DELETE returns 204;
an absent record is 404. Adapter output is validated, including requested record
identity. Known invalid output becomes a safe execution failure.

## Queries and pages

Only offset, limit, search, repeated sort and repeated filter are accepted.
Offset defaults to zero and limit to 50, bounded to 1..100. `ResourceQuery`
contains typed AND filters, literal case-sensitive substring search and ordered
sort criteria. Duplicate scalar parameters/sort fields, duplicate JSON members,
extra filter members and unadvertised fields/operators are rejected. Numeric and
temporal operands remain exact typed values. Nullable eq/ne, ordered comparisons,
fragments and sorting follow the published null semantics. Adapters may translate
this small grammar to their own persistence technology; no SQL is accepted.

`ResourcePage` is independent of legacy `PagedCollection`: items/offset/limit,
with total omitted when unavailable and no order member. A supplied total counts
matches before paging. Empty and beyond-end windows are successful 200 responses.

## Metadata, relations, presentation and actions

Metadata exposes effective operations/actions without internal permission names.
It includes fields, constraints/capabilities, typed annotations, declarative
MANY_TO_ONE relations and optional ordered form presentation. Relations create no
joins, cascades, inverse navigation or extra routes. Presentation validates field
and action references but introduces no UI renderer.

Metadata uses `private, no-cache` and Vary Authorization. Detailed metadata alone
has an opaque ETag: projection is recomputed before If-None-Match, so observable
permission/module changes invalidate it. Neither ETag nor definitionHash is a
record version; If-Match/concurrent-update control is outside this protocol.

Actions have independent permissions and ENTITY, COLLECTION or SELECTION scope.
Selection accepts 1..100 unique typed IDs. Typed parameters use presence/default/
null/constraint rules. Unauthorized availability is false/false without reason
or contextual provider evaluation. Execution rechecks availability every time.
Success is 204; conflicts/unavailability are explicit safe outcomes.

Errors reuse the closed technical ErrorResponse, JSON/no-store and Bearer only
for 401. Undeclared operations produce 405 with Allow. Missing adapters produce
503 `resource_execution_unavailable`; unexpected execution/output failures use
500 `resource_execution_failed`, without exceptions, SQL or policy details.

## Isolated conformance fixture

Only the existing `validation` profile **and**
`ERBAS_MODULES_CONFORMANCE_ENABLED=true` install `validation.catalogue`, owned by
the conformance-only module composition. Its isolated in-memory adapter provides
UUID IDs, required code/name, immutable code, active default true, nullable note,
all CRUD operations and one ENTITY action. It grants only its own validation
permission to enabled administrators. It is a protocol probe, not a business
resource or production persistence facility. Normal composition registers neither
this resource nor its navigation contribution.

Run the sole complete authority with an external clean immutable checkout:

```bash
ERBAS_CONTRACT_DIR=/path/to/clean-v0.14.0-checkout ./bin/check
```

The published v0.14.0 collection has 622 physical requests / 2668 named checks.
It retains Resource Runtime and Navigation and adds contextual presentation;
execution never branches on request numbers.
PUT, bulk CRUD, relation expansion, generic SQL, dynamic schemas, workflows,
record concurrency, Assets and frontend changes remain outside this adoption.

## Contextual presentation (RESOURCE-PRESENTATION-001)

The normative [presentation protocol](https://github.com/alxarafe/erbas-contract/blob/v0.14.0/docs/core/resource-presentation-001.md)
and [closed OpenAPI schemas](https://github.com/alxarafe/erbas-contract/blob/v0.14.0/openapi/core/resources/presentation.yaml)
are read from the immutable v0.14.0 tag.

The additive v0.14.0 endpoint is
`GET /api/meta/resources/{resource}/presentation`, derived by appending
`/presentation` to a discovered resource's `metadataPath`. Its closed response
contains only `id`, `definitionHash` and `presentation`. The identity members
match detailed metadata. Contextual declarations belong to the resource's
structural definition, so changing them changes the shared `definitionHash`.
Neither this hash nor the ETag is a record concurrency token.

Modules may supply `ResourceContextPresentation` in their neutral
`ResourceDefinition`. It is projected separately: the existing closed
ResourceMetadata and legacy `ResourcePresentation` (`form` only) wire shapes
remain unchanged. Normal production registration and the conformance-only status
of `validation.catalogue` are unchanged. A legacy resource returns
`presentation: {}`; missing declarations never cause a 404 for a known resource.

The four optional contexts are LIST, CREATE, EDIT and VIEW. LIST contains only
ordered `columns` (`field` plus optional semantic `label`) and `actions`.
CREATE/EDIT/VIEW directly reuse `ResourcePresentation.Form`, including its
behavior, actions, tabs, sections and groups, without an extra form wrapper.
Array order is authoritative. An explicit empty context is retained and differs
from an absent context. The server publishes declarations, without resolving
visual fallback: clients use the contextual form, then the legacy form, then
metadata field order; LIST uses contextual columns, then metadata field order.

Definition construction rejects unknown or repeated field/action references
within each explicit context. Tab IDs are unique within the form, section IDs
within their tab, group IDs within their section. Separate containers and
contexts may reuse IDs; separate contexts may reuse fields and actions. Action
placement permits COLLECTION for CREATE, ENTITY for EDIT/VIEW, and
COLLECTION/SELECTION for LIST. Unplaced actions remain functional declarations.

Labels are plain semantic text, not HTML or component instructions. Existing
UI.Hidden and UI.FormExcluded annotations remain intact and authoritative for
client display exclusions: Hidden excludes every context; FormExcluded excludes
CREATE/EDIT/VIEW. Placement never overrides these hints. No client renderer or
fallback oracle is implemented in Java.

Presentation grants no authority and changes no field semantics, CRUD writes,
query results, sort/filter/search/pagination, permissions or action availability.
An immutable field placed in EDIT still cannot be patched. Read-only VIEW is a
display context and grants no access to records. No tenant, record-state or
permission-based layout selection is introduced.

The endpoint authenticates the current persisted identity, rejects all query
parameters, resolves the resource and static representation, computes its ETag,
then evaluates If-None-Match through the existing cache helper. It requires no
CRUD permission, adapter or module-enabled/tenant guard. Identical static
representations may share an ETag across users. Both 200 and bodyless 304 retain
ETag, `Cache-Control: private, no-cache` and `Vary: Authorization`. Errors reuse
the existing no-store envelope: 400 invalid_request, 401 unauthorized with Bearer,
404 resource_not_found or safe 500 resource_execution_failed.

Native declaration tests and HTTP/PostgreSQL tests cover closed shapes, form
reuse, empty/absent contexts, definition binding, validation, static hints,
permission independence and conditional caching. The sole complete authority
remains `./bin/check` against the immutable v0.14.0 checkout.

## Local verification evidence

On 2026-10-30, from Java base `d09453ff46c2d7f35240c6287c77ac057ee29fdd`,
focused resource, navigation, architecture and HTTP/PostgreSQL checks passed
before the single complete validation run `erbas-check-220425-31558-7436`.
That run used clean immutable v0.13.0 (tag object
`919684db4f607a575397576b87577cd9582e1f86`, target
`29bd8de3e5c5772fea5096ab2c60abc6a10e55f7`) and ended
with `CHECK_EXIT=0`, `CLEANUP_STATUS=passed` and `CLEANUP_EXIT=0`.

- **936 distinct Java tests passed:** 707 native and 229 PostgreSQL integration
  cases. The 133 additions comprise 125 Core resource/navigation cases, one
  architecture case and seven HTTP/PostgreSQL cases. Existing tests remain enabled;
  independent Core verification is not counted twice.
- Shared live **v0.13.0 conformance passed**, including Resource Runtime and
  Navigation. The release's official read-only inventory/fixture tests also
  passed: 11 static tests and a measured **617 physical requests / 2655 named
  checks**, separate from Java test counts.
- Fresh migrations, final image, health, legacy auth/Users/modules/WMS,
  CORE-CAP-001/002, READ primitives, AI, audit, secrecy probes and exact-owned
  ephemeral cleanup passed. The native context confirms empty resource and
  navigation registration in normal composition.
- No migration, dependency, WMS schema change, Assets code or Eclipse/Docker
  setup change was required. Host target files remain owned by the invoking
  user. Existing metadata HTTP 401, Mockito agent and deprecated testing API
  warnings remain non-blocking.

This is local validation, not remote CI or deployment evidence. Publication
requires separate review and authorization.
