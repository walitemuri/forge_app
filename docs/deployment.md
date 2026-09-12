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

### Azure for Students

The budget deployment is one Ubuntu 24.04 VM in North Central US, one of the
regions currently allowed by this Azure for Students subscription. The default
Bicep size is `Standard_B2als_v2` (2 vCPU, 4 GiB RAM) with a 64 GiB Standard SSD.
The Azure Compose overlay lowers the container ceilings and the bootstrap adds
4 GiB of swap. This is appropriate for a low-traffic portfolio demo; builds and
video jobs will be slower when the burstable VM is short on CPU credits. If the
heavy templates are unreliable, resize to `Standard_B2as_v2` (8 GiB) and keep
using the same disk and configuration.

At North Central US retail rates checked 2026-09-11, the selected Linux VM is
$0.0376/hour (about $27.45 per 730-hour month), the E6 Standard SSD is
$4.80/month, and the static IP is $0.006/hour (about $4.38/month). Budget about
$37 USD/month before disk operations, outbound traffic, and taxes. Confirm the
estimate in the Azure pricing calculator because rates and student entitlements
can change.

The template creates a resource group, static public IP with a free
`northcentralus.cloudapp.azure.com` hostname, network security group, virtual
network, and VM. Only 80 and 443 are public. SSH is key-only and restricted to
the CIDR supplied at deployment; ports 3000, 8080, 50051, and 5432 remain closed.

Install the Azure CLI, sign in to the Azure for Students subscription, and create
a dedicated key if needed. Deploy from the repository root:

```bash
az login
az account set --subscription "Azure for Students"
ssh-keygen -t ed25519 -f "$HOME/.ssh/forge_azure" -C forge-azure
az deployment sub create \
  --name forge-demo \
  --location northcentralus \
  --template-file deploy/azure/main.bicep \
  --parameters \
    sshPublicKey="$(< "$HOME/.ssh/forge_azure.pub")" \
    allowedSshCidr="$(curl -fsSL https://api.ipify.org)/32"
FORGE_HOST="$(az deployment sub show \
  --name forge-demo \
  --query properties.outputs.hostname.value \
  --output tsv)"
```

The automatically assigned hostname works with Caddy HTTPS, so a purchased
domain is optional. Copy and run the bootstrap, then set that hostname and a
new database password in `/opt/forge/.env`:

```bash
scp -i "$HOME/.ssh/forge_azure" \
  deploy/bootstrap-azure-ubuntu.sh "azureuser@$FORGE_HOST:"
ssh -i "$HOME/.ssh/forge_azure" "azureuser@$FORGE_HOST"
bash bootstrap-azure-ubuntu.sh https://github.com/walitemuri/forge_app.git
nano /opt/forge/.env
exit
```

Sign in again so the Docker group membership applies, then perform the first
deployment. The serial image build can take 20–40 minutes on the burstable VM:

```bash
ssh -i "$HOME/.ssh/forge_azure" "azureuser@$FORGE_HOST"
cd /opt/forge
./deploy/deploy-azure.sh "$(git rev-parse HEAD)"
```

Open `https://YOUR_HOSTNAME/templates`. Caddy obtains the certificate after the
hostname resolves and ports 80/443 are reachable.

Set a Cost Management budget and alert before leaving the VM running. Deallocating
the VM stops compute charges; the retained disk and static public IP remain billed:

```bash
az vm deallocate --resource-group forge-demo-rg --name forge-demo-vm
az vm start --resource-group forge-demo-rg --name forge-demo-vm
```

For optional GitHub Actions deployment, create a `production` environment
restricted to `main`, then configure these environment secrets:

| Secret | Value |
| --- | --- |
| `AZURE_HOST` | VM hostname from the deployment output |
| `AZURE_SSH_USER` | `azureuser` |
| `AZURE_SSH_PRIVATE_KEY` | Dedicated deployment private key, including header and footer |
| `AZURE_SSH_KNOWN_HOSTS` | Verified output of `ssh-keyscan -H YOUR_HOSTNAME` |

Set repository variables `FORGE_URL=https://YOUR_HOSTNAME` and
`AZURE_DEPLOY_ENABLED=true`. A GitHub-hosted runner must also be allowed through
the NSG on TCP 22; because its addresses change, prefer a self-hosted runner with
a fixed egress IP or keep automated deployment disabled and deploy manually.
Every push still runs CI when deployment is disabled. When enabled, the job
deploys the exact tested commit without deleting persistent Docker volumes.
You can also run the workflow manually on `main` and leave its **Deploy the
tested revision to Azure** input enabled; manual deployment does not depend on
the repository toggle.

### Manual deployment

1. Clone the repository onto the server. Copy `.env.example` to `.env` and set
   `FORGE_DOMAIN` to the actual DNS name, without `https://`, and
   `FORGE_DB_PASSWORD` to a long random value. Set the password **before** first
   starting PostgreSQL; changing the variable does not rotate an existing database.
2. Point the domain's A record (and AAAA record, if used) to the server. Allow
   incoming TCP ports 80 and 443, plus your restricted SSH access.
3. Start the public configuration. On the budget Azure VM, include the Azure
   overlay as shown:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.public.yml -f docker-compose.azure.yml config --quiet
   docker compose -f docker-compose.yml -f docker-compose.public.yml -f docker-compose.azure.yml up -d --build
   docker compose -f docker-compose.yml -f docker-compose.public.yml -f docker-compose.azure.yml ps
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
the dashboard with the same Compose files (including the Azure overlay when used).
This does not cancel active work.
To resume, set it to `true`. Check disk space periodically: PostgreSQL, artifacts,
and outboxes are persistent, and the archive limit intentionally requires an
operator decision instead of automatically deleting execution evidence. Export
needed runs before resetting a disposable demo database; never delete active
outboxes. There is no automatic retention deletion job.

```bash
docker compose -f docker-compose.yml -f docker-compose.public.yml logs --tail=100 dashboard controller worker-a
```

Always use the base and public Compose files, plus the Azure overlay on the
budget VM, for updates. `deploy/deploy-azure.sh` selects all three automatically.
Rebuild after code changes and keep the database, shared artifact volume, and
worker outboxes. Do not use `down -v` unless you explicitly intend to delete the
demo's durable state.

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
