# ADR 0002: physical CORE, WMS and host boundaries

Status: **Approved** by JAVA-ARCH-001. This refactors the Java implementation at
`4af73d9bf49294f0668ab0849049c2eb5a47c699` without changing published contract
`v0.6.0` (`92d1282154f340aadcfdf93f52a82a1f3472b335`).

## Decision

Use the root Maven parent/aggregator and exactly three JAR artifacts:
`erbas-core`, `erbas-wms` and executable `erbas-host`. Dependencies are
host → CORE, host → WMS and WMS → CORE. CORE has no WMS dependency. Preserve Java
packages and move source/resource roots rather than rename business namespaces.
Only the host applies Spring Boot's executable repackage plugin. Docker copies
that host JAR into the same pinned, non-root runtime image; the application
remains one process and one HTTP API.

The host explicitly imports `CoreConfiguration` and WMS's `WmsConfiguration`.
CORE scans its own platform packages; WMS imports its adapters and registers its
own beans and authorization customizer. The existing native Spring extension
mechanism stays in place, using `PlatformAuthorities.ADMINISTRATOR` instead of
requiring WMS to import an authentication implementation to obtain `ADMIN`.
The module source/registry/graph/runtime pipeline is unchanged. Domain/application
code remains independent of Spring/JDBC/Flyway/HTTP adapters. No dynamic loading,
plugin directory, new permission model or separate deployable service is added.

## Migration ownership and compatibility

Previously all SQL lived in the monolith's `classpath:db/migration`. Now:

- CORE packages V1–V4 under `db/core` in its own JAR.
- WMS packages historical `V5__wms_uoms.sql` under `db/wms` in its own JAR.
- The host explicitly configures both locations and retains the existing
  `flyway_schema_history` table in the same PostgreSQL schema.

All five SQL files remain byte-identical, including names, versions and
checksums. This deliberately uses Flyway's existing migration resolution and
validation rather than a custom lifecycle or history conversion. Fresh composed
installations apply V1–V5. Existing V1–V5 installations resolve the same migration
identities from the two owned locations, validate normally and execute no SQL
again. There is no automatic repair, clean, reset, history rewriting, baseline
or disabled validation.

[Spring Boot supports explicit Flyway locations](https://docs.spring.io/spring-boot/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway).
[Flyway versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
retain applied versions and checksums in history. Versions must remain unique
across the composed locations. Future functional WMS SQL belongs under WMS's
`db/wms`, never CORE's migration directory. Independent per-module histories or
an arbitrary migration framework are unnecessary for the installed WMS module.

Disabling WMS keeps its artifact, schema and data installed. Migration does not
depend on the enabled flag. Removing the artifact from an existing installation
is not supported as an uninstall workflow. A fresh CORE-only installation reads
only `db/core` and creates no WMS business tables.

## Verification

`ERBAS_CONTRACT_DIR=<clean-published-v0.6.0-checkout> ./bin/check` remains the sole
complete Docker validation authority. It verifies:

- CORE-only compilation/native tests and then the whole Maven reactor;
- dependency, source, host-composition, hexagonal and resource ownership guards;
- the existing real PostgreSQL integration coverage plus CORE-only migration;
- actual host startup against empty schemas and exact-base V1–V5 SQL fixtures;
- unchanged upgrade history/checksums, unchanged table OID, authentication users
  and token records, infrastructure marker and existing UoM identity/content;
- retained disabled WMS state, successful existing-account HTTP login, gated
  access and retrieval of preserved UoMs after re-enabling;
- plain library JARs and a single executable host JAR with no test fixtures;
- the runtime Docker image, health checks and published shared Bruno conformance.

Legacy SQL exists only as test fixtures, protected by pinned base SHA-256 guards.
Compatibility tests use owned schemas exclusively inside the disposable native
PostgreSQL instance and remove only those schemas. Existing validation resource
ownership, bounded waits, failure propagation, secret checks and cleanup remain
in force. Historical verification reports remain unchanged.
