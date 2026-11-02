# Assets

JAVA-ASSET-001 implements one productive resource, `assets.asset`, in the independent
`modules/assets` Maven module. It depends only on Core. The host composes its
configuration, migration location, message basename and administrator permission
policy. Core, WMS and AI have no dependency on Assets.

## Resource and persistence

| Field | Semantics |
| --- | --- |
| `id` | Server-generated UUID, required, read-only |
| `code` | Required case-sensitive string, 1–64 Unicode scalars, unique, immutable |
| `name` | Required nonblank string, at most 255 Unicode scalars |
| `description` | Nullable text |
| `serialNumber` | Nullable string, at most 255 Unicode scalars; no uniqueness rule |
| `lifecycle` | Server-owned enum: `DRAFT`, `READY`, `RETIRED` |
| `createdAt`, `updatedAt` | Required server-owned UTC timestamps |

Values are never trimmed or case-folded. Client input for read-only fields is
invalid. PostgreSQL table `assets_assets` belongs to this module; the module's
`db/assets/V8__assets.sql` participates in the existing shared Flyway history.
No generic persistence table or second migration engine is introduced.

## API and lifecycle

The generic Resource Runtime is the only CRUD API:

| Operation | Endpoint |
| --- | --- |
| LIST / CREATE | `GET` / `POST /api/resources/assets.asset` |
| GET / UPDATE / DELETE | `GET` / `PATCH` / `DELETE /api/resources/assets.asset/{id}` |

CREATE sets `DRAFT` and both timestamps. PATCH uses Core's existing merge rules:
omission preserves, null clears only nullable fields, and a valid value replaces.
Supplying immutable `code` in PATCH produces the standard conflict. Duplicate
codes produce `resource_conflict`, without database details.

`complete` transitions `DRAFT → READY`; `retire` transitions `READY → RETIRED`.
They accept no parameters and execute through
`POST /api/resource-actions/assets.asset/{action}/execute` with the standard
`{"target":{"id":"<uuid>"}}` envelope. Invalid transitions produce
`resource_action_conflict`. Retired assets remain readable but reject PATCH and
DELETE with `resource_conflict`. Only DRAFT assets can be deleted.

Writes use JDBC transactions. Lifecycle checks and writes share a row lock.
PATCH rejects a stale merged timestamp rather than overwriting a newer record.
Each successful update or transition advances `updatedAt`; no global lock is used.

## Permissions and presentation

| Operation | Functional permission |
| --- | --- |
| LIST / GET | `assets.read` |
| CREATE / UPDATE / DELETE / complete | `assets.maintain` |
| retire | `assets.actions.sensitive` |

The existing `FunctionalPermissionPolicy` mechanism supplies effective grants.
Host composition grants these permissions to current enabled administrators.
Assets does not inspect roles. Tests provide reader, maintainer and sensitive
profiles independently of administrator status. Maintenance does not implicitly
grant read permission. A full role administration subsystem is not implemented.

The module contributes an Assets navigation group with an Assets RESOURCE leaf
targeting `assets.asset`, localized in English and Spanish. Existing effective
navigation filtering applies; navigation never grants authority. Disabling the
module removes its navigation and makes execution return `module_disabled`.
Re-enabling preserves the data.

Contextual v0.14 presentation provides list columns and CREATE, EDIT and VIEW
forms using `ResourcePresentation.Form`. EDIT and VIEW place both entity actions.
Read-only and immutable metadata remains authoritative regardless of placement.

## Query and limits

Filtering supports every advertised string operator on `code` (`eq`, `ne`, `lt`,
`lte`, `gt`, `gte`, `contains`, `startsWith`) and enum equality/inequality on
`lifecycle`. Sorting supports `code`, `name`, `createdAt`, `updatedAt`, with the
runtime's identifier tie-breaker. Search is a literal case-sensitive substring
over code/name. Queries execute against PostgreSQL with bound values, without
normalization, joins, expansion or fuzzy search.

There is no Assets frontend or AI integration. Asset types, manufacturers,
models, locations, assignments, maintenance, contracts and dynamic fields are
outside this slice. A later JAVA-AI-ASSET-READ-001 may add governed READ integration;
it is not implemented here. The shared contract remains v0.14.0; module declarations
do not add shared schemas or endpoints.
