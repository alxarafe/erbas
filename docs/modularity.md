# Module extension model

## Architectural requirement

ERBAS must support future third-party modules without modifying or recompiling
its CORE. Production selects one implementation: Java or .NET. Java plugins may
be distributed as Java JARs/packages and .NET plugins as assemblies/packages.
They share modular semantics, not binary compatibility or a cross-runtime RPC
protocol. Physical external loading is not implemented in this task.

Built-in modules and future third-party modules use the same pipeline:

```text
ModuleSource(s) -> ModuleRegistry -> ModuleGraph -> ModuleRuntime
```

## Physical Maven ownership

The root `pom.xml` is an aggregator and dependency-management parent. It does not
produce an executable application. Three artifacts compose the one process:

| Source root | Artifact | Responsibility | Internal dependencies |
| --- | --- | --- | --- |
| `erbas-core/` | `erbas-core` | Non-disableable platform: Health, HTTP/i18n, authentication, users, collections, module contracts/runtime/gating and CORE persistence | None |
| `modules/wms/` | `erbas-wms` | WMS UoM, StorageAttribute, ItemFamily and association domain/application, HTTP/JDBC adapters, Spring composition and migration resources | CORE |
| `erbas-host/` | `erbas-host` | Executable Spring Boot composition root and runtime configuration | CORE and WMS |

Java packages remain stable. `ErbasApplication` imports `CoreConfiguration` and
`WmsConfiguration` explicitly; it does not scan the parent package to discover
functional modules. CORE scans only its owned platform packages. WMS explicitly
imports its own HTTP adapters and registers its services, descriptor and Spring
Security customizer. Both authentication and that customizer use the generic
`PlatformAuthorities.ADMINISTRATOR` contract, with the same existing authority.
No WMS domain or persistence classes are imported by the production host.

CORE builds and tests without WMS. WMS domain/application remain free of Spring,
JDBC, Flyway, HTTP adapters and host classes. Host tests cover the composed
application and real PostgreSQL; owning modules retain their native tests.
Permanent source/POM/resource guards and packaged-JAR integration guards enforce
these boundaries. See the [architecture decision](decisions/0002-physical-module-boundaries.md)
for migration compatibility and verification commands.

WMS owns V5 UoMs, V6 Storage Attributes and V7 Item Families/associations under
`modules/wms/src/main/resources/db/wms`. The host composes these with CORE V1–V4
in one validated Flyway history. Existing SQL migrations remain immutable.
WMS serializes association writes by locking the owning family row, evaluates
reference existence and pre-state conflicts before any writes, and commits the
accepted delta in one PostgreSQL transaction. CORE owns only the generic technical
`ContractError` representation; WMS maps its own domain issues to codes/context.

Installed WMS remains available and technically initialized when disabled. Its
artifact, schema and data stay installed; only functional access is gated.
Removing an installed module artifact from an existing database is not an
uninstallation mechanism. There is no dynamic plugin loader or separate service.

## Descriptor, version and availability

`ModuleDescriptor` is immutable and contains only an ID, Module API version and
an immutable dependency ID list. IDs are stable, opaque, case-sensitive and
identify modules persistently. They are chosen by the module owner, independently
of class names, packages, assembly names or package filenames. Third parties are
responsible for sufficiently unique IDs. Descriptors must remain stable throughout
startup and are the sole source of identity, version and dependency metadata.

The initial Module API version is 1, centralized in the platform's Module API
constant. Registration supports only that current version. An incompatible version
fails with the module ID and declared version before service configuration or
initialization. There is no negotiation, multi-version compatibility or version
range resolution.

A source supplies modules for the current startup. The framework-independent
registry gathers all sources, rejects duplicate IDs within or across sources,
validates API versions and exposes a read-only snapshot in ordinal/natural ID
order. Presence in this registry is the authority for **AVAILABLE**: discovered
by a valid source during this startup. Availability is not persisted. A future
package source could make a module available when its package is present and
absent after removal and restart without changing the registry or graph.

The graph consumes all available modules, validates missing dependencies and
cycles, and traverses root IDs and dependency IDs in ordinal/natural,
case-sensitive order. Dependencies precede consumers; every module is included
once. The topological result is deterministic and read-only. Dependencies can
cross source boundaries. Zero sources or modules are valid.

## Technical initialization and CORE boundaries

All AVAILABLE modules initialize sequentially in resolved dependency order.
Failures propagate and stop subsequent initialization. There is no rollback,
retry or parallel initialization. **INITIALIZED does not mean ENABLED**:
initialization remains technical even when the persistent enabled flag is false.

