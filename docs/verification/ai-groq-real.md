# Real Groq functional verification

These are historical local functional observations from 2026-11-08, using Java
revision `5c83195` from `origin/main`, Groq, the `openai-compatible` protocol and
model `openai/gpt-oss-20b`. They are separate from automated CI and shared-contract
conformance, and do not establish support for every provider or model.

## GROQ-REAL-001: empty catalogues

The application was rebuilt from a clean temporary worktree. Its local `.env`
was linked without copying credentials. Compose project `erbas` replaced only
`app`; the PostgreSQL container and `erbas_postgres_data` volume were unchanged.
The original Assets worktree was preserved. An earlier attempt from an outdated
feature branch had returned `503 assistant_unavailable`; it is not Groq evidence.

The running container received the configured protocol, Groq Chat Completions
endpoint, model and a nonempty credential. `/health` returned 200; administrator
login returned 200. Hikari confirmed a PostgreSQL connection to `erbas_dev` as
`erbas`. No credentials or bearer tokens are included in this report.

| Request | HTTP | Observed answer | Actual `toolsUsed` capability |
| --- | --- | --- | --- |
| Spanish greeting | 200 | Spanish greeting offering help | None (`[]`) |
| List units of measure | 200 | No registered units | `wms.uoms.list` |
| List storage attributes | 200 | No registered attributes | `wms.storage-attributes.list` |
| List item families | 200 | No registered families | `wms.item-families.list` |

AI invocation IDs, in table order: `a7379b77-4182-4330-9373-e96690db2565`,
`02495b8b-7370-4dfe-ae40-c150dd26bc68`,
`5540fd1c-e099-4f29-90c5-069e90515e47`, and
`50f332ba-ca53-4fd7-85c2-bcee8f8fd456`. CORE audit confirmed all three READ
executions completed. No WRITE capability was executed.

Authenticated GET requests to `/api/wms/uoms`, `/api/wms/storage-attributes` and
`/api/wms/item-families` each returned 200 with `items=[]` and `total=0`.
Direct SQL counts on `wms_uoms`, `wms_storage_attributes` and `wms_item_families`
were all zero. The model's conclusions agreed with persisted data; its storage
attribute answer nevertheless used an unnecessary table containing `N/A`.

An unauthenticated assistant invocation returned 401, `unauthorized`.
The 403 permission case was not tested in this run because suitable credentials
were unavailable. Nonempty catalogue fidelity, pagination beyond the first page,
multiple READ calls in one invocation and other providers remained unverified.
Connectivity, protocol interoperability and individual CORE READ execution were
demonstrated for this configuration only.

## GROQ-REAL-002: nonempty catalogues and authorization

The same running application was used without code or configuration changes.
The enabled administrator identity was confirmed through `/api/auth/me` after
login (both 200). All three catalogues were empty before preparation.
The original local result record, reviewed before writing this summary, was
`/tmp/erbas-groq-real-002-results.txt`; successful execution ran from
06:25:03 to 06:25:09 UTC. Only non-sensitive observations are reproduced here.

Six fictitious records were created through the public APIs (201). Subsequent
GET requests returned 200 with two items per catalogue, `offset=0`, `limit=50`
and `total=2`. Direct SQL confirmed IDs, codes and descriptions/names.

| Catalogue | Test code | Persisted description/name |
| --- | --- | --- |
| Units | `GQ002CAJA` | Caja (prueba GROQ-REAL-002) |
| Units | `GQ002KG` | Kilogramo (prueba GROQ-REAL-002) |
| Attributes | `GROQ002_FRAGIL` | Frágil (prueba GROQ-REAL-002) |
| Attributes | `GROQ002_REFRIGERADO` | Refrigerado (prueba GROQ-REAL-002) |
| Families | `GROQ002_ALIMENTACION` | Alimentación (prueba GROQ-REAL-002) |
| Families | `GROQ002_ELECTRONICA` | Electrónica (prueba GROQ-REAL-002) |

The first preparation attempt received 400, `invalid_request`, because the
proposed UoM code `GROQ002_KG` violated the actual UoM rule
`^[A-Z][A-Z0-9]{0,9}$`. No record was created by that rejected request.
Preparation continued with the valid UoM codes above; this was not a Groq failure.

Two associations were created through
`POST /api/wms/item-families/{familyId}/storage-attributes` (201):
`GROQ002_ALIMENTACION` → `GROQ002_REFRIGERADO` and
`GROQ002_ELECTRONICA` → `GROQ002_FRAGIL`. GET on each association collection
returned 200 with one item. A SQL join of `wms_item_family_storage_attributes`,
`wms_item_families` and `wms_storage_attributes` confirmed both pairs.
The assistant did not query these associations. API availability does not prove
their availability as CORE READ tools.

| Query | HTTP | Answer assessment | Actual `toolsUsed` |
| --- | --- | --- | --- |
| A: registered units | 200 | Both exact codes and descriptions, in Spanish | `wms.uoms.list` |
| B: available storage attributes | 200 | Both exact codes and names, in Spanish | `wms.storage-attributes.list` |
| C: existing item families | 200 | Both exact codes and names, in Spanish | `wms.item-families.list` |
| D: combined summary of all three catalogues | 200 | Asked which category to show first instead of producing the requested summary | `[]` |

| Query | AI invocation ID | CORE invocation ID |
| --- | --- | --- |
| A | `fd6f7a15-d3c3-48f8-b8d9-1cbed0371b2e` | `58082fc9-bff3-4fec-a6cc-a39fb89f5866` |
| B | `9d589254-23e5-43fe-aec0-599e0f8c6a09` | `2698bfad-0907-4954-978c-4757cd7dbcf0` |
| C | `25161b65-1fdd-4ecf-aff0-833b04902b38` | `0d63f73b-0f42-43ce-809b-8ec9837fcbea` |
| D | `49a0ee9d-8bb5-49ba-915f-239d826193de` | None |

