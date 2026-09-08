"use client";

import { useState } from "react";
import {
  Background,
  Controls,
  Edge,
  Node,
  ReactFlow,
} from "@xyflow/react";
import { X } from "lucide-react";

import type {
  ForgeExecutionEvent,
  ForgeWorkflowTask,
} from "@/lib/types";

interface WorkflowGraphProps {
  tasks: ForgeWorkflowTask[];
  events: ForgeExecutionEvent[];
}

interface TaskExecutionSummary {
  worker: string | null;
  attempts: number;
  durationMs: number | null;
}

interface AttemptSummary {
  attemptId: string;
  number: number;
  worker: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
}

function statusAppearance(status: string) {
  switch (status) {
    case "SUCCEEDED":
      return {
        border: "#3f6212",
        background: "#111a0b",
        color: "#bef264",
      };

    case "FAILED":
    case "LOST":
      return {
        border: "#7f1d1d",
        background: "#1c0c0c",
        color: "#fca5a5",
      };

    case "RUNNING":
      return {
        border: "#1d4ed8",
        background: "#0c1424",
        color: "#93c5fd",
      };

    case "DISPATCHED":
      return {
        border: "#075985",
        background: "#0b1720",
        color: "#7dd3fc",
      };

    case "PENDING":
      return {
        border: "#854d0e",
        background: "#1c1507",
        color: "#fde68a",
      };

    case "BLOCKED":
      return {
        border: "#52525b",
        background: "#18181b",
        color: "#d4d4d8",
      };

    default:
      return {
        border: "#52525b",
        background: "#18181b",
        color: "#a1a1aa",
      };
  }
}

function calculateLevels(
  tasks: ForgeWorkflowTask[],
) {
  const taskByKey = new Map(
    tasks.map((task) => [
      task.key,
      task,
    ]),
  );

  const memo = new Map<string, number>();
  const visiting = new Set<string>();

  function levelFor(key: string): number {
    if (memo.has(key)) {
      return memo.get(key)!;
    }

    if (visiting.has(key)) {
      return 0;
    }

    visiting.add(key);

    const task = taskByKey.get(key);

    if (
      !task ||
      task.dependsOn.length === 0
    ) {
      memo.set(key, 0);
      visiting.delete(key);

      return 0;
    }

    const level =
      Math.max(
        ...task.dependsOn.map(
          (dependency) =>
            levelFor(dependency),
        ),
      ) + 1;

    memo.set(key, level);
    visiting.delete(key);

    return level;
  }

  for (const task of tasks) {
    levelFor(task.key);
  }

  return memo;
}

function taskEvents(
  taskId: string,
  events: ForgeExecutionEvent[],
) {
  return events.filter(
    (event) =>
      event.taskId === taskId,
  );
}

function attemptSummaries(
  taskId: string,
  events: ForgeExecutionEvent[],
): AttemptSummary[] {
  const filtered =
    taskEvents(taskId, events);

  const created =
    filtered.filter(
      (event) =>
        event.type ===
          "ATTEMPT_CREATED" &&
        event.attemptId,
    );

  return created.map(
    (createdEvent, index) => {
      const attemptId =
        createdEvent.attemptId!;

      const attemptEvents =
        filtered.filter(
          (event) =>
            event.attemptId ===
            attemptId,
        );

      let worker: string | null =
        null;

      for (
        let i =
          attemptEvents.length - 1;
        i >= 0;
        i -= 1
      ) {
        if (
          attemptEvents[i].workerId
        ) {
          worker =
            attemptEvents[i].workerId;
          break;
        }
      }

      const running =
        attemptEvents.find(
          (event) =>
            event.type ===
            "ATTEMPT_RUNNING",
        );

      const dispatched =
        attemptEvents.find(
          (event) =>
            event.type ===
            "ATTEMPT_DISPATCHED",
        );

      const terminal =
        attemptEvents.find(
          (event) =>
            [
              "ATTEMPT_SUCCEEDED",
              "ATTEMPT_FAILED",
              "ATTEMPT_LOST",
              "ATTEMPT_CANCELLED",
            ].includes(event.type),
        );

      let status = "CREATED";

      if (terminal) {
        status =
          terminal.type.replace(
            "ATTEMPT_",
            "",
          );
      } else if (running) {
        status = "RUNNING";
      } else if (dispatched) {
        status = "DISPATCHED";
      }

      const startedAt =
        running?.createdAt ??
        dispatched?.createdAt ??
        null;

      const finishedAt =
        terminal?.createdAt ??
        null;

      let durationMs: number | null =
        null;

      if (
        startedAt &&
        finishedAt
      ) {
        durationMs =
          new Date(
            finishedAt,
          ).getTime()
          -
          new Date(
            startedAt,
          ).getTime();
      }

      return {
        attemptId,
        number: index + 1,
        worker,
        status,
        startedAt,
        finishedAt,
        durationMs,
      };
    },
  );
}

