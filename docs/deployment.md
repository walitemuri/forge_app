# Public recruiter demo

The public deployment runs the complete system on one Linux Docker host: Caddy,
Next.js, the Java controller, PostgreSQL, and three C++ workers. Visitors can
launch ten fixed workflows, inspect DAGs and attempts, follow worker telemetry,
and play or download a generated video. Workflows and artifacts are shared
between visitors; this is a portfolio demonstration, not a multi-tenant runner.

## Run locally first

```bash
docker compose up -d --build
```

Open http://localhost:3000/templates. The first build downloads compiler and Java
dependencies. Worker images contain the verification source, a warmed Gradle
cache, and a compact 30-second excerpt of real Tears of Steel footage with audio.
The excerpt is from the Blender Foundation's CC BY 3.0 film; attribution is
shown in the dashboard. No full film download, host source mount, or host compiler is required. The build-verification
workflow compiles in disposable directories, runs Java unit tests and C++ tests,
and checks Python syntax. Java's database-dependent application context test is
excluded from that workflow; it is not a full database integration test.

The normal Compose file binds the dashboard and controller ports to loopback.
PostgreSQL stays inside Docker. Existing named volumes are retained on rebuild.

```bash
npm --prefix dashboard ci
npm --prefix dashboard test
npm --prefix dashboard run lint
node scripts/dashboard_templates_smoke_test.mjs
```

The smoke test launches all ten workflows on the **local** configuration. It
checks exact final task states, skipped tasks having no attempts, retry counts,
and MP4 download/range responses. It deliberately bypasses the public launch
budgets by using local mode. Do not run the complete batch on the public host.

## Publish on your server

Use Docker Compose 2.24 or newer. The public overlay uses `!reset` to remove
all backend port publications. Plan memory for the configured container ceilings
(about 8 GiB in total), the operating system, and image builds. Compile workloads
and three workers share the host CPU; this demonstrates distribution across
worker processes, not across three physical servers.

### Oracle Cloud Free Tier and GitHub Actions

Use an Always Free `VM.Standard.A1.Flex` instance with the full free allocation
(currently 2 OCPUs and 12 GB RAM), Ubuntu 24.04, a public IPv4 address, and at
least a 50 GB boot volume. The 1 GB `VM.Standard.E2.1.Micro` shape cannot run
this stack. In the OCI subnet security list or network security group, allow
inbound TCP 80 and 443 from the internet. Keep 3000, 8080, 50051, and 5432
closed. A GitHub-hosted runner also needs to reach TCP 22. GitHub's hosted-runner
addresses change, so the simple setup exposes port 22 while enforcing key-only
SSH; a static-IP or self-hosted runner lets you restrict that rule further.

After creating the VM and pointing the domain's A record at its public IP, copy
`deploy/bootstrap-oracle-ubuntu.sh` to the VM and run it as the normal `ubuntu`
user. The script installs Docker from Docker's official Ubuntu repository,
clones this repository into `/opt/forge`, and creates a protected `.env` template.
Edit that file before the first deployment, then sign out and back in:

```bash
bash bootstrap-oracle-ubuntu.sh https://github.com/walitemuri/forge_app.git
sudoedit /opt/forge/.env
exit
```

Create a dedicated SSH key for GitHub Actions and append its public key to the
VM user's `~/.ssh/authorized_keys`. In the GitHub repository, create a
`production` environment restricted to `main`, optionally require approval, and
add these environment secrets:

| Secret | Value |
| --- | --- |
| `OCI_HOST` | VM public IPv4 address or DNS name |
| `OCI_SSH_USER` | `ubuntu` |
| `OCI_SSH_PRIVATE_KEY` | Dedicated deployment private key, including header and footer |
| `OCI_SSH_KNOWN_HOSTS` | Output of `ssh-keyscan -H YOUR_VM_IP` verified against the VM host-key fingerprint |

Set the environment variables `FORGE_URL` to `https://YOUR_DOMAIN` and
`OCI_DEPLOY_ENABLED` to `true` after the VM and all four secrets are ready. Every
push to `main` runs controller, worker, dashboard, and Compose checks. Until that
flag is set, the deploy job is safely skipped. Once enabled, only after all checks
pass does the production job connect over SSH, fetch the exact triggering Git
commit, rebuild the images, recreate containers without deleting volumes, and
verify the dashboard, controller, and an online worker. Deployments are serialized
and fail if anyone has edited tracked files directly on the server.

The first ARM build can take a while. You can start it manually after bootstrap
or let the first successful GitHub Actions run do it:

```bash
cd /opt/forge
./deploy/deploy.sh "$(git rev-parse HEAD)"
```

### Manual deployment

