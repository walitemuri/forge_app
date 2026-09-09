"use client";

import { memo, useId, useMemo, useState } from "react";
import {
  Background,
  BaseEdge,
  Controls,
  Edge,
  EdgeProps,
  getSmoothStepPath,
  Handle,
  Node,
  NodeProps,
  Panel,
  Position,
  ReactFlow,
} from "@xyflow/react";
import {
  Activity,
  Check,
  CircleDot,
  Clock3,
  Cpu,
  X,
  Zap,
} from "lucide-react";

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

interface TaskNodeData extends Record<string, unknown> {
  task: ForgeWorkflowTask;
  summary: TaskExecutionSummary;
  order: number;
  accent: string;
  accentRgb: string;
}

interface ExecutionEdgeData extends Record<string, unknown> {
  active: boolean;
  complete: boolean;
  color: string;
  delay: number;
}
interface TaskTopology {
  key: string;
  dependsOn: string[];
}


type TaskFlowNode = Node<
  TaskNodeData,
  "task"
>;

type ExecutionFlowEdge = Edge<
  ExecutionEdgeData,
  "execution"
>;

function statusAppearance(status: string) {
  switch (status) {
    case "SUCCEEDED":
      return {
        border: "#3f6212",
        background: "#111a0b",
        color: "#bef264",
        rgb: "163, 230, 53",
      };

    case "FAILED":
    case "LOST":
      return {
        border: "#7f1d1d",
        background: "#1c0c0c",
        color: "#fca5a5",
        rgb: "248, 113, 113",
      };

    case "RUNNING":
      return {
        border: "#1d4ed8",
        background: "#0c1424",
        color: "#93c5fd",
        rgb: "96, 165, 250",
      };

    case "DISPATCHED":
      return {
        border: "#075985",
        background: "#0b1720",
        color: "#7dd3fc",
        rgb: "56, 189, 248",
      };

    case "PENDING":
      return {
        border: "#854d0e",
        background: "#1c1507",
        color: "#fde68a",
        rgb: "250, 204, 21",
      };

    case "BLOCKED":
      return {
        border: "#52525b",
        background: "#18181b",
        color: "#d4d4d8",
        rgb: "161, 161, 170",
      };

    default:
      return {
        border: "#52525b",
        background: "#18181b",
        color: "#a1a1aa",
        rgb: "161, 161, 170",
      };
  }
}

function statusIcon(status: string) {
  switch (status) {
    case "SUCCEEDED":
      return <Check className="h-3 w-3" />;
    case "RUNNING":
    case "DISPATCHED":
      return <Zap className="h-3 w-3" />;
    case "FAILED":
    case "LOST":
      return <X className="h-3 w-3" />;
    default:
      return <Clock3 className="h-3 w-3" />;
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

function TaskNode({
  data,
  selected,
}: NodeProps<TaskFlowNode>) {
  const { task, summary } = data;
  const isLive = [
    "RUNNING",
    "DISPATCHED",
  ].includes(task.status);

  return (
    <div
      className={`execution-node execution-node--${task.status.toLowerCase()} ${
        selected
          ? "execution-node--selected"
          : ""
      }`}
      style={{
        "--node-accent": data.accent,
        "--node-accent-rgb": data.accentRgb,
        "--node-delay": `${data.order * 80 + 120}ms`,
      } as React.CSSProperties}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="execution-handle execution-handle--target"
      />

      <div className="execution-node__glow" />
      <div className="execution-node__scan" />

      <div className="execution-node__header">
        <div className="min-w-0">
          <div className="execution-node__eyebrow">
            <Cpu className="h-3 w-3" />
            EXECUTION UNIT
          </div>
          <div className="truncate font-mono text-sm font-semibold text-zinc-100">
            {task.key}
          </div>
        </div>

        <span className="execution-node__status">
          <span
            className={`execution-node__beacon ${
              isLive
                ? "execution-node__beacon--live"
                : ""
            }`}
          >
            {statusIcon(task.status)}
          </span>
          {task.status}
        </span>
      </div>

      <div className="execution-node__id">
        {task.taskId}
      </div>

      <div className="execution-node__telemetry">
        <div>
          <span>Worker</span>
          <strong title={summary.worker ?? "Unassigned"}>
            {summary.worker ?? "standby"}
          </strong>
        </div>
        <div>
          <span>Attempts</span>
          <strong>{summary.attempts}</strong>
        </div>
        <div>
          <span>Runtime</span>
          <strong>
            {formatDuration(summary.durationMs)}
          </strong>
        </div>
      </div>

      <div className="execution-node__rail">
        <span />
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="execution-handle execution-handle--source"
      />
    </div>
  );
}

const ExecutionEdge = memo(function ExecutionEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps<ExecutionFlowEdge>) {
  const glowId = useId();
  const [edgePath] =
    getSmoothStepPath({
      sourceX,
      sourceY,
      targetX,
      targetY,
      sourcePosition,
      targetPosition,
      borderRadius: 22,
      // Keep the two end offsets from overlapping on short connectors.
      offset: Math.min(34, Math.max(0, (targetX - sourceX) / 2 - 1)),
    });

  const active = data?.active ?? false;
  const complete = data?.complete ?? false;
  const color = data?.color ?? "#71717a";
  const delay = data?.delay ?? 0;

  return (
    <g
      className={`execution-edge ${
        active
          ? "execution-edge--active"
          : ""
      } ${
        complete
          ? "execution-edge--complete"
          : ""
      }`}
      style={{
        "--edge-color": color,
      } as React.CSSProperties}
    >
      <path
        d={edgePath}
        className="execution-edge__halo"
      />
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        className="execution-edge__core"
      />
      <defs>
        <radialGradient id={glowId}>
          <stop offset="0" stopColor={color} stopOpacity="0.9" />
          <stop offset="0.3" stopColor={color} stopOpacity="0.4" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </radialGradient>
      </defs>
      {/* Move one light along the actual curve without React animation ticks.
          Stable edges preserve its position through task-state refreshes. */}
      <g className="execution-edge__traveller" aria-hidden="true">
        <circle r="9" fill={`url(#${glowId})`} />
        <circle r="2.4" fill="#ecfeff" />
        <animateMotion
          path={edgePath}
          dur="2s"
          begin={`${delay}s`}
          repeatCount="indefinite"
          calcMode="paced"
        />
        <animate
          attributeName="opacity"
          values="0;1;1;0"
          keyTimes="0;0.05;0.95;1"
          dur="2s"
          begin={`${delay}s`}
          repeatCount="indefinite"
        />
      </g>
    </g>
  );
});

const nodeTypes = {
  task: TaskNode,
};

const edgeTypes = {
  execution: ExecutionEdge,
};

function buildNodes(
  tasks: ForgeWorkflowTask[],
  events: ForgeExecutionEvent[],
  selectedTaskKey: string | null,
): TaskFlowNode[] {
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

  return tasks.map((task, order) => {
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
        task,
        summary,
        order,
        accent: appearance.color,
        accentRgb: appearance.rgb,
      },
      type: "task",
      selected,
    };
  });
}

