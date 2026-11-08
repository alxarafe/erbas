# MCP-SPIKE-001: experimental stdio adapter

This isolated experiment exposes only `wms.uoms.list` through MCP. It is not
part of the backend Maven reactor or normal ERBAS composition. It contains no
Spring Boot application, CORE/WMS dependency, JDBC access, HTTP MCP endpoint,
RAG integration or model-provider call. Existing public contracts remain intact.

## SDK and runtime

The official Java SDK is pinned to **2.0.1**, MIT licensed. Its maintained 2.0
line targets specification **2025-11-25**; the artifact targets Java 17 and this
experiment compiles/runs on the project's pinned Java 25 images. It uses the
classic `McpServerSession` and `StdioServerTransportProvider`, not a preview
stateless/modern server. See the official [release history](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/CHANGELOG.md),
[SDK POM](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/pom.xml),
[license](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/LICENSE)
and [classic server documentation](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/docs/server.md).

The SDK's high-level server lists registered tools without a per-request
discovery callback. Explicit classic session handlers allow current CORE
discovery on each list without maintaining a cached authorization catalogue.
The SDK owns JSON-RPC framing, session lifecycle and serialization. The server
offers only ping, tool listing and calling, with initialization negotiating
2025-11-25 and no change notifications. Stdout is exclusively MCP. No logging
backend is installed; SDK SLF4J binding notices, when present, go to stderr.

Direct dependencies are the SDK bundle and test-only JUnit 6.0.2. SDK transitive
runtime artifacts are pinned by its published POMs: Jackson 3.1.4/annotations
2.21, Reactor 3.7.0, Reactive Streams 1.0.4, SLF4J API 2.0.16, NetworkNT JSON
Schema Validator 3.0.6, itu 1.14.0 and SnakeYAML Engine 3.0.1. They do not enter
the backend dependency graph. The SDK's MIT notice ships in the runtime image;
bundled dependencies retain their own notices and licenses.

## Authorization and mapping

The process requires `ERBAS_MCP_CORE_URL` (trusted CORE origin, without path,
credentials, query or fragment) and `ERBAS_MCP_TOKEN` (existing opaque bearer
token). Provision the token through the process environment, never command-line
arguments, source, tool arguments or logs. One process represents one identity;
there is no service administrator fallback or token renewal. Environment
provisioning is not a security boundary against a privileged host/container user.
The operator must choose a trusted origin; internal HTTP is intended only for
an isolated Docker network, with HTTPS for nonlocal deployments.

`tools/list` performs authenticated
`GET /api/core/capabilities/wms.uoms.list`, the existing effective discovery
lookup. It verifies the exact ID, READ effect and supported input schema before
advertising a single tool. A CORE 404 produces an empty list; authentication or
transport failure produces a safe MCP error. Discovery is repeated on every
operation, with no background polling or authorization cache.

The published v0.14.0 `CapabilityListInput` reference is resolved explicitly to
a closed, self-contained JSON Schema: optional integer `offset >= 0` (default
0) and integer `limit` from 1 through 100 (default 50). Unknown references or
changed schema shapes fail closed; this is not a generic schema resolver.

`tools/call` rejects any name other than `wms.uoms.list`, then sends arguments
inside `{input: ...}` to authenticated
`POST /api/core/capabilities/wms.uoms.list/invocations`. Identity/permission
fields are never execution context: CORE rejects them as invalid input.
CORE reauthenticates and applies current functional permission, module state,
validation, dispatch, output validation and audit on every call, including calls
made after listing or without listing. The adapter never grants authority.

Success returns the CORE envelope (`invocationId`, `capabilityId`, `output`) in
MCP `structuredContent` and JSON text content. CORE errors become `isError=true`
with only a safe technical code and HTTP status. Unknown names and malformed
tool request shapes are MCP protocol errors; failures before CORE have no CORE
invocation ID. Denied capability discovery/invocation normally uses CORE's 404
concealment, not a new 403 policy invented by the adapter. No new audit store or
correlation field is introduced. Successful IDs link directly to existing CORE
audit; authentication rejection occurs before capability audit.

The JDK HTTP client disables redirects and proxy discovery, bounds complete
responses to 1 MiB and 10 seconds, and performs no application retries. Stdio
input is bounded to 64 Ki characters per message. These are spike limits, not
changes to the shared HTTP contract.

## Docker usage and validation

Build the standalone runtime from the repository root:

```bash
docker build -f experiments/mcp-stdio/Dockerfile -t erbas-mcp-spike:0.1.0 .
```

Launch from an MCP client's command configuration, with environment provisioned
securely by the operator. `-i` preserves stdin; never allocate a TTY:

```bash
docker run --rm -i --read-only --tmpfs /tmp --network YOUR_TRUSTED_NETWORK \
  --env ERBAS_MCP_CORE_URL --env ERBAS_MCP_TOKEN erbas-mcp-spike:0.1.0
```

The multistage runtime contains only Java, the adapter and runtime libraries,
runs as UID 10001, and has no source, Maven, credentials or exposed ports.
Its health check tests public CORE liveness; it does not validate token authority.

