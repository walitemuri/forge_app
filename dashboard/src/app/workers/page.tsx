import {
  Activity,
  ArrowLeft,
  Cpu,
  HardDrive,
  Server,
} from "lucide-react";
import Link from "next/link";

import { AutoRefresh } from "@/components/auto-refresh";
import { forgeFetch } from "@/lib/forge";
import type { ForgeWorker } from "@/lib/types";

function formatBytes(bytes: number) {
  if (bytes <= 0) {
    return "0 B";
  }

  const gib =
    bytes / 1024 / 1024 / 1024;

  if (gib >= 1) {
    return `${gib.toFixed(1)} GiB`;
  }

  const mib =
    bytes / 1024 / 1024;

  return `${mib.toFixed(0)} MiB`;
}

function formatPercent(value: number) {
  return `${value.toFixed(1)}%`;
}

function heartbeatAge(
  lastHeartbeat: number,
) {
  const elapsed =
    Math.max(
      0,
      Date.now() - lastHeartbeat,
    );

  if (elapsed < 1000) {
    return "<1s ago";
  }

  if (elapsed < 60_000) {
    return `${Math.floor(
      elapsed / 1000,
    )}s ago`;
  }

  return `${Math.floor(
    elapsed / 60_000,
  )}m ago`;
}

function WorkerStatus({
  worker,
}: {
  worker: ForgeWorker;
}) {
  if (!worker.online) {
    return (
      <span className="rounded-md bg-red-500/10 px-2 py-1 text-xs font-medium text-red-400 ring-1 ring-inset ring-red-500/20">
        OFFLINE
      </span>
    );
  }

  if (
    !worker.commandStreamConnected
  ) {
    return (
      <span className="rounded-md bg-amber-500/10 px-2 py-1 text-xs font-medium text-amber-400 ring-1 ring-inset ring-amber-500/20">
        DEGRADED
      </span>
    );
  }

  return (
    <span className="rounded-md bg-emerald-500/10 px-2 py-1 text-xs font-medium text-emerald-400 ring-1 ring-inset ring-emerald-500/20">
      ONLINE
    </span>
  );
}

