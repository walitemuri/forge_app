import {
  NextResponse,
} from "next/server";

import {
  forgeFetch,
} from "@/lib/forge";

import {
  shell,
  waitForRunningAttempt,
  waitForSharedMarker,
} from "@/lib/reliability-lab";

import type {
  CreatedWorkflow,
  LabWorkflow,
} from "@/lib/reliability-lab";


export const runtime =
  "nodejs";


export async function POST() {
  if (process.env.FORGE_PUBLIC_DEMO === "true") {
    return NextResponse.json({ error: "Infrastructure controls are private in the public demo" }, { status: 403 });
  }

  if (
    process.env
      .FORGE_CHAOS_ENABLED !==
    "true"
  ) {
    return NextResponse.json(
      {
        error:
          "Reliability Lab disabled",
      },
      {
        status: 403,
      },
    );
  }


  const chaosUrl =
    process.env
      .FORGE_CHAOS_URL;

  if (!chaosUrl) {
    return NextResponse.json(
      {
        error:
          "Chaos agent unavailable",
      },
      {
        status: 503,
      },
    );
  }


  const runId =
    crypto.randomUUID()
      .replaceAll("-", "")
      .slice(0, 8);


  const marker =
    `/workspace/shared/worker-loss-${runId}`;

  const armed =
    `${marker}.armed`;


  const workflow:
    LabWorkflow = {
      name:
        `worker-loss-recovery-${runId}`,

      tasks: [
        shell(
          "prepare",
          `
set -euo pipefail

rm -f \
  "${marker}" \
  "${armed}"

echo "================================"
echo " Forge Worker Loss Recovery"
echo "================================"
echo
echo "Preparing real failure injection"
echo "Worker: $(hostname)"
`,
          [],
        ),


        shell(
          "resilient-task",
          `
set -euo pipefail

MARKER="${marker}"
ARMED="${armed}"

echo "================================"
echo " Resilient Task"
echo "================================"
echo
echo "Worker: $(hostname)"


# ------------------------------------------
# RETRY PATH
# ------------------------------------------

if [ -f "$MARKER" ]; then
    ORIGINAL="$(
        cat "$MARKER"
    )"

    echo
    echo "Recovery attempt detected"
    echo "Original worker: $ORIGINAL"
    echo "Recovery worker: $(hostname)"
    echo

    sleep 3

    echo "Recovered execution succeeded"

    exit 0
fi


# ------------------------------------------
# FIRST ATTEMPT
# ------------------------------------------

echo "$(hostname)" \
  > "$MARKER"

touch "$ARMED"

echo
echo "Execution armed for failure injection"
echo "Waiting for infrastructure failure..."
echo

sleep 60

echo "Original execution unexpectedly survived"
`,
          [
            "prepare",
          ],
          {
            maxAttempts:
              2,

            timeoutSeconds:
              120,
          },
        ),


        shell(
          "parallel-check",
          `
set -euo pipefail

echo "Independent branch"
echo "Worker: $(hostname)"

sleep 3

echo "Independent branch succeeded"
`,
          [
            "prepare",
          ],
        ),


        shell(
          "release-gate",
          `
set -euo pipefail

rm -f \
  "${marker}" \
  "${armed}"

echo "================================"
echo " Worker Recovery Complete"
echo "================================"
echo
echo "Original attempt: LOST"
echo "Replacement attempt: SUCCEEDED"
echo "Downstream dependencies released"
echo
echo "Final worker: $(hostname)"
`,
          [
            "resilient-task",
            "parallel-check",
          ],
        ),
      ],
    };


  const created =
    await forgeFetch<
      CreatedWorkflow
    >(
      "/api/workflows",
      {
        method:
          "POST",

        body:
          JSON.stringify(
            workflow,
          ),
      },
    );


  try {
    const running =
      await waitForRunningAttempt(
        created.id,
        "resilient-task",
        20000,
      );


    // ATTEMPT_RUNNING means the worker accepted
    // execution. The marker proves the actual
    // child process has started.
    await waitForSharedMarker(
      armed,
      10000,
    );


    const response =
      await fetch(
        `${chaosUrl}/v1/kill-worker`,
        {
          method:
            "POST",

          headers: {
            "Content-Type":
              "application/json",
          },

          body:
            JSON.stringify({
              workerId:
                running.workerId,
            }),
        },
      );


    if (!response.ok) {
      const text =
        await response.text();

      throw new Error(
        `Failure injection failed: ${text}`,
      );
    }


    return NextResponse.json({
      id:
        created.id,

      name:
        workflow.name,

      killedWorker:
        running.workerId,

      attemptId:
        running.attemptId,
    });


  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Worker loss demo failed",
      },
      {
        status:
          500,
      },
    );
  }
}
