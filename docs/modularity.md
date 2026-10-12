# Modular runtime foundation

The `com.alxarafe.erbas.modularity` package provides a minimal, in-process modular
runtime. Its conceptual antecedent is Alxarafe.NET's `IAlxarafeModule`,
`ModuleGraph` and `ModuleRuntime`: identity, explicit dependencies, graph
validation, dependency ordering and sequential initialization. Java uses Spring
bean construction rather than assembly discovery or service-provider APIs.

## Module definition and ordering

`ErbasModule` exposes `String id()`, `List<String> dependencies()` and
`void initialize()`. IDs and dependency metadata must be non-null and stable;
dependencies identify other registered modules. Modules can use normal Spring
constructor injection without receiving an application context or bean factory.

`ModuleGraph.order` rejects duplicate IDs and missing dependencies before
traversing the graph. Depth-first traversal detects direct and indirect cycles,
visits each module once and places dependencies before their consumers. Both
root IDs and dependency IDs use Java's natural, case-sensitive `String` order,
independent of bean registration order. This is deterministic topological order,
not a promise that the entire resulting list is lexicographically sorted.
The returned list is immutable. Configuration errors use
`IllegalArgumentException` and identify the duplicate ID, the consumer and absent
dependency, or the ID where a cycle was detected.

## Runtime and Spring startup

`ModuleRuntime` defensively copies an already ordered list and exposes it through
the immutable `modules()` view. `initialize()` calls modules sequentially in that
order. A module failure propagates unchanged and stops all subsequent calls;
there is no rollback or retry. A second initialization attempt on the same runtime
throws `IllegalStateException`, including after an unsuccessful first attempt.
The runtime is intended for single-threaded startup, not concurrent invocation.

`ModularityConfiguration` receives the registered `ErbasModule` beans from
Spring, resolves their graph and creates the runtime. Its
`SmartInitializingSingleton` bean initializes that runtime after regular
singletons have been constructed, before context refresh completes. An
initialization failure prevents successful startup. This uses Spring's standard
[singleton initialization callback](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/SmartInitializingSingleton.html),
not an application-ready event. Future modules should be singleton beans;
module dependency ordering governs initialization, not bean construction.

There are no production modules yet. Zero module beans produce a valid, empty
runtime. AUTH, USERS and Health retain their existing implementation and HTTP
behavior; the database and shared contract are unchanged.

## Boundaries and verification

Enabled/disabled management, persistence, HTTP management,
installation/uninstallation, dynamic loading, business modules and a module
endpoint abstraction are not implemented. Java does not imitate .NET's
`IServiceCollection`, assembly discovery, `IHttpModule`, cancellation tokens or
asynchronous initialization internals.

Focused graph and runtime tests cover ordering, configuration errors,
immutability, single initialization and failure propagation. Small Spring
contexts verify constructor injection, ordered startup, startup failure and zero
modules without PostgreSQL. Run the complete Docker-only validation with a clean
checkout of the pinned published contract:

```bash
ERBAS_CONTRACT_DIR=/absolute/path/to/pinned-erbas-contract ./bin/check
```