function executionSummary(
  taskId: string,
  events: ForgeExecutionEvent[],
): TaskExecutionSummary {
  const attempts =
    attemptSummaries(
      taskId,
      events,
    );

  const latest =
    attempts.at(-1);

  return {
    worker:
      latest?.worker ?? null,

    attempts:
      attempts.length,

    durationMs:
      latest?.durationMs ?? null,
  };
}

function formatDuration(
  durationMs: number | null,
) {
  if (durationMs === null) {
    return "—";
  }

  if (durationMs < 1000) {
    return `${durationMs}ms`;
  }

  return `${(
    durationMs / 1000
  ).toFixed(1)}s`;
}

function formatTimestamp(
  value: string | null,
) {
  if (!value) {
    return "—";
  }

  return new Intl.DateTimeFormat(
    "en-CA",
    {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    },
  ).format(
    new Date(value),
  );
}

function buildNodes(
  tasks: ForgeWorkflowTask[],
  events: ForgeExecutionEvent[],
  selectedTaskKey: string | null,
): Node[] {
  const levels =
    calculateLevels(tasks);

  const grouped =
    new Map<
      number,
      ForgeWorkflowTask[]
    >();

  for (const task of tasks) {
    const level =
      levels.get(task.key) ?? 0;

    const existing =
      grouped.get(level) ?? [];

    existing.push(task);

    grouped.set(
      level,
      existing,
    );
  }

  return tasks.map((task) => {
    const level =
      levels.get(task.key) ?? 0;

    const tasksAtLevel =
      grouped.get(level) ?? [];

    const index =
      tasksAtLevel.findIndex(
        (candidate) =>
          candidate.key ===
          task.key,
      );

    const summary =
      executionSummary(
        task.taskId,
        events,
      );

    const appearance =
      statusAppearance(task.status);

    const selected =
      task.key ===
      selectedTaskKey;

    return {
      id: task.key,

      position: {
        x: level * 340,
        y: index * 205,
      },

      data: {
        label: (
          <div className="w-[230px] text-left">
            <div className="mb-3 flex items-start justify-between gap-3">
              <span className="font-mono text-sm font-semibold text-zinc-100">
                {task.key}
              </span>

              <span
                className="rounded px-1.5 py-0.5 text-[10px] font-medium"
                style={{
                  color:
                    appearance.color,
                }}
              >
                {task.status}
              </span>
            </div>

            <div className="truncate font-mono text-[10px] text-zinc-600">
              {task.taskId}
            </div>

            <div className="mt-4 grid grid-cols-[70px_1fr] gap-y-2 border-t border-zinc-800 pt-3 text-xs">
              <span className="text-zinc-600">
                Worker
              </span>

              <span className="truncate font-mono text-zinc-300">
                {summary.worker ??
                  "—"}
              </span>

              <span className="text-zinc-600">
                Attempts
              </span>

              <span className="font-mono text-zinc-300">
                {summary.attempts}
              </span>

              <span className="text-zinc-600">
                Duration
              </span>

              <span className="font-mono text-zinc-300">
                {formatDuration(
                  summary.durationMs,
                )}
              </span>
            </div>
          </div>
        ),
      },

      style: {
        width: 260,
        border:
          `1px solid ${appearance.border}`,
        background:
          appearance.background,
        borderRadius: 10,
        padding: 14,
        color:
          appearance.color,
        cursor: "pointer",
        boxShadow:
          selected
            ? "0 0 0 2px rgba(161,161,170,0.35)"
            : "none",
      },
    };
  });
}

function buildEdges(
  tasks: ForgeWorkflowTask[],
): Edge[] {
  const edges: Edge[] = [];

  for (const task of tasks) {
    for (
      const dependency
      of task.dependsOn
    ) {
      edges.push({
        id:
          `${dependency}-${task.key}`,
        source: dependency,
        target: task.key,
        type: "smoothstep",
      });
    }
  }

  return edges;
}

