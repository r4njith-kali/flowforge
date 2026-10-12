# FlowForge web dashboard

The React/TypeScript operator dashboard is built with Vite and Tailwind CSS.
It uses the control-plane REST API through Vite's development proxy; it does
not connect directly to PostgreSQL, Kafka, or worker services.

From this directory:

```bash
npm ci
cp .env.example .env.local
npm run dev
```

Set `VITE_API_PROXY_TARGET` in `.env.local` if the control plane is not at
`http://localhost:8080`. `VITE_REFRESH_INTERVAL_MS` accepts `5000`, `15000`, or
`30000`; the dashboard also exposes the interval selector in its header.

Run verification:

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

See the repository [README](../../README.md) for local backend setup,
API-contract notes, and screenshots.
