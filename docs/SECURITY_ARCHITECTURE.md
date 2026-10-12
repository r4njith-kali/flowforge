# FlowForge Security Architecture

## Status and scope

Milestone 7A adds OIDC authentication, JWT resource-server validation,
role-based API authorization, and per-user job ownership. This is a
single-realm, single-tenant user access model; it is **not** full tenant
isolation. Local end-to-end identity-provider checks passed; production
deployment and environment-specific security acceptance are still required
before treating this as production-ready.

## Authentication

- Keycloak is the local OpenID Connect provider. It issues access tokens using
  its realm-managed asymmetric signing keys; FlowForge does not hold signing
  keys or implement JWT cryptography.
- The browser is a public OIDC client using Authorization Code with PKCE
  (`S256`). It has no client secret and does not use the password grant or
  implicit flow.
- The control plane is a stateless Spring Security OAuth2 Resource Server.
  Nimbus validates the JWT signature against the configured JWKS, exact issuer,
  expiration/not-before timestamps, the required `flowforge-api` audience,
  and a non-empty subject claim before the request reaches a controller.
- Only the `ADMIN`, `OPERATOR`, and `VIEWER` Keycloak realm roles map to Spring
  authorities. Unknown, malformed, and client-specific role claims do not
  grant privileges.
- Authentication failures return HTTP 401 and authorization failures HTTP
  403 as generic RFC Problem Details; token contents and validation internals
  are not returned to callers.

`FLOWFORGE_OIDC_ISSUER_URI`, `FLOWFORGE_OIDC_JWK_SET_URI`, and
`FLOWFORGE_OIDC_AUDIENCE` are environment configurable. Issuer and JWKS URLs
must be trusted and HTTPS in deployed environments. Local HTTP is only for
loopback development.

## Authorization and ownership

| Role | List/read jobs | Submit jobs | Cancel jobs | `/api/v1/admin/**` |
| --- | --- | --- | --- | --- |
| ADMIN | All jobs, including historical unowned rows | Yes | Any job | Yes |
| OPERATOR | Own jobs | Yes | Own jobs | No |
| VIEWER | Own jobs | No | No | No |

There are no administrative API operations under `/api/v1/admin/**` yet; the
route namespace is reserved and protected as ADMIN-only. Every current job
operation is protected both by URL authorization and method-level role checks.

The control plane derives the stable owner key from the validated JWT `(iss,
sub)` claims. It persists these as `owner_issuer` and `owner_subject` in the
additive Flyway V5 migration. Listing applies owner predicates before database
pagination and filtering. Detail and cancellation return 404 for a job owned
by someone else to avoid confirming its existence. ADMIN can access every
record. Legacy rows are retained with null ownership and are visible only to
ADMIN until an explicit migration/backfill policy is approved.

Idempotency keys are scoped to the operation and validated `(iss, sub)`;
different users can independently use the same key. The existing 24-hour
retention period is unchanged. Ownership is a user-level boundary, not a
tenant boundary. No tenant claim, tenant column, cross-tenant administration
model, or tenant-isolation test exists; do not describe this release as
multi-tenant.

Workers continue consuming Kafka and claiming jobs from PostgreSQL without
browser credentials. Ownership does not control dispatch or execution.

## Browser, API, and CSRF model

The React SPA uses a public OIDC client and Authorization Code + PKCE. The
`oidc-client-ts` user store and OIDC state store use `sessionStorage`, never
`localStorage`; logout removes the OIDC user and token expiry/silent-renew
failure returns the UI to its unauthenticated state. The in-memory API token
provider attaches `Authorization: Bearer` and fetch explicitly omits cookies.

The API is stateless and does not authenticate with ambient cookies. Spring's
CSRF protection is ignored only for `/api/**`, because browser API requests are
authenticated with an explicit bearer header rather than an automatically
sent cookie. CSRF remains enabled elsewhere. This assumption must be revisited
if cookie-based authentication or state-changing browser endpoints are added.

CORS allows one configured dashboard origin, selected methods and explicit
headers, and does not allow credentials. Security headers include a restrictive
content security policy, frame denial, `nosniff`, referrer policy, and a
permissions policy. Actuator exposes health only; liveness/readiness health
routes are public and other management paths require authentication. OpenAPI
and Swagger UI are authenticated and document bearer JWT authorization.

Session storage is more resistant to persistence across browser restarts than
local storage, but it remains accessible to same-origin JavaScript. An XSS
vulnerability could steal the current access or refresh token. The CSP and
dependency hygiene reduce risk but do not eliminate it. A production
confidential BFF with HttpOnly secure cookies may be preferable where the
deployment model supports it.

## Threat considerations and limitations

- Configure HTTPS, trusted issuer/JWKS URLs, restrictive network access, and
  managed secrets before deployment. Keycloak's local admin password belongs
  only in an ignored `.env`; no signing private key is committed.
- Keycloak import defines local role assignments but no passwords. Developers
  must assign local-only passwords in the admin console. The web client's
  explicit `oidc-sub-mapper` places Keycloak's stable user ID in the access
  token's `sub` claim; owner scoping must not fall back to a mutable username.
- Role claims are authorization input only after Spring has validated the
  signed token. Unverified token payloads are never parsed for identity or
  permissions.
- A stolen valid bearer token can be replayed until expiry/revocation. Configure
  short access-token lifetimes and appropriate refresh-token/session policies
  in deployed identity providers.
- User ownership does not isolate database access, Kafka topics, metrics, or
  administrators by tenant. Tenant data partitioning and tenant-scoped
  authorization remain future work.
- The API has no rate limiting, account lifecycle integration, tenant-level
  quotas, or audit trail for access decisions yet.
- Local Docker Compose, PKCE login, authenticated API access, operator job
  execution, viewer restrictions, and unauthenticated rejection have been
  verified. `npm audit` reported no known frontend vulnerabilities at the time
  of verification. A separate Maven dependency vulnerability scan has not been
  run; deployed identity-provider, HTTPS, secret-management, and infrastructure
  hardening remain production acceptance work.

## Security test coverage

The control-plane security tests cover missing/malformed bearer tokens,
Problem Details responses, role restrictions, public health routing, realm
role allowlisting, and JWT signature, issuer, audience, and expiration
validation using locally generated RSA keys and a local JWKS endpoint. The
JWT tests also reject absent and blank subject claims. The
PostgreSQL integration suite includes per-user list/detail/cancel filtering,
cross-owner idempotency-key isolation, and administrator access; those
Testcontainers cases require Docker.

Run targeted tests with:

```bash
mvn -B -ntp -pl services/control-plane \
  -Dtest=SecurityAccessTest,JwtDecoderValidationTest,KeycloakRoleConverterTest,JobServiceTest test
```

Run the full security/integration suite with Docker available:

```bash
mvn -B -ntp clean verify
```

Frontend auth and API error tests run with `cd apps/web && npm test`; use
`npm run lint`, `npm run typecheck`, and `npm run build` for the other checks.
