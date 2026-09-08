import Link from "next/link";
import {
  ArrowLeft,
  GitBranch,
} from "lucide-react";

import { AutoRefresh } from "@/components/auto-refresh";
import { WorkflowGraph } from "@/components/workflow-graph";
import { forgeFetch } from "@/lib/forge";
import type {
  ForgeExecutionEvent,
  ForgeWorkflowDetail,
} from "@/lib/types";

function statusClasses(status: string) {
  switch (status) {
    case "SUCCEEDED":
      return "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20";

    case "FAILED":
    case "LOST":
      return "bg-red-500/10 text-red-400 ring-red-500/20";

    case "RUNNING":
    case "DISPATCHED":
      return "bg-blue-500/10 text-blue-400 ring-blue-500/20";

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

function formatTimestamp(
  value: string,
) {
  return new Intl.DateTimeFormat(
    "en-CA",
    {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      timeZone: "UTC",
      timeZoneName: "short",
    },
  ).format(new Date(value));
}

function shortId(
  value: string | null,
) {
  if (!value) {
    return null;
  }

  return value.slice(0, 8);
}

export default async function WorkflowPage({
  params,
}: {
  params: Promise<{
    id: string;
  }>;
}) {
  const { id } = await params;

  const [
    workflow,
    events,
  ] = await Promise.all([
    forgeFetch<ForgeWorkflowDetail>(
      `/api/workflows/${id}`,
    ),

    forgeFetch<ForgeExecutionEvent[]>(
      `/api/workflows/${id}/events`,
    ),
  ]);

  const isActive = [
    "CREATED",
    "BLOCKED",
    "PENDING",
    "DISPATCHED",
    "RUNNING",
  ].includes(workflow.status);

  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <AutoRefresh
        enabled={isActive}
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

        <header className="mb-8 flex flex-col gap-5 border-b border-zinc-800 pb-8 md:flex-row md:items-start md:justify-between">
          <div>
            <div className="mb-3 flex items-center gap-2 text-sm text-zinc-500">
              <GitBranch className="h-4 w-4" />
              Workflow
            </div>

            <h1 className="text-2xl font-semibold tracking-tight">
              {workflow.name}
            </h1>

            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-zinc-500">
              <span>
                {workflow.tasks.length}{" "}
                {workflow.tasks.length === 1
                  ? "task"
                  : "tasks"}
              </span>

              <span>
                {formatTimestamp(
                  workflow.createdAt,
                )}
              </span>

              <span className="font-mono text-xs">
                {workflow.id}
              </span>
            </div>
          </div>

          <StatusBadge
            status={workflow.status}
          />
        </header>

        <section className="mb-8 overflow-hidden rounded-xl border border-zinc-800 bg-zinc-950">
          <div className="border-b border-zinc-800 px-5 py-4">
            <h2 className="font-medium">
              Execution Graph
            </h2>

            <p className="mt-1 text-xs text-zinc-500">
              Task dependencies and execution state
            </p>
          </div>

          <WorkflowGraph
            tasks={workflow.tasks}
            events={events}
          />
        </section>

        <section className="overflow-hidden rounded-xl border border-zinc-800 bg-zinc-950">
          <div className="border-b border-zinc-800 px-5 py-4">
            <h2 className="font-medium">
              Execution Timeline
            </h2>

            <p className="mt-1 text-xs text-zinc-500">
              Durable controller events in persistence order
            </p>
          </div>

          <div className="divide-y divide-zinc-900">
            {events.map((event) => (
              <div
                key={event.id}
                className="grid gap-3 px-5 py-4 md:grid-cols-[110px_180px_1fr]"
              >
                <div className="font-mono text-xs text-zinc-600">
                  #
                  {event.id}
                </div>

                <div>
                  <div className="font-mono text-xs font-medium text-zinc-300">
                    {event.type}
                  </div>

                  <div className="mt-1 text-[11px] text-zinc-600">
                    {formatTimestamp(
                      event.createdAt,
                    )}
                  </div>
                </div>

                <div>
                  <div className="text-sm text-zinc-300">
                    {event.message}
                  </div>

                  {(event.workerId ||
                    event.attemptId ||
                    event.taskId) && (
                    <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 font-mono text-[11px] text-zinc-600">
                      {event.workerId && (
                        <span>
                          worker{" "}
                          <span className="text-zinc-400">
                            {event.workerId}
                          </span>
                        </span>
                      )}

                      {event.taskId && (
                        <span>
                          task{" "}
                          <span className="text-zinc-400">
                            {shortId(
                              event.taskId,
                            )}
                          </span>
                        </span>
                      )}

                      {event.attemptId && (
                        <span>
                          attempt{" "}
                          <span className="text-zinc-400">
                            {shortId(
                              event.attemptId,
                            )}
                          </span>
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </main>
  );
}
