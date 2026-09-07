# Security policy

## Current trust model

Forge is a distributed-systems development project, not a hardened multi-tenant service. Run it only on trusted networks with trusted API clients, controller operators, database administrators, and worker hosts.

The current implementation intentionally leaves several production controls out of scope:

- REST endpoints have no authentication or authorization.
- gRPC uses insecure channel credentials and does not authenticate workers.
- Submitted commands execute with the worker process account.
- The worker uses host process execution, not containers or a sandbox.
- Command output is persisted without size limits or secret filtering.
- Local Compose credentials are checked into source for development convenience.

Do not bind the services to an untrusted network, run the worker as root, reuse the development database password, or process untrusted commands.

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
