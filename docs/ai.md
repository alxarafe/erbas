# AI assistant READ orchestration (AI-001)

Java adopts **erbas-contract v0.11.1** directly. AI-001 was introduced in v0.11.0;
v0.11.1 changes only the conformance runner timeout, with no HTTP or semantic change.
The immutable annotated tag object is `5ea047b3375cf0362397ee1e21c95d4991f09c10`,
targeting `5517f0df5d3cacc57c35ef704131dcb30ad9dde2`.
The normative [design](https://github.com/alxarafe/erbas-contract/blob/v0.11.1/docs/modules/ai/ai-001.md),
[OpenAPI](https://github.com/alxarafe/erbas-contract/blob/v0.11.1/openapi/modules/ai/contract.yaml)
and [contract verification](https://github.com/alxarafe/erbas-contract/blob/v0.11.1/docs/verification/ai-001.md)
remain owned by the contract repository. Earlier Java verification reports remain historical.

## Module and security boundary

`modules/ai` builds the ordinary `erbas-ai` Maven library, depending only on
`erbas-core`. The host explicitly imports `AiConfiguration`. `AiModule` registers
`erbas.ai` through the existing module pipeline, with no module dependencies,
special loader or migrations. Availability and persisted enabled state use the
existing module registry/state services and `/api/modules` administration.
Disable and restore affect the next request without restarting.

`POST /api/ai/assistant/invocations` uses the existing bearer authentication.
`AssistantInvocation` then checks the independent `ai.assistant.invoke` permission,
requires `erbas.ai` to be available/enabled, and only then reads/parses the body.
The current `AiAssistantPermission` bridge grants AI access only to enabled
administrators. It is not a CORE business permission policy and grants no WMS
permission. Tests independently grant AI to a normal user without granting any
business capability. No role/grant administration or persisted grants are added.

The closed body contains exactly `message`, a nonempty, non-whitespace string
without NUL or unpaired surrogates. The limit is 4000 Unicode scalar values,
including valid supplementary characters; UTF-16 code units are not the limit.
The invocation parser rejects duplicate JSON keys, trailing tokens, extra fields
and caller-supplied execution/security context. No history or configuration fields
are accepted. Human adapter text uses native AI-owned English/Spanish resource
bundles and standard `Accept-Language`, with deterministic English fallback.

## Providers and grounding

`AiAssistantProvider` models a synchronous turn: final answer or one untrusted
tool request. After a successful CORE call, the provider receives its actual
output and may request another tool or return its final answer. No agent
framework, provider SDK, API key, internet call or real LLM is installed.

Normal/default composition selects `UnavailableAssistantProvider`: valid authorized
requests return `503 assistant_unavailable`. The deterministic adapter is selected
only with the existing explicit `validation` Spring profile, used by tests and
the isolated conformance application. It is never the default provider.

`DeterministicAssistantProvider` recognizes the published fixture intents for a
greeting, one catalogue and multiple catalogues. It does not hard-code business
results. It requests relevant effective READ capabilities and builds summaries
from the returned CORE results. With no relevant effective capability, it says
that it cannot obtain the information rather than inventing current ERP state.
Tests change persisted data and verify that the answer changes accordingly.

## CORE execution and provenance

The tool catalogue comes exclusively from `CapabilityDiscovery.effective`, filtered
to `effect=read`. AI knows no WMS services, controllers, repositories, JDBC or HTTP
loopback. Future registered READ capabilities enter the catalogue through normal
CORE registration; no WMS list is hard-coded in orchestration. Only the validation
adapter owns the published WMS fixture intent identifiers.

Every tool request goes through the real `CapabilityInvocation.invoke` service.
Its body is built internally from the structured tool input, with no caller
identity or permissions. Before each call, the composition refreshes the trusted
user through CORE `UserAdministration.find`, including changes during the same
HTTP request. CORE reevaluates the effective lookup, READ effect, module state,
permissions and input, executes its provider handler, validates output and audits.
AI does not duplicate authorization or dispatch. A denied/failed tool terminates
orchestration safely, without privilege escalation or retries.

`MAX_TOOL_CALLS` is a finite server-owned budget (currently eight). Exhaustion,
invalid provider turns/answers and unrecoverable execution failures return the
safe execution error. The budget is not request or response state.

Successful responses have exactly `invocationId`, nonempty `answer` and
`toolsUsed`. The AI ID is a new server-generated opaque identifier, distinct from
every successful CORE invocation ID. `toolsUsed` is always present and preserves
successful execution order; each entry contains only `capabilityId` and
`invocationId`. There is no `primitiveId` or future provenance union. Failed or
denied attempts and raw inputs/outputs never enter this metadata.

## Errors and audit

| Status | Code | Precedence/meaning |
| --- | --- | --- |
| 401 | unauthorized | Authentication, before permission/module/body |
| 403 | forbidden | AI permission, before module/body |
| 503 | module_disabled | AI unavailable/disabled, before body |
| 400 | invalid_request | Invalid body after all gates |
| 503 | assistant_unavailable | Temporary assistant/provider unavailability |
| 500 | assistant_execution_failed | Invalid provider result, exhausted budget or tool/orchestration failure |

All responses are closed JSON and `Cache-Control: no-store`, without redirects.
Only 401 carries `WWW-Authenticate: Bearer`. No `Retry-After` is added. Safe errors
have one matching detail and expose no provider, prompt, reasoning, SQL, stack
trace, hidden CORE metadata or invocation ID.

`AiAssistantAuditSink` is independent from CORE audit. Its safe default logger
records the AI ID, available authenticated subject, timestamp, completed/denied/failed
outcome, duration, successful tool identity links and safe error code. Correlation
remains null when unavailable. An AI-owned servlet filter outside Spring Security
also audits rejected authentication without reading credentials or request body.
CORE continues auditing every tool attempt. Neither audit includes full user
messages, prompts, answers, provider responses, tool input/output or credentials.
There is no public audit API or persistence table.

## Validation and exclusions

Use the sole complete authority with an explicit immutable checkout:

```bash
ERBAS_CONTRACT_DIR=/path/to/clean-v0.11.1-checkout ./bin/check
```

The isolated app already uses the validation profile; no LLM service is needed.
CI pins the same v0.11.1 tag and validates its annotated object and target before
provisioning and shared calls. Shared inventory is 590 requests / 2587 named checks,
30 requests / 120 checks more than v0.10.0. These counts are separate from Java tests.

Native tests cover scalar/strict parsing, module/permission precedence, provider
selection, catalogue filtering, AI-only authorization, untrusted tools, current
authority, grounding, budget, safe failures and payload-free audit. Real
PostgreSQL/HTTP tests exercise successful CORE/WMS calls, persisted facts, module
disable/restore, identity changes within one request, native localization and safe
errors. Architecture/artifact tests enforce AI-to-CORE dependency direction and
module-owned resources without new migrations.

Real providers, conversations/history, memory/RAG, WRITE tools, external tools,
MCP, autonomous/background agents, streaming, multimodal inputs, billing, tenants,
Resource Runtime, Assets and client changes remain outside this implementation.

## Local verification evidence

On 2026-10-30, from Java base `c6da60af9debd23ba9abfee19cf3e7ada26c7b36`,
`JAVA-AI-ASSISTANT-001` passed the complete command with
`ERBAS_CONTRACT_DIR=/home/rsanjose/Desarrollo/Alxarafe/erbas-contract-v0.11.1`.
Run `erbas-check-96114-21565-918` ended with `CHECK_EXIT=0`,
`CLEANUP_STATUS=passed` and `CLEANUP_EXIT=0`.

- **760 distinct Java tests passed:** 542 native and 218 integration cases with
  real PostgreSQL, 110 more than the 650-case baseline. Independent CORE checking
  passed 328 tests, not counted twice.
- New coverage comprises 76 AI native cases, 33 AI HTTP/PostgreSQL cases and one
  additional architecture guard. Existing guards retain their assertions, with
  explicit AI descriptors/counts added to normal composition expectations. The
  absent-route fixture now uses a genuinely absent path instead of the newly
  implemented AI path. The first full check caught these stale fixtures; they
  were corrected and the entire check was rerun successfully.
- Shared live **v0.11.1 conformance passed**, using the deterministic adapter.
  The official read-only inventory test measured **590 requests / 2587 named
  checks** and passed independently of the Java suite.
- Final image, fresh migrations, authentication/Users, modularity, WMS,
  CORE-CAP-001/002, AI-001, both audit boundaries, post-conformance database
  invariants, database-down liveness, Actuator, sensitive-log checks and exact-owned
  ephemeral container/network/volume cleanup passed.
- No migrations or external dependencies were added. Optional Maven metadata
  HTTP 401, Mockito dynamic-agent and deprecated-test-API warnings remain
  non-blocking. No external provider, key or quota was used.

This is local verification, not remote CI or deployment evidence. No push, tag,
release or publication is part of this ticket.