The CORE is not a disableable functional module. Basic Health, essential
authentication, the modular runtime and essential system administration remain
outside functional module activation. AUTH/USERS behavior is unchanged.

Modules must not access another module's internal tables or private repositories.
Future integration must use public contracts/capabilities of the owning module,
including future Inventory and AI integrations. No bus or capability registry is
introduced without a real consumer.

## Persistent dynamic state

**ENABLED** is administrative state stored in PostgreSQL, independently of
AVAILABLE and INITIALIZED. **OPERATIONAL** is not modeled. State belongs to the
platform/CORE, never to Security or a business module.

The platform table `module_states` has exactly two columns: `module_id TEXT
COLLATE "C" PRIMARY KEY` and `enabled BOOLEAN NOT NULL`. IDs retain the descriptor's
case-sensitive semantics and are not constrained by class or package names.
There are no module foreign keys, timestamps or configuration fields.

After registry/graph validation, startup synchronizes state before technical
initialization. Missing rows are inserted with the centralized initial policy
`enabled = true` to preserve existing behavior. Existing flags are never rewritten.
Rows for unavailable modules remain untouched: removing a package and restarting
removes availability, but reinstalling the same ID recovers its previous state.
An enabled module whose dependency is disabled makes startup fail; synchronization
rolls back rather than repairing persisted choices or leaving partial inserts.
Zero available modules are valid and do not remove retained state.

`ModuleStateService` reads the database for every enabled check; it keeps no
persistent enabled-state cache. A committed change is immediately visible to a
new check, including from another service instance sharing this database.
Availability is still checked against the current registry, never inferred from
a retained database row. Unknown/unavailable IDs cannot be queried, enabled,
disabled or passed to `requireEnabled` (named `RequireEnabledAsync` in .NET).

Enable requires all declared dependencies to be enabled. Disable fails while an
enabled module depends on the target. There are no automatic cascades: disable
consumers before dependencies; enable dependencies before consumers. Validating
every enabled module's direct edges also preserves the invariant through indirect
dependency chains. Repeating an already satisfied transition succeeds without
rewriting unchanged rows. Failures identify the module and offending dependency.

Synchronization and transitions use a READ COMMITTED transaction and acquire
`LOCK TABLE module_states IN SHARE ROW EXCLUSIVE MODE` before reading the snapshot.
One lock serializes modifications, including initial insertions into an empty
table, without a multi-row lock order or permanent memory authority. Validation
precedes writes; exceptions roll back the whole operation. This deliberately
simple policy fits a small module set and coordinates separate application
instances. Ordinary checks continue to read committed database state.

Disabling never deletes module data, tables or configuration, reverts migrations,
unloads code, or changes technical initialization. All available modules still
initialize, including disabled ones. `requireEnabled` provides the shared internal
guard used by the platform HTTP gate. The CORE administration API below is outside
that gate; INITIALIZED never substitutes for permission to execute functional work.

## CORE module administration

Java adopts published ERBAS Contract `v0.8.0` through administrator-only
`GET /api/modules` and `PATCH /api/modules/{id}`. Spring Security applies existing
bearer authentication and the central `ADMIN` authority before MVC body/query
validation, descriptor lookup or state changes. Missing/invalid authentication
returns the existing closed 401 response with a Bearer challenge; non-admin users
receive the existing closed 403 without that header. No new RBAC is introduced.
These CORE endpoints never use `@ModuleEndpoint` and remain usable when all
functional modules are disabled.

