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
initialization remains technical, and functional activation is a future layer.

The CORE is not a disableable functional module. Basic Health, essential
authentication, the modular runtime and essential system administration remain
outside functional module activation. AUTH/USERS behavior is unchanged.

Modules must not access another module's internal tables or private repositories.
Future integration must use public contracts/capabilities of the owning module,
including future Inventory and AI integrations. No bus or capability registry is
introduced without a real consumer.

## Approved future lifecycle, not implemented

Enabled/disabled will be persistent and dynamic, without restart. Disabling must
never delete data. Routes and services may remain registered while runtime checks
block functionality. Jobs and event consumers must respect the same state;
dependencies must prevent invalid activation combinations. No activation state,
storage, HTTP administration or activation hooks exist yet.

Installation will mean physical incorporation of a module package; uninstallation
will mean its physical removal. Both may initially require restart. DISABLE is
not UNINSTALL, and UNINSTALL does not imply deleting data. PURGE, if ever provided,
will be explicit and independent. None of these operations is implemented.

External directory discovery, JAR/DLL loading, hot loading/unloading, installers,
state persistence, module management endpoints and frontend are out of scope.
The future package source will enter the existing registry/graph/runtime pipeline;
its loader and package distribution details require later implementation.

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

`SmartInitializingSingleton` performs initialization after regular singletons are
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
source whose module is not a Spring bean. The complete Docker validation is:

```bash
ERBAS_CONTRACT_DIR=/absolute/path/to/pinned-erbas-contract ./bin/check
```
