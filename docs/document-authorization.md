# Internal document authorization (RAG-PERM-001)

This contract prepares shared knowledge authorization without implementing RAG,
document persistence, retrieval, an HTTP API or an AI provider integration.
It adds no permissions, roles, user fields, organizational tables or dependencies.
The existing `FunctionalPermissionPolicy` semantics remain unchanged.

## Contract and explicit composition

`DocumentReference` contains only an opaque document ID, owning module ID,
nonempty required functional permissions and `modulePolicyRequired`. References
must come from trusted module/server metadata, never a request body, model output
or untrusted document contents. They are not grants or document contents.

`DocumentVisibilityPolicy` supplies the independent document visibility decision.
`DocumentAccessPolicy` declares one module owner and its additional restriction.
A module registers policy instances through the collection passed to
`DocumentAuthorization` at trusted composition time; Spring compositions can
collect module-owned policy beans, as the WMS fixture demonstrates. Registered
owners must be available in `ModuleRegistry`. Registration bindings are fixed;
policy decisions must consult current authoritative state.

There is deliberately no production document authorization bean, controller or default
visibility policy yet. Future composition must explicitly provide current-user
lookup, existing functional policies, visibility and module policies. Omitting
visibility is a configuration error, not public access. This is an in-process
trust boundary, not a sandbox for malicious plugin code claiming another owner.

## Cumulative checks and revocation

For every `allowed(subject, reference)` call, authorization requires all of:

1. Current user lookup resolves the requested subject and the user is enabled.
2. The reference owner is available and currently enabled.
3. Every required functional permission is granted by an existing functional
   policy. As elsewhere in CORE, policies are OR-combined for one permission;
   distinct required permissions are AND-combined.
4. The independent visibility policy allows this document.
5. All registered restrictions for this owner allow access. If
   `modulePolicyRequired=true`, at least one owner policy must be registered;
   absence denies. If false, absence alone is acceptable, but registered vetoes
   still apply. A module needing several conditions must register all of them or
   compose them within its own policy; the flag does not name individual policies.

No administrator bypass exists for visibility or module restrictions. Foreign
module policies neither satisfy a missing owner policy nor affect that owner's
documents. Failed runtime authority lookups/evaluations deny access. The result
is only a boolean; internal reasons or restricted document metadata are not
returned. Identity, functional grants, visibility, module state and module-owned
memberships are reevaluated on the next query, without cached authorization.

`visibleRelations` returns an edge only after authorizing both endpoints. A
denied edge is omitted entirely, including its identifiers. Future consumers
must not expose rejected candidates through counts, metadata, citations or
alternative traversal paths. Previously returned values are not live views;
callers must query again before a later disclosure.

## WMS evidence and validation

`WmsDocumentAuthorizationTests` composes the real WMS functional permission
bridge with test-only document references, visibility and departmental membership
sets. Multiple memberships, revocation, disabled/deleted/replaced identities,
module disable, absent policies, cumulative vetoes, foreign ownership, failed
authorities and both relation directions are exercised. Departments exist only
inside these fixtures; no organizational structure enters CORE or production WMS.

Focused tests run with the repository's pinned Maven/Java 25 container. The sole
complete authority remains `./bin/check` with an explicit clean published
v0.14.0 contract checkout; successful focused tests alone do not authorize the
task's final commit. No Groq or Ollama requests are needed for this contract.

Local validation on 2026-11-08 completed in run
`erbas-check-154757-18273-25252`: `CHECK_EXIT=0`, `CLEANUP_STATUS=passed`,
`CLEANUP_EXIT=0`. The log records 806 distinct native Java tests (including the
22 document authorization cases), 246 integration tests and successful shared
v0.14.0 conformance, image/startup, migrations, health and secrecy checks.
The focused Docker run also passed all 22 document authorization cases.

## Proposed AI-RAG-001A integration and limits

AI-RAG-001A should obtain references from a trusted knowledge source and apply
this authorization before returning content, excerpts, identifiers, metadata,
relations or citations, and before supplying any of them to an AI provider.
Retrieval and relationship expansion must use the same checks; an existing AI
invocation permission never substitutes for document authorization. A future ERP
module may supply departments or multiple memberships entirely within its policy.

Persistence, document visibility rules, retrieval implementation, public API,
embeddings, vector storage, provider integration and knowledge audit are outside
this task. Reference integrity and complete policy registration remain trusted
composition responsibilities. Authorization and a future content read are not
atomic: concurrent revocation between check and disclosure remains unresolved.
AI-RAG-001A must design that consistency boundary and information-leak tests
before exposing a real retrieval path; this contract alone does not secure one.

The subsequent [AI-RAG-001A-1 storage slice](knowledge-storage.md) adds internal
document persistence in an independent Knowledge module. It does not expose a
retrieval path or supply authorization composition and leaves this boundary intact.
