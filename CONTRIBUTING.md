# Contributing to Forge

Thanks for helping improve Forge. Changes are easiest to review when they preserve the project's core invariants: durable intent before external I/O, explicit task/attempt state, authoritative worker sessions, and replay-safe events.

## Development loop

1. Start PostgreSQL with `docker compose up -d`.
2. Build and test the controller with `(cd controller && ./gradlew test)`.
3. Build the worker with `cmake -S worker -B worker/build && cmake --build worker/build --parallel`.
4. Run the smoke tests relevant to the behavior you changed.
5. Update the README, API reference, lifecycle tables, and diagrams when contracts or architecture change.

See the [development guide](docs/development.md) for toolchain setup, protocol changes, migrations, and the full verification checklist.

## Change guidelines

- Keep pull requests focused and explain the failure mode or invariant being addressed.
- Add a new Flyway migration; do not rewrite an applied migration.
- Edit `proto/forge.proto`, not generated Java or C++ sources.
- Preserve protobuf field numbers and cross-version compatibility.
- Include concurrency or restart coverage for changes involving ownership, recovery, outbox delivery, or scheduled coordinators.
- Keep task and attempt transitions consistent and emit a timeline event for material changes.
- Never commit build outputs, logs, outbox files, secrets, or Python cache files.

## Pull request checklist

- [ ] Controller tests pass with PostgreSQL running.
- [ ] C++ worker builds from a clean CMake configuration.
- [ ] Relevant end-to-end or failure-injection tests pass.
- [ ] New persistence invariants are enforced transactionally or by database constraints.
- [ ] Worker messages remain session-fenced and replay-safe.
- [ ] Public behavior and configuration are documented.
- [ ] Security implications of new command, network, or persistence behavior were considered.

## Commit messages

Use an imperative summary that explains the outcome, for example:

```text
Fence command stream attachment during worker takeover
Persist cancellation intent before notifying workers
Document outbox replay and recovery boundaries
```
