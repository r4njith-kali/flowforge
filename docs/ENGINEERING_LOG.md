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

## 2026-10-21 — FLOW-104: Document frontend OIDC overrides

- **Decision:** Add the authority URL and public client ID to the frontend
  environment example, using the same values as the existing development
  defaults.
- **Rationale:** Make the already-supported OIDC overrides discoverable
  without changing authentication behavior or adding secrets.
- **Verification:** Confirmed both values match `apps/web/src/auth/oidc.ts`.
  This example-only change does not require running the application tests.
- **Tracking:** GitHub issue #4.

## 2026-10-22 — FLOW-105: Expose job results and attempt history

- **Decision:** Return persisted job result/error only from the detail route
  and add an owner-authorized endpoint for execution attempts in attempt-number
  order. Keep list and create response shapes unchanged.
- **Rationale:** Workers already persist these outcomes; exposing them through
  the existing ownership boundary closes a key operator visibility gap.
- **Verification:** Focused control-plane PostgreSQL integration and service
  tests passed (31 tests). Full Maven/Testcontainers verification passed all
  90 tests (73 control-plane, 17 worker) with the documented local Docker API
  compatibility override.
- **Tracking:** GitHub issue #6.

## 2026-10-22 — FLOW-106: Render execution outcomes in the dashboard

- **Decision:** Extend the job detail client to load typed outcome and attempt
  data from FLOW-105, rendering results as text-formatted JSON with independent
  attempt loading/error/empty states.
- **Rationale:** Make worker execution evidence useful to operators without
  fabricating lifecycle events or making job controls depend on history reads.
- **Verification:** `npm run lint`, `npm run typecheck`, `npm test` (22 tests),
  and `npm run build` all passed in `apps/web/`.
- **Tracking:** GitHub issue #8.
