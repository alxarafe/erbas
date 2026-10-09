# Documentation

| Document | Purpose |
| --- | --- |
| [Usage](usage.md) | Docker build, development, isolated validation and diagnostics |
| [Integration ADR](decisions/0001-java-health-conformance.md) | Approved boundaries and choices |
| [CONTRACT-001B verification](verification/contract-001b.md) | Actual results, isolation and cleanup evidence |
| [AUTH-003 persistence verification](verification/auth-003-persistence.md) | Task 1 schema, JDBC decisions and isolated tests; login pending |
| [Working agreement](../AGENTS.md) | Persistent repository rules |
| [Contract revision](../contract.revision) | Exact unpublished commit |

Workflows run in their responsible repositories. Their badges can be displayed
elsewhere without duplicating tests. General CI, the declared contractual revision
and a conformance result are distinct. CONTRACT-002 will connect complete Java
validation to CI; the contract repository will progressively record compatibility.
