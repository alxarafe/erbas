# Documentation

| Document | Purpose |
| --- | --- |
| [Usage](usage.md) | Docker build, development, isolated validation and diagnostics |
| [Integration ADR](decisions/0001-java-health-conformance.md) | Approved boundaries and choices |
| [CONTRACT-001B verification](verification/contract-001b.md) | Actual results, isolation and cleanup evidence |
| [AUTH-003 closure](verification/auth-003.md) | Complete persistence, login/security and operational conformance evidence |
| [USERS-001 foundation](verification/users-001-foundation.md) | Task 2A identity, admin bootstrap, atomic state changes and deferred HTTP boundary |
| [AUTH-003 persistence verification](verification/auth-003-persistence.md) | Historical task 1 schema, JDBC decisions and isolated tests |
| [AUTH-003 login verification](verification/auth-003-login.md) | Historical task 2 login and Spring Security verification |
| [Working agreement](../AGENTS.md) | Persistent repository rules |
| [Contract revision](../contract.revision) | Exact unpublished commit |

Workflows run in their responsible repositories. Their badges can be displayed
elsewhere without duplicating tests. General CI, the declared contractual revision
and a conformance result are distinct. CONTRACT-002 will connect complete Java
validation to CI; the contract repository will progressively record compatibility.