export default async function WorkersPage() {
  let workers: ForgeWorker[] = [];
  let controllerOnline = true;

  try {
    workers =
      await forgeFetch<ForgeWorker[]>(
        "/api/workers",
      );
  } catch {
    controllerOnline = false;
  }

  workers.sort((a, b) => {
    if (a.online !== b.online) {
      return a.online ? -1 : 1;
    }

    return a.id.localeCompare(b.id);
  });

  const online =
    workers.filter(
      (worker) => worker.online,
    );

  const totalCapacity =
    online.reduce(
      (total, worker) =>
        total + worker.capacity,
      0,
    );

  const runningTasks =
    online.reduce(
      (total, worker) =>
        total +
        worker.runningTasks,
      0,
    );

  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <AutoRefresh
        enabled
        intervalMs={1000}
      />

      <div className="mx-auto max-w-7xl px-6 py-8">
        <Link
          href="/"
          className="mb-8 inline-flex items-center gap-2 text-sm text-zinc-500 transition hover:text-zinc-200"
        >
          <ArrowLeft className="h-4 w-4" />
          Control Plane
        </Link>

        <header className="mb-8 border-b border-zinc-800 pb-8">
          <div className="mb-3 flex items-center gap-2 text-sm text-zinc-500">
            <Server className="h-4 w-4" />
            Execution Plane
          </div>

          <div className="flex items-end justify-between gap-6">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">
                Workers
              </h1>

              <p className="mt-2 text-sm text-zinc-500">
                Live execution agents registered
                with the Forge controller.
              </p>
            </div>

            <div
              className={
                controllerOnline
                  ? "text-xs text-emerald-400"
                  : "text-xs text-red-400"
              }
            >
              Controller{" "}
              {controllerOnline
                ? "online"
                : "unreachable"}
            </div>
          </div>
        </header>

        <section className="mb-8 grid gap-4 md:grid-cols-3">
          <Metric
            label="Online Workers"
            value={`${online.length}/${workers.length}`}
            icon={
              <Activity className="h-4 w-4" />
            }
          />

          <Metric
            label="Execution Capacity"
            value={String(
              totalCapacity,
            )}
            icon={
              <Cpu className="h-4 w-4" />
            }
          />

          <Metric
            label="Running Tasks"
            value={String(
              runningTasks,
            )}
            icon={
              <HardDrive className="h-4 w-4" />
            }
          />
        </section>

        {workers.length === 0 ? (
          <div className="rounded-xl border border-zinc-800 bg-zinc-950 px-6 py-16 text-center text-sm text-zinc-600">
            No workers registered.
          </div>
        ) : (
          <div className="grid gap-5 lg:grid-cols-2">
            {workers.map(
              (worker) => {
                const memoryPercent =
                  worker.memoryBytes > 0
                    ? (
                        worker.memoryUsedBytes /
                        worker.memoryBytes
                      ) * 100
                    : 0;

                return (
                  <article
                    key={worker.id}
                    className="rounded-xl border border-zinc-800 bg-zinc-950"
                  >
                    <div className="flex items-start justify-between border-b border-zinc-800 px-5 py-4">
                      <div>
                        <div className="font-mono text-sm font-semibold text-zinc-100">
                          {worker.id}
                        </div>

                        <div className="mt-1 text-xs text-zinc-600">
                          {worker.hostname} ·{" "}
                          {
                            worker.operatingSystem
                          }
                        </div>
                      </div>

                      <WorkerStatus
                        worker={worker}
                      />
                    </div>

                    <div className="grid grid-cols-2 gap-px bg-zinc-800">
                      <WorkerMetric
                        label="CPU"
                        value={formatPercent(
                          worker.cpuUsagePercent,
                        )}
                        detail={`${worker.cpuCores} cores`}
                      />

                      <WorkerMetric
                        label="Memory"
                        value={formatPercent(
                          memoryPercent,
                        )}
                        detail={`${formatBytes(
                          worker.memoryUsedBytes,
                        )} / ${formatBytes(
                          worker.memoryBytes,
                        )}`}
                      />

                      <WorkerMetric
                        label="Running"
                        value={String(
                          worker.runningTasks,
                        )}
                        detail={`${worker.outstandingTasks} outstanding`}
                      />

                      <WorkerMetric
                        label="Capacity"
                        value={String(
                          worker.capacity,
                        )}
                        detail="execution slots"
                      />
                    </div>

                    <div className="space-y-3 px-5 py-4">
                      <Detail
                        label="Session"
                        value={
                          worker.sessionId ||
                          "—"
                        }
                      />

                      <Detail
                        label="Command stream"
                        value={
                          worker.commandStreamConnected
                            ? "Connected"
                            : "Disconnected"
                        }
                      />

                      <Detail
                        label="Last heartbeat"
                        value={heartbeatAge(
                          worker.lastHeartbeat,
                        )}
                      />
                    </div>
                  </article>
                );
              },
            )}
          </div>
        )}
      </div>
    </main>
  );
}

function Metric({
  label,
  value,
  icon,
}: {
  label: string;
  value: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-950 p-5">
      <div className="flex items-center gap-2 text-sm text-zinc-500">
        {icon}
        {label}
      </div>

      <div className="mt-3 text-3xl font-semibold tracking-tight">
        {value}
      </div>
    </div>
  );
}

function WorkerMetric({
  label,
  value,
  detail,
}: {
  label: string;
  value: string;
  detail: string;
}) {
  return (
    <div className="bg-zinc-950 px-5 py-4">
      <div className="text-xs text-zinc-600">
        {label}
      </div>

      <div className="mt-1 font-mono text-lg text-zinc-200">
        {value}
      </div>

      <div className="mt-1 text-xs text-zinc-600">
        {detail}
      </div>
    </div>
  );
}

function Detail({
  label,
  value,
}: {
  label: string;
  value: string;
}) {
  return (
    <div className="grid grid-cols-[120px_1fr] items-center text-xs">
      <span className="text-zinc-600">
        {label}
      </span>

      <span className="truncate font-mono text-zinc-300">
        {value}
      </span>
    </div>
  );
}
