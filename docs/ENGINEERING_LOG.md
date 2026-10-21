# Engineering Log

## 2026-10-21 — FLOW-103: Require database credentials at startup

- **Decision:** Validate the effective Spring datasource username and password
  during bean-factory post-processing in both services.
- **Rationale:** Missing or blank values should produce an actionable startup
  error before Flyway or the worker can attempt a database connection. Keep the
  check at each service boundary rather than adding a shared Maven module.
- **Verification:** Added context tests for missing username, blank password,
  unresolved placeholders, and valid credentials in both services. Full
  Maven/Testcontainers verification passed all 85 tests with the documented
  local Docker API compatibility override.
- **Tracking:** GitHub issue #2; pull request #3.
