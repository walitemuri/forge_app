# Forge dashboard

Next.js frontend and a restricted template-launch API for the Forge Java/C++
workflow engine. The catalog is defined in `src/lib/workflow-templates.ts`;
server-side command builders are in `src/lib/template-workflows.ts`.

Start the complete system from the repository root with `docker compose up -d
--build`, then open http://localhost:3000/templates.

For frontend development:

```bash
npm ci
npm run dev
```

`FORGE_API_URL` defaults to `http://127.0.0.1:8080`. Docker configures the internal
controller address automatically. Local video artifacts are served by the Docker
dashboard, which mounts the shared artifact volume; a host-only dev server has
no access to that volume unless you explicitly mount it.

```bash
npm test
npm run lint
npm run build
```

Tests cover the full catalog, DAG structure, fixed command admission, public
budgets, concurrent launch protection, and private chaos routes. Run
`node scripts/dashboard_templates_smoke_test.mjs` from the repository root for
real worker execution against the local stack.

For public HTTPS hosting, resource bounds, operating instructions, and expected
template outcomes, see [Deployment](../docs/deployment.md). The complete demo
requires a persistent Docker host; a standalone frontend deployment cannot run
the Java/C++ workers or serve their shared artifacts.
