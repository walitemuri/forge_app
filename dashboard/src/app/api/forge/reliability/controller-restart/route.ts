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
    process.env.FORGE_CHAOS_ENABLED !==
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
    process.env.FORGE_CHAOS_URL;

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
    `/workspace/shared/controller-restart-${runId}`;

  const armed =
    `${marker}.armed`;


  const workflow:
    LabWorkflow = {
      name:
        `controller-crash-recovery-${runId}`,

      tasks: [
        shell(
          "prepare",
          `
set -euo pipefail

rm -f \
  "${marker}" \
  "${armed}"

echo "================================"
echo " Controller Crash Recovery"
echo "================================"
echo
echo "Preparing control-plane failure"
echo "Worker: $(hostname)"
`,
          [],
        ),


        shell(
          "resilient-execution",
          `
set -euo pipefail

MARKER="${marker}"
ARMED="${armed}"

echo "================================"
echo " Resilient Execution"
echo "================================"
echo
echo "Worker: $(hostname)"


# ==========================================
# RETRY PATH
# ==========================================

if [ -f "$MARKER" ]; then
    ORIGINAL="$(
        cat "$MARKER"
    )"

    echo
    echo "Controller-restart retry detected"
    echo "Original attempt worker: $ORIGINAL"
    echo "Recovery attempt worker: $(hostname)"
    echo

    sleep 2

    echo "Recovered execution succeeded"

    exit 0
fi


# ==========================================
# FIRST ATTEMPT
# ==========================================

echo "$(hostname)" \
  > "$MARKER"

touch "$ARMED"

echo
echo "Execution is active"
echo "Control plane will now be terminated"
echo
echo "Worker continues running independently..."

# Stay alive while the controller is killed.
sleep 3

echo
echo "First execution completed while controller was unavailable"
echo "Result delivery may race controller recovery"
`,
          [
            "prepare",
          ],
          {
            maxAttempts:
              2,

            timeoutSeconds:
              90,
          },
        ),


        shell(
          "release-gate",
          `
set -euo pipefail

rm -f \
  "${marker}" \
  "${armed}"

echo "================================"
echo " Controller Recovery Complete"
echo "================================"
echo
echo "Control plane restarted"
echo "Interrupted attempt was reconciled"
echo "Replacement attempt succeeded"
echo "Downstream dependency released"
echo
echo "Final worker: $(hostname)"
`,
          [
            "resilient-execution",
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
        "resilient-execution",
        20000,
      );


    // Do not kill the controller merely because
    // the worker acknowledged the assignment.
    //
    // This marker proves that the actual child
    // process is executing.
    await waitForSharedMarker(
      armed,
      10000,
    );


    const response =
      await fetch(
        `${chaosUrl}/v1/restart-controller`,
        {
          method:
            "POST",

          headers: {
            "Content-Type":
              "application/json",
          },

          body:
            "{}",
        },
      );


    if (!response.ok) {
      const text =
        await response.text();

      throw new Error(
        `Controller recovery failed: ${text}`,
      );
    }


    return NextResponse.json({
      id:
        created.id,

      name:
        workflow.name,

      originalWorker:
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
            : "Controller restart demo failed",
      },
      {
        status: 500,
      },
    );
  }
}
