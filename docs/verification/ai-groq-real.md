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
