import {
  forgeFetch,
} from "@/lib/forge";

import type {
  ForgeExecutionEvent,
  ForgeWorkflowDetail,
} from "@/lib/types";

export interface LabTask {
  key: string;
  command: string;
  arguments: string[];
  maxAttempts: number;
  timeoutSeconds: number;
  dependsOn: string[];
}

export interface LabWorkflow {
  name: string;
  tasks: LabTask[];
}

export interface CreatedWorkflow {
  id: string;
  name: string;
}

export function shell(
  key: string,
  command: string,
  dependsOn: string[],
  options?: {
    maxAttempts?: number;
    timeoutSeconds?: number;
  },
): LabTask {
  return {
    key,

    command:
      "bash",

    arguments: [
      "-lc",
      command,
    ],

    maxAttempts:
      options?.maxAttempts ?? 1,

    timeoutSeconds:
      options?.timeoutSeconds ?? 120,

    dependsOn,
  };
}

function sleep(
  milliseconds: number,
) {
  return new Promise(
    (resolve) =>
      setTimeout(
        resolve,
        milliseconds,
      ),
  );
}

export async function waitForRunningAttempt(
  workflowId: string,
  taskKey: string,
  timeoutMs = 20000,
) {
  const deadline =
    Date.now() +
    timeoutMs;

  while (
    Date.now() <
    deadline
  ) {
    const [
      workflow,
      events,
    ] =
      await Promise.all([
        forgeFetch<
          ForgeWorkflowDetail
        >(
          `/api/workflows/${workflowId}`,
        ),

        forgeFetch<
          ForgeExecutionEvent[]
        >(
          `/api/workflows/${workflowId}/events`,
        ),
      ]);

    const task =
      workflow.tasks.find(
        (candidate) =>
          candidate.key ===
          taskKey,
      );

    if (task) {
      const running =
        [...events]
          .reverse()
          .find(
            (event) =>
              event.taskId ===
                task.taskId &&
              event.type ===
                "ATTEMPT_RUNNING" &&
              Boolean(
                event.workerId,
              ),
          );

      if (
        running?.workerId &&
        running.attemptId
      ) {
        return {
          workerId:
            running.workerId,

          attemptId:
            running.attemptId,
        };
      }
    }

    await sleep(250);
  }

  throw new Error(
    `Timed out waiting for ${taskKey}`,
  );
}

export async function waitForSharedMarker(
  file: string,
  timeoutMs = 10000,
) {
  const {
    access,
  } =
    await import(
      "node:fs/promises"
    );

  const deadline =
    Date.now() +
    timeoutMs;

  while (
    Date.now() <
    deadline
  ) {
    try {
      await access(file);

      return;
    } catch {
      await new Promise(
        (resolve) =>
          setTimeout(
            resolve,
            100,
          ),
      );
    }
  }

  throw new Error(
    `Timed out waiting for execution marker: ${file}`,
  );
}