1. Clone the repository onto the server. Copy `.env.example` to `.env` and set
   `FORGE_DOMAIN` to the actual DNS name, without `https://`, and
   `FORGE_DB_PASSWORD` to a long random value. Set the password **before** first
   starting PostgreSQL; changing the variable does not rotate an existing database.
2. Point the domain's A record (and AAAA record, if used) to the server. Allow
   incoming TCP ports 80 and 443, plus your restricted SSH access.
3. Start the public configuration:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.public.yml config --quiet
   docker compose -f docker-compose.yml -f docker-compose.public.yml up -d --build
   docker compose -f docker-compose.yml -f docker-compose.public.yml ps
   ```

4. Open `https://YOUR_DOMAIN/templates`, launch Parallel Processing, then Failure
   & Retry Recovery. Click a task in the graph to inspect its physical attempts.
   Launch video processing to check browser playback and download. Confirm that
   `/reliability` and `/api/forge/reliability/worker-loss` are unavailable and that
   the host does not publish 3000, 8080, 50051, or PostgreSQL.

Caddy obtains and renews certificates when the domain resolves to the host and
ports 80/443 are reachable. Its certificate state is kept in named volumes.
See [Caddy's HTTPS requirements](https://caddyserver.com/docs/automatic-https#overview)
and [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/).

Do not combine the public overlay with `docker-compose.chaos.yml`. That optional
local lab mounts the Docker socket and can terminate infrastructure. If you
previously ran it on this host, stop its agent before publication:

```bash
docker compose -f docker-compose.yml -f docker-compose.chaos.yml stop chaos-agent
```

Public mode also rejects both chaos API routes even if the chaos flag is enabled
accidentally. Caddy only proxies the dashboard; no raw task-submission API is
published. Use a fresh server/database for the public demo so historical private
commands and outputs do not become visible to visitors.

## Admission and operating bounds

The only public mutation accepts one allowlisted `template` key. Visitors cannot
supply commands, arguments, paths, task graphs, or run IDs. The route rejects
cross-origin browser launches and bodies larger than 1 KiB. The gateway limits
request bodies to 16 KiB.

Public launches have shared limits: a 20-second interval, at most three active
workflows, 40 launches per rolling 24 hours, and a 500-workflow archive ceiling.
Budgets are based on persisted controller history; a dashboard restart does not
reset them. A single-process submission lock prevents concurrent admission races.
Run **one dashboard instance**; this lock is not a distributed admission service.
Visitors may encounter a busy or budget message and can still browse history.
These limits bound submitted work; they are not a substitute for upstream DDoS
protection on an internet host.

Each task has a timeout and bounded retries. Public containers have CPU, memory,
and process limits. Workers have no host checkout or Docker socket mounted. The
worker engine remains an arbitrary process executor internally; keep its REST
and gRPC endpoints on the private Compose network and accept only trusted code.

To pause launches, set `FORGE_DEMO_LAUNCH_ENABLED=false` in `.env`, then recreate
the dashboard with the same two Compose files. This does not cancel active work.
To resume, set it to `true`. Check disk space periodically: PostgreSQL, artifacts,
and outboxes are persistent, and the archive limit intentionally requires an
operator decision instead of automatically deleting execution evidence. Export
needed runs before resetting a disposable demo database; never delete active
outboxes. There is no automatic retention deletion job.

```bash
docker compose -f docker-compose.yml -f docker-compose.public.yml logs --tail=100 dashboard controller worker-a
```

Always use both Compose files for public updates. Rebuild after code changes and
keep the database, shared artifact volume, and worker outboxes. Do not use
`down -v` unless you explicitly intend to delete the demo's durable state.

## Template outcomes

| Template | Tasks | Expected result |
| --- | ---: | --- |
| Distributed Video Processing | 5 | Success; three FFmpeg segments merge into a playable Tears of Steel MP4 |
| Full Orchestration Showcase | 23 | Intentional failure in an experimental branch; release and audit succeed |
| Distributed Build & Verification | 5 | Java unit tests, C++ tests, and Python syntax checks succeed |
| Failure & Retry Recovery | 4 | One intentional failure, then successful retry |
| Parallel Processing Pipeline | 5 | Three branches succeed before publish |
| Wait for Every Parent | 4 | Join waits for all inputs |
| Failure Isolation | 5 | Release skipped; independent analysis succeeds |
| Cascading Dependency Skip | 4 | Root fails; three descendants never execute |
| Retry Budget Exhaustion | 3 | Three failed attempts; publish skipped |
| Worker Capacity & Load | 10 | Eight bounded jobs join at a completion gate |

For a resume, describe the Java/C++ control plane, DAG execution, persisted
attempts, gRPC session fencing, durable outbox, and demonstrated recovery. Avoid
claims of production multi-tenancy, measured throughput, or performance speedups
without corresponding benchmarks. Link the live demo and repository together.
