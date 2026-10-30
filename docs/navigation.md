# Effective application navigation (NAVIGATION-CONTRACT-001)

Java adopts immutable **erbas-contract v0.13.0** alongside the
[Resource Runtime](resources.md). The authoritative
[protocol](https://github.com/alxarafe/erbas-contract/blob/v0.13.0/docs/core/navigation-001.md)
defines `GET /api/meta/navigation`, an authenticated request without query parameters.
It accepts standard Accept-Language and If-None-Match.

Modules register `NavigationContribution.Source` beans. Their immutable
contributions contain a module owner and nested entries with globally unique
IDs, label keys, integer order, optional icon, children or an actionable target,
and optional stricter permission requirements. Core validates ownership,
hierarchy, resource references and safe route grammar at startup. No navigation
administration API or persisted menu table is added.

`NavigationRegistry` remains neutral. Core and Navigation know no WMS/AI business
classes. Navigation is curated discovery, not a dump of resource definitions or
an authority grant. Each destination still enforces its own checks.

RESOURCE projects exactly type/resource and must reference a discoverable,
list-capable resource. Current module state, list permission, adapter context and
any stricter presentation requirement filter it. ROUTE projects exactly
type/route: a local absolute ASCII path with alphanumeric/underscore/hyphen
segments and optional trailing slash. URLs, protocol-relative paths, traversal,
query and fragment are rejected. Registering a route creates no page.

On each load, `EffectiveNavigation` refreshes the persisted user, filters current
module/functional permissions, removes inaccessible leaves and recursively empty
containers, resolves labels through the existing MessageSource and locale
negotiation, and orders siblings by order then ordinal ID. Containers have no
target; actionable leaves have empty children. The public response contains only
version/items and final display text, without localization keys, internal
permissions, policies, schemas or visibility flags. An empty tree is valid 200.

The version/ETag binds the effective tree to the authenticated user, trusted
context and effective language. `NavigationContext` supplies the cache context
identity; normal Java composition uses the existing platform context. No tenant
engine, context-selection endpoint or header is introduced. Identical trees for
different users still have different cache identities.

Effectiveness, localization and ordering are recomputed **before** If-None-Match.
A match yields bodyless 304, preserving ETag, `private, no-cache` and Vary
Authorization/Accept-Language. Permission/module changes affecting the tree
invalidate its version. Clients clear their own cached body/ETag on logout or
context switch. There is no polling, event bus, SSE or WebSocket requirement.

Normal production composition currently has no navigation contributions. The
explicit conformance composition adds a neutral group and RESOURCE leaf for its
protocol fixture, with English/Spanish labels from the existing Core bundles.
Native tests cover registry/route validation, pruning, authorization, identity,
context, localization, ordering and caching. HTTP/PostgreSQL tests cover real
identity changes and Accept-Language negotiation/fallback. The same
`./bin/check` authority verifies the published shared collection; see
[validation](resources.md#isolated-conformance-fixture).