function AttemptPanel({
  task,
  events,
  onClose,
}: {
  task: ForgeWorkflowTask;
  events: ForgeExecutionEvent[];
  onClose: () => void;
}) {
  const attempts =
    attemptSummaries(
      task.taskId,
      events,
    );

  return (
    <aside className="absolute right-4 top-4 z-20 w-[350px] overflow-hidden rounded-xl border border-zinc-700 bg-[#0d0d10]/95 shadow-2xl backdrop-blur">
      <div className="flex items-start justify-between border-b border-zinc-800 px-4 py-4">
        <div>
          <div className="font-mono text-sm font-semibold text-zinc-100">
            {task.key}
          </div>

          <div className="mt-1 font-mono text-[10px] text-zinc-600">
            {task.taskId}
          </div>
        </div>

        <button
          type="button"
          onClick={onClose}
          className="rounded-md p-1 text-zinc-500 transition hover:bg-zinc-800 hover:text-zinc-200"
          aria-label="Close task details"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="border-b border-zinc-800 px-4 py-3 text-xs">
        <span className="text-zinc-500">
          Task status
        </span>

        <span className="ml-3 font-mono text-zinc-200">
          {task.status}
        </span>
      </div>

      <div className="max-h-[430px] overflow-y-auto p-4">
        <div className="mb-3 text-xs font-medium uppercase tracking-wide text-zinc-600">
          Attempts
        </div>

        {attempts.length === 0 ? (
          <div className="rounded-lg border border-zinc-800 bg-zinc-950 p-4 text-sm text-zinc-600">
            No execution attempts yet.
          </div>
        ) : (
          <div className="space-y-3">
            {attempts.map(
              (attempt) => {
                const appearance =
                  statusAppearance(
                    attempt.status,
                  );

                return (
                  <div
                    key={
                      attempt.attemptId
                    }
                    className="rounded-lg border border-zinc-800 bg-zinc-950 p-4"
                  >
                    <div className="mb-4 flex items-center justify-between">
                      <span className="text-sm font-medium text-zinc-200">
                        Attempt{" "}
                        {attempt.number}
                      </span>

                      <span
                        className="font-mono text-[10px]"
                        style={{
                          color:
                            appearance.color,
                        }}
                      >
                        {
                          attempt.status
                        }
                      </span>
                    </div>

                    <div className="grid grid-cols-[75px_1fr] gap-y-2 text-xs">
                      <span className="text-zinc-600">
                        Worker
                      </span>

                      <span className="truncate font-mono text-zinc-300">
                        {attempt.worker ??
                          "—"}
                      </span>

                      <span className="text-zinc-600">
                        Attempt ID
                      </span>

                      <span className="truncate font-mono text-zinc-400">
                        {
                          attempt.attemptId
                        }
                      </span>

                      <span className="text-zinc-600">
                        Started
                      </span>

                      <span className="font-mono text-zinc-400">
                        {formatTimestamp(
                          attempt.startedAt,
                        )}
                      </span>

                      <span className="text-zinc-600">
                        Duration
                      </span>

                      <span className="font-mono text-zinc-300">
                        {formatDuration(
                          attempt.durationMs,
                        )}
                      </span>
                    </div>
                  </div>
                );
              },
            )}
          </div>
        )}
      </div>
    </aside>
  );
}

export function WorkflowGraph({
  tasks,
  events,
}: WorkflowGraphProps) {
  const [
    selectedTaskKey,
    setSelectedTaskKey,
  ] =
    useState<string | null>(
      null,
    );

  const selectedTask =
    tasks.find(
      (task) =>
        task.key ===
        selectedTaskKey,
    ) ?? null;

  const nodes =
    buildNodes(
      tasks,
      events,
      selectedTaskKey,
    );

  const edges =
    buildEdges(tasks);

  return (
    <div className="relative h-[560px] w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        fitViewOptions={{
          padding: 0.2,
        }}
        minZoom={0.3}
        maxZoom={1.5}
        colorMode="dark"
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        onNodeClick={(
          _event,
          node,
        ) => {
          setSelectedTaskKey(
            node.id,
          );
        }}
      >
        <Background
          gap={24}
          size={1}
        />

        <Controls
          showInteractive={false}
        />
      </ReactFlow>

      {selectedTask && (
        <AttemptPanel
          task={selectedTask}
          events={events}
          onClose={() =>
            setSelectedTaskKey(
              null,
            )
          }
        />
      )}
    </div>
  );
}