The closed Module contains only `id`, `moduleApiVersion`, `dependencies` and
`enabled`. Descriptor metadata comes exclusively from the current registry,
dependencies are sorted by natural case-sensitive ID order, and enabled state is
read from persistence for each response. Historical rows do not make an absent
module available. The list uses registry ID ordering before pagination and the
same `PagedCollection` envelope, defaults, bounds and parser as Users, preserving
COLLECTIONS-001 and existing user behavior. Empty and beyond-total windows return
200 with complete metadata. Pagination and wire schemas are specified by the
[published OpenAPI](https://github.com/alxarafe/erbas-contract/blob/v0.8.0/openapi/erbas.yaml).

PATCH accepts exactly one required boolean `enabled`. Malformed JSON, arrays,
scalars, missing/nonboolean values and extra fields return 400 `invalid_request`.
Successful transitions, including idempotent ones, return 200 with the complete
current Module. Case-sensitive unavailable IDs return 404 `module_not_found`.
`ModuleStateService` remains the sole state authority: its existing transaction,
snapshot validation and store lock provide atomic dependency-safe changes without
cascades. A dependency violation returns 409 `module_dependency_conflict`.
Narrow `ModuleUnavailableException` and `ModuleDependencyConflictException`
identify those cases without parsing messages. Missing state and unexpected
failures retain internal errors rather than becoming false 404/409 responses.
All specified responses are JSON with `Cache-Control: no-store`; only 401 carries
`WWW-Authenticate`. Closed error bodies expose no internal IDs or details.

### Explicit canonical conformance composition

`ConformanceModulesConfiguration` contributes one `ModuleSource` only when the
`validation` profile and `erbas.modules.conformance.enabled=true` are both set.
The environment binding is `ERBAS_MODULES_CONFORMANCE_ENABLED`, default false.
`docker/compose.validation.yaml` enables it explicitly; development/production
composition does not register it. Normal startup registers WMS, without these
conformance descriptors.

The source contributes exactly `erbas.dev.math` (no dependencies) and
`erbas.dev.statistics` (depends on Math), using `ModuleApi.CURRENT_VERSION`.
Their IDs are centralized in that configuration. They are reserved development
descriptors with no business behavior, mathematical functions or functional HTTP
endpoints. No extra descriptor fields distinguish them.

The source enters the ordinary registry/graph/runtime pipeline. Startup uses
normal state synchronization to insert initially enabled rows; no special DB seed
or reset endpoint exists. Existing persisted choices are preserved. Shared
conformance mutates both flags in an isolated disposable database and leaves both
enabled on success. This composition adds no external loader, bus or capability
registry.

## Functional HTTP gating

DISABLED means available and technically initialized, but functional entry is
blocked. Endpoints, routes, services and code remain registered; disabling does
not delete data or revert migrations. A request checks `ModuleStateService` once
at its module boundary, using the stable descriptor ID. It never reads the store
directly or captures an enabled flag during startup. Disable/re-enable takes effect
on the next request without restart, route rebuilding or reinitialization.
PostgreSQL remains the authority; no enabled-state cache is introduced.

The observable order is authentication, authorization, module gate, endpoint.
Existing protected endpoints return their normal 401 for invalid/missing credentials
and 403 for insufficient permissions before reaching the gate. An authorized caller
to a disabled module receives HTTP 503 with JSON exactly:

```json
{"code":"module_disabled"}
```

The response sets `Cache-Control: no-store`, with no `WWW-Authenticate`,
`Retry-After`, module ID, internal message or stack trace. A public module endpoint
also passes through the gate, without an authentication requirement of its own.

`ModuleDisabledException` belongs to Modularity and retains the module ID only
internally. `requireEnabled`/`RequireEnabledAsync` throws it only for an available
module with a persisted false flag. Unavailable IDs, missing state and unexpected
system failures retain their distinct internal errors; the HTTP gate does not
misrepresent them as administrative deactivation. The gate never changes state
or bypasses dependency validation.

CORE remains outside this gate: Health, authentication/login, user administration,
the runtime and future recovery/administration infrastructure are not functional
modules. All available modules still initialize technically even when disabled.
Future scheduled jobs, event consumers, AI capabilities and other externally
triggered internal actions must use the same `ModuleStateService.requireEnabled`
authority at their entry boundary; no such execution mechanisms are implemented.
Future external modules receive the same HTTP semantics through the public adapters.

## Future lifecycle, not implemented

Installation will mean physical incorporation of a module package; uninstallation
will mean its physical removal. Both may initially require restart. DISABLE is
not UNINSTALL, and UNINSTALL does not imply deleting data. PURGE, if ever provided,
will be explicit and independent. None of these operations is implemented.

External directory discovery, JAR/DLL loading, hot loading/unloading, installers,
installation/uninstallation endpoints and frontend remain out of scope. No start/stop,
activation hooks, event bus or cache invalidation infrastructure is introduced.
The future package source will enter the existing registry/graph/runtime pipeline;
state remains keyed by descriptor ID independently of its source.

## Java integration and bootstrap limit

The public contract is `ErbasModule.descriptor()` plus synchronous
`initialize()`. `ModuleDescriptor` is a Java record with `String id`,
`int moduleApiVersion` and `List<String> dependencies`; its constructor copies
and freezes dependencies. `ModuleApi.CURRENT_VERSION` is the supported version.
`ModuleSource.modules()` supplies modules without requiring Spring beans.

`SpringModuleSource` is today's built-in source and snapshots the registered
`ErbasModule` beans. `ModularityConfiguration` gathers all `ModuleSource` beans,
creates the registry, orders its modules and creates `ModuleRuntime`. The registry
and graph have no Spring dependencies and accept sources constructed outside
Spring as well. A new source can supply non-bean module instances.

`ModuleStateStore` is the framework-independent persistence port; its update
callback operates on a transactionally locked snapshot. `JdbcModuleStateStore`
implements it with the existing JDBC transaction manager, without JPA or a new
database. Flyway `V4__module_states.sql` creates the table after the existing
auth/user migrations, for both fresh and already initialized installations.

`ModuleStateConfiguration` supplies the store and service in the normal application.
`SmartInitializingSingleton` synchronizes state, then performs initialization after regular singletons are
constructed and before context refresh completes. A startup failure prevents a
successful refresh. Modules can receive normal constructor-injected services;
no application context or service locator is passed to their public contract.
`ModuleRuntime` defensively copies its ordered list and rejects a second
initialization attempt, including after failure. Startup is single-threaded.
Java registers the production `WmsModule` bean through `SpringModuleSource`.
Its stable `erbas.wms` descriptor uses `ModuleApi.CURRENT_VERSION` and no
dependencies. `UomController` reuses that ID in `@ModuleEndpoint`; the ordinary
state synchronization, dependency graph and runtime apply without WMS special cases.
WMS owns its domain, application port, JDBC adapter and V5 catalogue migration.
WMS contributes a native Spring Security `Customizer` applying the existing ADMIN
authority before the MVC gate. CORE consumes these framework customizers before
its authenticated fallback and does not import WMS types or know WMS routes.
No CORE domain or runtime class owns warehouse concepts. See
[WMS-001J](verification/wms-001-java.md) for the UoM boundary and evidence.

A future external Java plugin contributing Spring services/controllers must be
discovered early enough to register its configuration before the application
context is refreshed. The current bean source alone does not solve that bootstrap
requirement. No external loader, classloader API, dynamic bean registration or
manual classpath scanner is implemented.

### Java HTTP adapter

`com.alxarafe.erbas.modularity.http.ModuleEndpoint` is a runtime, class-level
annotation for an entire MVC controller. Reuse the module's named `ID` constant
in both its descriptor and `@ModuleEndpoint(ID)`; never derive identity from a
controller class, package or route. The platform registers `ModuleEndpointInterceptor`
through a `WebMvcConfigurer` bean. Spring Security's filter chain runs before MVC;
the interceptor resolves the controller annotation and calls only `requireEnabled`.
Successful checks are retained only in the current servlet request so an async
MVC redispatch does not repeat the boundary check; the next request reads current
state again.
Only `ModuleDisabledException` is translated to the disabled HTTP response.
Unannotated CORE controllers and non-controller handlers pass through unchanged.
No production fake module/controller or new OpenAPI infrastructure is added.

## Verification

The existing graph/runtime tests are adapted to descriptors. Registry tests cover
supported/incompatible versions, empty/single/multiple sources, duplicates,
cross-source dependencies, case-sensitive IDs and immutable snapshots. Small
Spring contexts verify ordered startup, failure, zero modules and an additional
source whose module is not a Spring bean. PostgreSQL-backed `ModuleStateIT`
covers defaults, retained/disabled state, transitions, dependency chains, startup
ordering and failure, rollback, case-sensitive IDs, preservation of unrelated data,
V3-to-V4 migration and competing enable/disable transactions waiting on real
database locks. `ModuleGatingIT` adds a test-only descriptor/module and annotated
controllers to the real application, uses real HTTP on an ephemeral port and the
JDBC/PostgreSQL store, and covers dynamic disable/re-enable, both controller routes,
401 before the gate, exact 503 headers/body, initialization/migration preservation
and unaffected Health/Actuator/login/auth/users. Negative checks confirm internal
MVC exceptions and HTTP responses are not converted to `module_disabled`.
Focused interceptor tests cover one check across async redispatch and zero checks
for CORE/non-controller handlers.
The complete Docker validation is:

```bash
ERBAS_CONTRACT_DIR=/absolute/path/to/pinned-erbas-contract ./bin/check
```

`ModuleControllerTests` covers the closed HTTP boundary, case-sensitive ordering
before pagination, fresh enabled reads, 401/403 precedence, exact errors and
unexpected failures. Composition tests cover disabled/default/production contexts
and explicit canonical registration. PostgreSQL-backed `ModuleAdministrationIT`
verifies real HTTP security, defaults, historical-row exclusion, idempotence,
both dependency conflicts without partial persisted changes, normal synchronization
and CORE recovery when all modules are disabled. `ModuleStateIT` also proves that
missing dependency state remains an internal failure. See
[MODULES-001E-JAVA evidence](verification/modules-001e-java.md).