Focused simulated-HTTP tests, using an independent raw JSON-RPC client and a
real Java subprocess, run during the verification image build:

```bash
docker build -f experiments/mcp-stdio/Dockerfile --target verification \
  -t erbas-mcp-spike-focused:0.1.0 .
```

The explicit integration authority for this experiment is:

```bash
./bin/check-mcp-spike
```

It creates a unique Compose project, clean PostgreSQL and an isolated test ERBAS
instance. The MCP adapter itself never starts ERBAS; development is untouched.
There are no published ports. The test creates two UoMs and test identities via
public APIs, then runs the independent client against a child process using the
packaged adapter JAR and runtime libraries. It verifies parity with both WMS and
CORE HTTP output, permission loss after listing, user disable, WMS disable,
invalid credentials/arguments and unknown names. CORE IDs are matched against
completed audit entries; denied subjects are also checked. SQL verifies fixture
contents/count and all eight migrations. The final adapter image has a liveness
smoke test. Cleanup checks exact ownership and removes only that run's resources
and image tags, retaining evidence logs in the reported private `/tmp` directory.

Timing is reported separately for Java process spawn plus MCP initialization
(three samples), and direct CORE HTTP versus stdio-plus-CORE HTTP operation
(three warmups, twenty paired samples, p50/p95). It excludes Docker startup,
JVM warmup from operation samples, build time and model latency. It measures
against the HTTP CORE baseline, not a Java in-process call, and is not a load
test or a statistical performance guarantee.

The backend's separate full validation remains `./bin/check` with an explicit
clean published contract checkout; the experiment's standalone POM is not
silently added to that command or the backend's normal CI.

## Observed results

The final isolated run on 2026-11-08,
`erbas-mcp-spike-247181-31932-13789`, passed eight focused tests and the live
integration case. All 24 successful MCP-triggered CORE IDs matched completed
CORE audits; both denied test subjects matched denied audits. Revocation after
listing, WMS disable/restore, invalid credentials, forbidden identity arguments,
invalid pagination and unknown tool names behaved as expected. Both persisted
UoMs matched API output and SQL; all eight migrations were present.

| Measurement | Samples | p50 (ms) | p95 (ms) |
| --- | --- | --- | --- |
| Java process spawn and MCP initialization | 3 | 556.558 | 560.500 |
| Direct CORE HTTP invocation | 20 | 6.785 | 7.665 |
| MCP stdio plus CORE HTTP invocation | 20 | 8.719 | 9.392 |

The difference between the two operation medians was 1.934 ms in this local
sample. This is not a paired per-call overhead distribution or a measurement of
the existing Java in-process path. Runtime dependencies total 13 JARs and
6,239,033 bytes (about 5.95 MiB), excluding the JRE and adapter JAR. Production
integration code comprises three Java classes with no backend reactor changes.

The run reported `MCP_SQL=passed`, `MCP_AUDIT=passed`,
`MCP_CLEANUP_STATUS=passed`, `MCP_CLEANUP_EXIT=0` and `MCP_CHECK_EXIT=0`.
The final runtime image liveness probe also passed, including the SDK license
notice added after the first run. Temporary image tags use an explicit version.
Its local log is `/tmp/erbas-mcp-spike-001-final-live.log`; raw client and app audit
evidence remains in the private directory printed by that runner. No test data
or service from this run was retained, and no development data was used.

## Limits and adoption decision

| Aspect | Existing internal execution | Experimental MCP boundary |
| --- | --- | --- |
| Interoperability | Java callers in the application | Independent stdio MCP client verified; desktop integrations remain untested |
| Authentication and permission | Trusted identity supplied to CORE | Existing HTTP bearer authentication on each operation; same CORE policies |
| Audit | Existing CORE invocation audit | Same CORE audit and invocation IDs; transport rejections have no CORE invocation |
| Complexity | Existing discovery and invocation services | Separate process, three adapter classes, 13 runtime dependency JARs, schema mapping and test runner |
| Performance | In-process latency not measured here | Process startup measured separately; operations compared with direct CORE HTTP, not in-process Java |

The experiment covers one known READ capability, one identity per local process
and one classic protocol version. It does not implement general capability
exposure, HTTP MCP/OAuth, multiuser sessions, progress, cancellation forwarding,
token refresh, subscriptions, prompts, resources or model integration. Slow
clients and concurrency beyond the bounded SDK/HTTP behavior are not stress
tested. It is not a claim of full MCP conformance or compatibility with every
desktop client. The independent client deliberately shares no SDK client API
or adapter invocation handler.

MCP can provide an external interoperability boundary while retaining CORE as
the authority. It adds a process, SDK/transitive dependencies, credential
provisioning, schema mapping, lifecycle/error translation and a second test
surface. It does not benefit the existing internal Java path by itself.
Adoption should remain optional and depend on a concrete external client need,
measured results and subsequent review of authentication and operational scope;
this spike does not replace CORE or authorize production deployment.
