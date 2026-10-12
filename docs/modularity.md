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
platform/CORE, never to Catalog, Security or another business module.

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
initialize, including disabled ones. `requireEnabled` provides an internal guard
with a clear exception; no HTTP status mapping, middleware or management API is
introduced. Existing endpoints remain unchanged. Future HTTP, jobs, event consumers
and inter-module capabilities must consult this same authority instead of treating
INITIALIZED as permission to execute functional work.

## Future lifecycle, not implemented

Installation will mean physical incorporation of a module package; uninstallation
will mean its physical removal. Both may initially require restart. DISABLE is
not UNINSTALL, and UNINSTALL does not imply deleting data. PURGE, if ever provided,
will be explicit and independent. None of these operations is implemented.

External directory discovery, JAR/DLL loading, hot loading/unloading, installers,
module management endpoints and frontend remain out of scope. No start/stop,
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
Java currently has zero production modules and continues to start normally.

A future external Java plugin contributing Spring services/controllers must be
discovered early enough to register its configuration before the application
context is refreshed. The current bean source alone does not solve that bootstrap
requirement. No external loader, classloader API, dynamic bean registration or
manual classpath scanner is implemented.

## Verification

The existing graph/runtime tests are adapted to descriptors. Registry tests cover
supported/incompatible versions, empty/single/multiple sources, duplicates,
cross-source dependencies, case-sensitive IDs and immutable snapshots. Small
Spring contexts verify ordered startup, failure, zero modules and an additional
source whose module is not a Spring bean. PostgreSQL-backed `ModuleStateIT`
covers defaults, retained/disabled state, transitions, dependency chains, startup
ordering and failure, rollback, case-sensitive IDs, preservation of unrelated data,
V3-to-V4 migration and competing enable/disable transactions waiting on real
database locks. The complete Docker validation is:

```bash
ERBAS_CONTRACT_DIR=/absolute/path/to/pinned-erbas-contract ./bin/check
```
