# Local Authentication Setup

## Prerequisites

- Docker Compose v2
- JDK 21 (or a compatible JDK; the current verification host uses JDK 25)
- Maven 3.9+
- Node.js 20.19+ or 22.12+ and npm

## Start the local identity provider and dependencies

From the repository root:

```bash
cp -n .env.example .env
```

If `.env` already exists, merge the newly required Keycloak settings from
`.env.example` rather than overwriting existing database or broker settings.
Edit `.env` and replace `POSTGRES_PASSWORD` and
`KEYCLOAK_ADMIN_PASSWORD` with local development passwords. Do not commit or
share `.env`. The realm import contains no passwords or private signing keys.

```bash
docker compose --env-file .env -f infra/docker/compose.yaml \
  up -d postgres kafka keycloak
```

The Keycloak container binds to `127.0.0.1:8180` and imports
`infra/docker/keycloak/realm/flowforge-realm.json`. It creates:

- Realm: `flowforge`
- Public OIDC SPA client: `flowforge-web` (authorization code flow, PKCE S256)
- API audience: `flowforge-api`
- Realm roles: `ADMIN`, `OPERATOR`, `VIEWER`
- Local users: `flowforge-admin`, `flowforge-operator`, `flowforge-viewer`

The browser client's access-token mappers explicitly include the stable
Keycloak user ID as the standard `sub` claim, realm roles, and the
`flowforge-api` audience. The control plane rejects tokens without a non-empty
subject rather than substituting a username.

Confirm that realm metadata is available:

```bash
curl http://localhost:8180/realms/flowforge/.well-known/openid-configuration
```

Open `http://localhost:8180/admin`, sign in with the local admin values from
`.env`, select the `flowforge` realm, and set a local password for each user
under **Users → Credentials**. Do not use these local credentials outside
development. The imported role assignments are already applied.

## Run the control plane, workers, and dashboard

In the control-plane terminal, source the ignored environment file before
starting Spring Boot so the database and issuer settings are available:

```bash
set -a
source .env
set +a
mvn -pl services/control-plane spring-boot:run
```

Wait for Flyway migrations to complete and verify
`http://localhost:8080/actuator/health`. Only health is public. The job API,
OpenAPI JSON, and Swagger UI require a valid access token.

Start workers only after the control plane has migrated the database:

```bash
docker compose --env-file .env -f infra/docker/compose.yaml \
  up --build -d --scale worker=2 worker
```

In another terminal, start the dashboard:

```bash
cd apps/web
npm install
npm run dev
```

Visit `http://localhost:5173`, sign in using the operator account, and submit a
job. A VIEWER can list/open their own jobs, but the UI hides creation and
cancellation controls; direct requests are still rejected by the API. To test
ADMIN access, sign in with the admin account. Sign out through the dashboard
to clear its OIDC session.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/flowforge` | PostgreSQL connection URL |
| `DB_USERNAME` | Required | PostgreSQL username for both Spring services |
| `DB_PASSWORD` | Required | PostgreSQL password for both Spring services |
| `FLOWFORGE_OIDC_ISSUER_URI` | `http://localhost:8180/realms/flowforge` | Exact expected token issuer |
| `FLOWFORGE_OIDC_JWK_SET_URI` | `http://localhost:8180/realms/flowforge/protocol/openid-connect/certs` | Trusted signing-key endpoint |
| `FLOWFORGE_OIDC_AUDIENCE` | `flowforge-api` | Required JWT audience |
| `FLOWFORGE_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | Single allowed browser origin |
| `VITE_OIDC_AUTHORITY` | `http://localhost:8180/realms/flowforge` | Browser's OIDC authority |
| `VITE_OIDC_CLIENT_ID` | `flowforge-web` | Public browser client |

Both backend services require `DB_USERNAME` and `DB_PASSWORD`; they do not
fall back to empty credentials. Missing variables prevent application
configuration from resolving at startup. The local `.env.example` supplies
development placeholders; replace them with local-only values before running
the services.
Keep issuer and audience values aligned with the realm/client configuration.
Deployed environments must use HTTPS endpoints, protected secret storage, and
their own redirect URIs and CORS origin.

## Reproducible verification

Focused API and JWT tests:

```bash
mvn -B -ntp -pl services/control-plane \
  -Dtest=SecurityAccessTest,JwtDecoderValidationTest,KeycloakRoleConverterTest,JobServiceTest test
```

Frontend tests and static checks:

```bash
cd apps/web
npm run lint
npm run typecheck
npm test
npm run build
```

The PostgreSQL ownership and persistence tests use Testcontainers and require
a running Docker daemon. For the full stack, follow the preceding login and
submission steps and confirm the job reaches `COMPLETED`; do not infer a
successful identity-provider or worker check solely from compilation.

## Stop local services

```bash
docker compose --env-file .env -f infra/docker/compose.yaml down
```

Use `down -v` only when you intentionally want to erase the local PostgreSQL
volume and all development data.
