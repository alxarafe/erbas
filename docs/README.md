# Documentation

| Document | Purpose |
| --- | --- |
| [Capabilities](capabilities.md) | CORE-CAP-001 effective discovery, module-owned declarations and published v0.9.0 adoption; no execution |
| [CLEANUP-001 verification](verification/cleanup-001.md) | Empty normal composition, canonical validation modules and regression evidence |
| [WMS-003J verification](verification/wms-003-java.md) | Item Families, atomic N:M associations and v0.8.0 error-envelope adoption |
| [WMS-002J verification](verification/wms-002-java.md) | Storage Attributes, v0.7.0 adoption and PostgreSQL/HTTP validation evidence |
| [WMS-001J verification](verification/wms-001-java.md) | Production WMS UoM, v0.6.0 adoption and validation evidence |
| [Usage](usage.md) | Docker build, development, isolated validation and diagnostics |
| [Modularity](modularity.md) | Physical CORE/WMS/host boundaries, module identity, dependency ordering and Spring composition |
| [Physical module ADR](decisions/0002-physical-module-boundaries.md) | Maven ownership, explicit composition and existing-V5 migration compatibility |
| [API internationalization](i18n.md) | Native locale negotiation, module-owned bundles and Jakarta Validation with closed technical contract errors |
| [I18N-001 verification](verification/i18n-001.md) | HTTP locale/validation tests, artifact boundary, shared conformance and cleanup evidence |
| [MODULES-001E-JAVA verification](verification/modules-001e-java.md) | Published v0.5.0 adoption, CORE module administration, explicit conformance composition and validation evidence |
| [Integration ADR](decisions/0001-java-health-conformance.md) | Approved boundaries and choices |
| [CONTRACT-001B verification](verification/contract-001b.md) | Actual results, isolation and cleanup evidence |
| [AUTH-003 closure](verification/auth-003.md) | Complete persistence, login/security and operational conformance evidence |
| [COLLECTIONS-001 verification](verification/collections-001.md) | Paged user responses and full draft 0.4.0 shared conformance |
| [USERS-001 verification](verification/users-001.md) | Historical draft 0.3.0 HTTP API, authorization, Unicode passwords and shared conformance |
| [USERS-001 foundation](verification/users-001-foundation.md) | Historical Task 2A identity, admin bootstrap, atomic state changes and deferred HTTP boundary |
| [AUTH-003 persistence verification](verification/auth-003-persistence.md) | Historical task 1 schema, JDBC decisions and isolated tests |
| [AUTH-003 login verification](verification/auth-003-login.md) | Historical task 2 login and Spring Security verification |
| [Working agreement](../AGENTS.md) | Persistent repository rules |
| [Contract revision](../contract.revision) | Implemented published release v0.9.0 |

Workflows run in their responsible repositories. Their badges can be displayed
elsewhere without duplicating tests. Workflow status, the declared contractual
release and a conformance result are distinct. Java CI invokes the complete
`./bin/check`, including mandatory shared conformance against `v0.9.0`;
see [validation and CI](usage.md#complete-validation). Verification reports
retain their historical source revisions and results.