CORE audit confirmed A–C completed successfully; `toolsUsed` linked each actual
READ execution. All records fit in the first page, and the answers agreed with
both API and SQL results without invented records. No WRITE capability was
executed by AI; fixture creation was performed directly through the public APIs.

D was functionally incorrect despite HTTP 200 and a valid response envelope.
It is an observed model behavior failure, not a demonstrated protocol failure:
the model returned an answer without requesting tools, and no CORE call failed.
Multiple READ execution in a single real-provider invocation was unverified at
the end of this run. No additional Groq calls were made within GROQ-REAL-002 to
retry or reformulate D. See the subsequent GROQ-MULTIREAD-001 observations below.

A separate non-administrator test identity was created through `/api/users`
(201). Login and `/api/auth/me` returned 200; AI, user listing and WMS unit
listing each returned 403, `forbidden`. The denied AI audit entry recorded
`toolsUsed=[]`, with no CORE execution. The temporary identity was then disabled
through `PATCH /api/users/{id}` (200); GET confirmed `enabled=false`, and its
previous bearer token returned 401 on `/api/auth/me`. Existing accounts were
not changed.

The six records and two associations listed above remain pending deliberate
cleanup; they were not removed automatically. The temporary identity remains
disabled. No previous business data was changed or deleted.

**Assessment at the end of GROQ-REAL-002:** Groq connectivity, individual tool calling, successful CORE READ
execution, fidelity for these nonempty catalogues and effective authorization
were demonstrated. Combined-summary quality failed; multi-READ behavior,
larger pagination and other providers were not established. This evidence does
not validate the entire multiprovider MVP or all model behavior.

## GROQ-MULTIREAD-001: sequential READ calls

On the same date, 06:44:47–06:44:51 UTC, the existing application was used without
rebuilding or changing code, configuration or functional behavior. Health and
administrator login returned 200. Effective configuration remained Groq,
`openai-compatible`, `openai/gpt-oss-20b`, with a nonempty credential.
The original evidence is `/tmp/erbas-groq-multiread-001-results.txt`, reviewed for
sensitive data before incorporating this summary. No new provider calls were
made to prepare this documentation.

Exactly two HTTP invocations of the ERBAS assistant were made, without retries.
The second was sent only after the first demonstrated both requested READs and
faithful results. Each invocation allowed the application's existing bounded
sequence of internal Groq turns, with at most one tool request per turn.

| Invocation | Request | HTTP | Ordered `toolsUsed` capabilities | Functional result |
| --- | --- | --- | --- | --- |
| 1 | Explicitly obtain units and storage attributes using the available tools | 200 | `wms.uoms.list` → `wms.storage-attributes.list` | Both catalogues complete, with exact codes and descriptions/names in Spanish |
| 2 | Repeat the original natural request for a summary of units, attributes and families | 200 | `wms.item-families.list` → `wms.storage-attributes.list` → `wms.uoms.list` | All three catalogues summarized faithfully in Spanish |

AI invocation IDs were `62e74560-ee3c-4920-9ec3-0868de8222cc` (1) and
`39b84693-68cd-4b8b-adb8-9fedce650a8d` (2). The following CORE IDs appeared in
`toolsUsed` in actual execution order and in audit entries with
`outcome=completed` and no error:

| Invocation / sequence | Capability | CORE invocation ID |
| --- | --- | --- |
| 1 / 1 | `wms.uoms.list` | `88d09d81-64e7-4ffb-9363-184df1630146` |
| 1 / 2 | `wms.storage-attributes.list` | `696b85e4-02ba-4076-9aef-e6ca02445037` |
| 2 / 1 | `wms.item-families.list` | `0442d935-9255-4a86-95ee-abea694be026` |
| 2 / 2 | `wms.storage-attributes.list` | `705c653d-3c0e-499e-bf53-4e7cc103073d` |
| 2 / 3 | `wms.uoms.list` | `8e580086-d394-4880-9679-008c481c53ae` |

All five CORE executions completed. The effective capability catalogue exposed
the three WMS READ capabilities. API responses and direct PostgreSQL queries
confirmed the same two records per catalogue listed above; every requested code
and description/name appeared in the answers, without invented records. The six
fixtures and their two associations were preserved. No WRITE capability was
executed and no business data was created, modified or deleted during this run.

The **seven HTTP requests to Groq are inferred**, not directly measured network
traffic: two sequential tool turns plus a final-answer turn for invocation 1
(three requests), and three tool turns plus a final-answer turn for invocation 2
(four requests). The inference follows the adapter's one HTTP request per turn
and the successful CORE audit sequence; the evidence contains no traffic capture
or independent provider request counter.

The earlier GROQ-REAL-002 D failure remains a valid historical observation. It
did not recur in this subsequent natural-summary invocation. No adapter defect
or need for a code correction has been demonstrated. These two successes show
that sequential multi-READ is possible, not that the model will consistently
choose the appropriate tools on every request.

## Overall conclusion after these local trials

**Verified for the tested Groq/model configuration:** real-provider connectivity,
individual and multiple sequential READ calls, fidelity to persisted data,
effective authorization (including denial and temporary-user revocation), and
audit/provenance linking AI invocations to actual CORE executions.

**Not verified:** statistical consistency of model behavior, catalogues requiring
multiple pages, or real interoperability with other providers not exercised in
these trials. The earlier model behavior failure is not erased by the later
success. These observations remain separate from CI and shared-contract
conformance and do not establish universal multiprovider compatibility.
