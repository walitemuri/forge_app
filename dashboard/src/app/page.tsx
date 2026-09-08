import Link from "next/link";
import {
  Activity,
  Boxes,
  CircleCheck,
  CircleX,
  Workflow,
} from "lucide-react";

import { forgeFetch } from "@/lib/forge";
import type {
  ForgeWorker,
  ForgeWorkflow,
} from "@/lib/types";

function statusClasses(status: string) {
  switch (status) {
    case "SUCCEEDED":
    case "ONLINE":
      return "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20";

    case "RUNNING":
    case "DISPATCHED":
      return "bg-blue-500/10 text-blue-400 ring-blue-500/20";

    case "FAILED":
    case "LOST":
      return "bg-red-500/10 text-red-400 ring-red-500/20";

    case "PENDING":
    case "BLOCKED":
      return "bg-amber-500/10 text-amber-400 ring-amber-500/20";

    default:
      return "bg-zinc-500/10 text-zinc-400 ring-zinc-500/20";
  }
}

function StatusBadge({
  status,
}: {
  status: string;
}) {
  return (
    <span
      className={`inline-flex rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset ${statusClasses(
        status,
      )}`}
    >
      {status}
    </span>
  );
}

async function loadData() {
  const [workersResult, workflowsResult] =
    await Promise.allSettled([
      forgeFetch<ForgeWorker[]>(
        "/api/workers",
      ),
      forgeFetch<ForgeWorkflow[]>(
        "/api/workflows",
      ),
    ]);

  return {
    workers:
      workersResult.status === "fulfilled"
        ? workersResult.value
        : [],

    workflows:
      workflowsResult.status === "fulfilled"
        ? workflowsResult.value
        : [],

    controllerOnline:
      workersResult.status === "fulfilled" ||
      workflowsResult.status === "fulfilled",
  };
}

export default async function Home() {
  const {
    workers,
    workflows,
    controllerOnline,
  } = await loadData();

  const onlineWorkers = workers.filter(
    (worker) => worker.online,
  ).length;

  const activeWorkflows =
    workflows.filter((workflow) =>
      [
        "CREATED",
        "PENDING",
        "RUNNING",
      ].includes(workflow.status),
    ).length;

  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <div className="mx-auto max-w-7xl px-6 py-8">
        <header className="mb-10 flex items-center justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2">
              <Boxes className="h-5 w-5 text-zinc-400" />

              <span className="text-sm font-medium text-zinc-400">
                Forge
              </span>
            </div>

            <h1 className="text-3xl font-semibold tracking-tight">
              Control Plane
            </h1>

            <p className="mt-2 text-sm text-zinc-500">
              Distributed workflow execution
            </p>
          </div>

          <div className="flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2">
            {controllerOnline ? (
              <CircleCheck className="h-4 w-4 text-emerald-400" />
            ) : (
              <CircleX className="h-4 w-4 text-red-400" />
            )}

            <span className="text-sm text-zinc-300">
              Controller{" "}
              {controllerOnline
                ? "online"
                : "offline"}
            </span>
          </div>
        </header>

        <section className="mb-8 grid gap-4 md:grid-cols-3">
          <Metric
            label="Workers Online"
            value={`${onlineWorkers}/${workers.length}`}
            icon={<Activity className="h-4 w-4" />}
          />

          <Metric
            label="Workflows"
            value={String(workflows.length)}
            icon={<Workflow className="h-4 w-4" />}
          />

          <Metric
            label="Active Workflows"
            value={String(activeWorkflows)}
            icon={<Activity className="h-4 w-4" />}
          />
        </section>

        <div className="grid gap-6 lg:grid-cols-2">
          <section className="rounded-xl border border-zinc-800 bg-zinc-950">
            <div className="border-b border-zinc-800 px-5 py-4">
              <h2 className="font-medium">
                Workers
              </h2>

              <p className="mt-1 text-xs text-zinc-500">
                Connected execution agents
              </p>
            </div>

            <div className="divide-y divide-zinc-900">
              {workers.length === 0 ? (
                <EmptyState text="No workers connected" />
              ) : (
                workers.map((worker) => (
                  <div
                    key={worker.id}
                    className="flex items-center justify-between px-5 py-4"
                  >
                    <div>
                      <div className="font-mono text-sm text-zinc-200">
                        {worker.id}
                      </div>

                      {worker.sessionId && (
                        <div className="mt-1 max-w-64 truncate font-mono text-xs text-zinc-600">
                          {worker.sessionId}
                        </div>
                      )}
                    </div>

                    <StatusBadge
                      status={
                        worker.online
                          ? "ONLINE"
                          : "OFFLINE"
                      }
                    />
                  </div>
                ))
              )}
            </div>
          </section>

          <section className="rounded-xl border border-zinc-800 bg-zinc-950">
            <div className="border-b border-zinc-800 px-5 py-4">
              <h2 className="font-medium">
                Recent Workflows
              </h2>

              <p className="mt-1 text-xs text-zinc-500">
                Durable workflow executions
              </p>
            </div>

            <div className="divide-y divide-zinc-900">
              {workflows.length === 0 ? (
                <EmptyState text="No workflows yet" />
              ) : (
                workflows
                  .slice(0, 8)
                  .map((workflow) => (
                    <div
                      key={workflow.id}
                      className="flex items-center justify-between px-5 py-4"
                    >
                      <div>
                        <Link
                          href={`/workflows/${workflow.id}`}
                          className="text-sm text-zinc-200 transition hover:text-white hover:underline"
                        >
                          {workflow.name ??
                            `Workflow ${workflow.id}`}
                        </Link>

                        <div className="mt-1 font-mono text-xs text-zinc-600">
                          {workflow.id}
                        </div>
                      </div>

                      <StatusBadge
                        status={
                          workflow.status
                        }
                      />
                    </div>
                  ))
              )}
            </div>
          </section>
        </div>
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

function EmptyState({
  text,
}: {
  text: string;
}) {
  return (
    <div className="px-5 py-10 text-center text-sm text-zinc-600">
      {text}
    </div>
  );
}
