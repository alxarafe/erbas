# Documentation

| Document | Purpose |
| --- | --- |
| [Usage](usage.md) | Docker build, development, isolated validation and diagnostics |
| [Modularity](modularity.md) | Module identity, dependency ordering, sequential initialization and Spring startup boundaries |
| [API internationalization](i18n.md) | Native locale negotiation, module-owned bundles and Jakarta Validation without changing v0.5.0 errors |
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
| [Contract revision](../contract.revision) | Implemented published release v0.5.0 |

Workflows run in their responsible repositories. Their badges can be displayed
elsewhere without duplicating tests. Workflow status, the declared contractual
release and a conformance result are distinct. Java CI invokes the complete
`./bin/check`, including mandatory shared conformance against `v0.5.0`;
see [validation and CI](usage.md#complete-validation). Verification reports
retain their historical source revisions and results.