function buildEdges(tasks: TaskTopology[]): ExecutionFlowEdge[] {
  const edges: ExecutionFlowEdge[] = [];

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
        type: "execution",
        zIndex: 2,
        data: {
          active: false,
          complete: false,
          color: "#67e8f9",
          delay:
            (edges.length % 5) *
            -0.42,
        },
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
    <aside className="attempt-panel absolute inset-x-3 top-3 z-20 overflow-hidden rounded-xl border border-zinc-700 bg-[#0d0d10]/95 shadow-2xl backdrop-blur sm:left-auto sm:right-4 sm:top-4 sm:w-[350px]">
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
                    className="attempt-panel__card rounded-lg border border-zinc-800 bg-zinc-950 p-4"
                    style={{
                      "--attempt-delay": `${attempt.number * 60 + 90}ms`,
                    } as React.CSSProperties}
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

  /*
   * router.refresh() gives us fresh array identities every second. Parse
   * content-addressed snapshots so React Flow only receives new graph
   * objects when the underlying data actually changes.
   */
  const tasksSnapshot =
    JSON.stringify(tasks);
  const eventsSnapshot =
    JSON.stringify(events);
  const topologySnapshot =
    JSON.stringify(
      tasks.map((task) => ({
        key: task.key,
        dependsOn: task.dependsOn,
      })),
    );

  const stableTasks = useMemo(
    () =>
      JSON.parse(
        tasksSnapshot,
      ) as ForgeWorkflowTask[],
    [tasksSnapshot],
  );
  const stableEvents = useMemo(
    () =>
      JSON.parse(
        eventsSnapshot,
      ) as ForgeExecutionEvent[],
    [eventsSnapshot],
  );
  const stableTopology = useMemo(
    () =>
      JSON.parse(
        topologySnapshot,
      ) as TaskTopology[],
    [topologySnapshot],
  );

  const selectedTask =
    stableTasks.find(
      (task) =>
        task.key ===
        selectedTaskKey,
    ) ?? null;

  const nodes = useMemo(
    () =>
      buildNodes(
        stableTasks,
        stableEvents,
        selectedTaskKey,
      ),
    [
      stableTasks,
      stableEvents,
      selectedTaskKey,
    ],
  );

  const edges = useMemo(
    () => buildEdges(stableTopology),
    [stableTopology],
  );

  const liveTasks = tasks.filter(
    (task) =>
      [
        "RUNNING",
        "DISPATCHED",
      ].includes(task.status),
  ).length;

  const completedTasks = tasks.filter(
    (task) =>
      task.status === "SUCCEEDED",
  ).length;

  return (
    <div className="execution-graph relative h-[560px] w-full">
      <div className="execution-graph__aurora execution-graph__aurora--one" />
      <div className="execution-graph__aurora execution-graph__aurora--two" />

      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
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
        onPaneClick={() =>
          setSelectedTaskKey(null)
        }
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
          color="#3f3f46"
        />

        <Panel
          position="top-left"
          className="execution-hud"
        >
          <div className="execution-hud__signal">
            <Activity className="h-3.5 w-3.5" />
            <span>LIVE TOPOLOGY</span>
          </div>
          <div className="execution-hud__metrics">
            <span>
              <strong>{liveTasks}</strong>
              active
            </span>
            <span>
              <strong>{completedTasks}</strong>
              complete
            </span>
            <span>
              <strong>{edges.length}</strong>
              links
            </span>
          </div>
        </Panel>

        <Panel
          position="bottom-right"
          className="execution-legend"
        >
          <span>
            <i className="execution-legend__dot execution-legend__dot--live" />
            live
          </span>
          <span>
            <i className="execution-legend__dot execution-legend__dot--done" />
            complete
          </span>
          <span>
            <CircleDot className="h-3 w-3" />
            select a node
          </span>
        </Panel>

        <Controls
          showInteractive={false}
          className="execution-controls"
        />
      </ReactFlow>

      {selectedTask && (
        <AttemptPanel
          task={selectedTask}
          events={stableEvents}
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
