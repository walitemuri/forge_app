import path from "node:path";

import { NextRequest, NextResponse } from "next/server";

import { forgeFetch } from "@/lib/forge";

type TemplateKey =
  | "self-verification"
  | "failure-retry";

interface WorkflowCreateResponse {
  id: string;
}

interface WorkflowTaskRequest {
  key: string;
  command: string;
  arguments: string[];
  maxAttempts: number;
  timeoutSeconds: number;
  dependsOn: string[];
}

interface WorkflowRequest {
  name: string;
  tasks: WorkflowTaskRequest[];
}

function shellQuote(value: string) {
  return (
    "'" +
    value.replaceAll(
      "'",
      "'\"'\"'",
    ) +
    "'"
  );
}

function shell(
  key: string,
  command: string,
  dependsOn: string[],
  options?: {
    maxAttempts?: number;
    timeoutSeconds?: number;
  },
): WorkflowTaskRequest {
  return {
    key,
    command: "bash",
    arguments: [
      "-lc",
      command,
    ],
    maxAttempts:
      options?.maxAttempts ?? 1,
    timeoutSeconds:
      options?.timeoutSeconds ?? 180,
    dependsOn,
  };
}

function selfVerificationWorkflow(
  runId: string,
): WorkflowRequest {
  /*
   * The dashboard currently runs from:
   *
   *   <repo>/dashboard
   *
   * This local demo therefore resolves the Forge
   * repository as its parent directory.
   *
   * Remote workers will eventually need explicit
   * workspace/source distribution instead of this
   * shared-filesystem assumption.
   */
  const repoRoot =
    path.resolve(
      process.cwd(),
      "..",
    );

  const repo =
    shellQuote(repoRoot);

  return {
    name:
      `forge-self-verification-${runId}`,

    tasks: [
      shell(
        "source-check",
        `
set -euo pipefail

echo "=== Forge source ==="

git -C ${repo} rev-parse --short HEAD
git -C ${repo} diff --check

echo "Source tree validated"
`,
        [],
      ),

      shell(
        "controller-build",
        `
set -euo pipefail

echo "=== Java controller ==="

cd ${repo}/controller

./gradlew classes --no-daemon

echo "Controller compilation passed"
`,
        [
          "source-check",
        ],
      ),

      shell(
        "worker-build",
        `
set -euo pipefail

echo "=== C++ worker ==="

cmake --build ${repo}/worker/build \
  --parallel 2

test -x \
  ${repo}/worker/build/forge-worker

echo "Worker compilation passed"
`,
        [
          "source-check",
        ],
      ),

      shell(
        "python-checks",
        `
set -euo pipefail

echo "=== Python tooling ==="

cd ${repo}

python3 -m compileall \
  -q \
  scripts

echo "Python tooling validated"
`,
        [
          "source-check",
        ],
      ),

      shell(
        "release-gate",
        `
set -euo pipefail

echo "=============================="
echo " Forge verification complete "
echo "=============================="
`,
        [
          "controller-build",
          "worker-build",
          "python-checks",
        ],
      ),
    ],
  };
}

function failureRetryWorkflow(
  runId: string,
): WorkflowRequest {
  const repoRoot =
    path.resolve(
      process.cwd(),
      "..",
    );

  const repo =
    shellQuote(repoRoot);

  const marker =
    shellQuote(
      `/tmp/forge-retry-demo-${runId}`,
    );

  return {
    name:
      `failure-recovery-demo-${runId}`,

    tasks: [
      shell(
        "prepare",
        `
set -euo pipefail

rm -f ${marker}

echo "Preparing recovery demonstration"

git -C ${repo} \
  rev-parse \
  --short \
  HEAD

echo "Preparation complete"
`,
        [],
      ),

      shell(
        "retrying-tests",
        `
set -euo pipefail

echo "Running simulated unstable test"

if [ ! -f ${marker} ]; then
    echo "First attempt: injecting failure"

    touch ${marker}

    exit 42
fi

echo "Retry detected"
echo "Tests recovered successfully"
`,
        [
          "prepare",
        ],
        {
          maxAttempts: 2,
        },
      ),

      shell(
        "static-analysis",
        `
set -euo pipefail

cd ${repo}

python3 -m compileall \
  -q \
  scripts

echo "Static analysis passed"
`,
        [
          "prepare",
        ],
      ),

      shell(
        "package",
        `
set -euo pipefail

echo "All required checks passed"
echo "Package gate succeeded"
`,
        [
          "retrying-tests",
          "static-analysis",
        ],
      ),
    ],
  };
}

export async function POST(
  request: NextRequest,
) {
  let body: {
    template?: string;
  };

  try {
    body = await request.json();
  } catch {
    return NextResponse.json(
      {
        error:
          "Invalid request body",
      },
      {
        status: 400,
      },
    );
  }

  const template =
    body.template;

  if (
    template !==
      "self-verification" &&
    template !==
      "failure-retry"
  ) {
    return NextResponse.json(
      {
        error:
          "Unknown workflow template",
      },
      {
        status: 400,
      },
    );
  }

  const runId =
    crypto.randomUUID()
      .replaceAll("-", "")
      .slice(0, 8);

  const workflow =
    template ===
    "self-verification"
      ? selfVerificationWorkflow(
          runId,
        )
      : failureRetryWorkflow(
          runId,
        );

  try {
    const result =
      await forgeFetch<
        WorkflowCreateResponse
      >(
        "/api/workflows",
        {
          method: "POST",
          body:
            JSON.stringify(
              workflow,
            ),
        },
      );

    return NextResponse.json({
      id: result.id,
      name: workflow.name,
    });
  } catch (error) {
    console.error(
      "Failed to launch workflow template:",
      error,
    );

    return NextResponse.json(
      {
        error:
          "Forge controller could not launch the workflow",
      },
      {
        status: 503,
      },
    );
  }
}
