# Security policy

## Current trust model

Forge is a distributed-systems development project, not a hardened multi-tenant service. Keep the engine APIs on trusted networks with trusted API clients, controller operators, database administrators, and worker hosts. The public demo serves a restricted dashboard over HTTPS; visitors can submit only predefined templates.

The current implementation intentionally leaves several production controls out of scope:

- REST endpoints have no authentication or authorization.
- gRPC uses insecure channel credentials and does not authenticate workers.
- Submitted commands execute with the worker process account.
- The worker executes child processes with its own privileges. Public deployment places each worker in a resource-limited Docker container, but does not create a separate sandbox per task.
- Worker stdout and stderr capture is capped at 1 MiB per stream; output is not filtered for secrets.
- Local Compose credentials are checked into source for development convenience.

Do not publish the internal engine services, run native workers as host root, reuse the development database password publicly, or process untrusted commands. See [public deployment](docs/deployment.md) for the curated demo boundary, single-dashboard admission limits, and private infrastructure controls.

## Safe local use

- Keep REST, gRPC, and PostgreSQL behind a local firewall.
- Run the worker under a dedicated non-privileged user with access only to disposable work directories.
- Set `FORGE_OUTBOX_DIR` to a directory owned only by that worker user.
- Avoid including credentials in command arguments because task data, timelines, logs, and process listings may expose them.
- Use disposable infrastructure for failure-injection smoke tests.
- Back up PostgreSQL and the worker outbox together when recovery guarantees matter.

## Reporting a vulnerability

Please report security issues privately through the repository owner's preferred private contact or GitHub's private vulnerability-reporting feature when enabled. Include the affected commit, reproduction steps, impact, and any proposed mitigation. Avoid opening a public issue for an unpatched vulnerability.

## Hardening roadmap

A production-oriented deployment should add mTLS worker identity, authenticated and authorized API access, secret management, network policy, sandboxed execution, resource and output limits, audit logging, dependency scanning, rate limiting, artifact isolation, and explicit tenant boundaries. See the [operations hardening checklist](docs/operations.md#production-hardening-checklist).
