import { NextRequest, NextResponse } from "next/server";
import { forgeFetch } from "@/lib/forge";
import { buildTemplateWorkflow } from "@/lib/template-workflows";
import { isTemplateKey } from "@/lib/workflow-templates";
import { checkDemoBudget, DemoLimitError, validateLaunchBody } from "@/lib/demo-policy";
import type { ForgeWorker, ForgeWorkflow, ForgeWorkflowDetail } from "@/lib/types";

export const runtime = "nodejs";

// One dashboard process is supported. Hold the lock across admission and
// controller submission so simultaneous visitors cannot overbook the demo.
let submitting = false;

export async function POST(request: NextRequest) {
  if (process.env.FORGE_DEMO_LAUNCH_ENABLED === "false") {
    return NextResponse.json({ error: "Demo launches are paused. You can explore existing runs." }, { status: 503 });
  }
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    return NextResponse.json({ error: "Expected application/json" }, { status: 415 });
  }
  const origin = request.headers.get("origin");
  // Standalone Next uses its bind address in nextUrl (often 0.0.0.0).
  // Local browsers send the actual Host; public mode pins an explicit origin.
  const expectedOrigin = process.env.FORGE_PUBLIC_ORIGIN
    || `${request.nextUrl.protocol}//${request.headers.get("host") ?? request.nextUrl.host}`;
  if (request.headers.get("sec-fetch-site") === "cross-site" || (origin && origin !== expectedOrigin)) {
    return NextResponse.json({ error: "Cross-site launches are not allowed" }, { status: 403 });
  }
  let body: unknown;
  try {
    const reader = request.body?.getReader();
    if (!reader) throw new Error("Missing body");
    let length = 0;
    const chunks: Uint8Array[] = [];
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > 1024) {
        await reader.cancel();
        return NextResponse.json({ error: "Request body too large" }, { status: 413 });
      }
      chunks.push(value);
    }
    body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  }
  if (!validateLaunchBody(body) || !isTemplateKey(body.template)) {
    return NextResponse.json({ error: "Choose a known workflow template; custom commands are not accepted." }, { status: 400 });
  }
  if (submitting) {
    return NextResponse.json({ error: "Another launch is being submitted. Please try again." }, { status: 429, headers: { "Retry-After": "5" } });
  }
  submitting = true;
  try {
    const workers = await forgeFetch<ForgeWorker[]>("/api/workers");
    if (!workers.some(worker => worker.online && worker.commandStreamConnected)) {
      return NextResponse.json({ error: "Workers are reconnecting. Please try again shortly." }, { status: 503 });
    }
    if (process.env.FORGE_PUBLIC_DEMO === "true") {
      const workflows = await forgeFetch<ForgeWorkflow[]>("/api/workflows");
      checkDemoBudget(workflows);
      // FAILED can still have running branches or blocked retry descendants.
      let active = 0;
      for (const workflow of workflows) {
        if (["SUCCEEDED", "CANCELLED"].includes(workflow.status)) continue;
        const detail = await forgeFetch<ForgeWorkflowDetail>(`/api/workflows/${workflow.id}`);
        if (detail.tasks.some(task => ["CREATED", "BLOCKED", "PENDING", "DISPATCHED", "RUNNING"].includes(task.status))) active++;
        if (active >= 3) throw new DemoLimitError("The demo is busy with three workflows. Explore a live run and try again shortly.");
      }
    }
    const runId = crypto.randomUUID().replaceAll("-", "").slice(0, 8);
    const workflow = buildTemplateWorkflow(body.template, runId);
    const result = await forgeFetch<{ id: string }>("/api/workflows", { method: "POST", body: JSON.stringify(workflow) });
    return NextResponse.json({ id: result.id, name: workflow.name }, { status: 201 });
  } catch (error) {
    if (error instanceof DemoLimitError) {
      return NextResponse.json({ error: error.message }, { status: 429, headers: { "Retry-After": String(error.retryAfter) } });
    }
    console.error("Failed to launch workflow template:", error);
    return NextResponse.json({ error: "Forge controller could not launch the workflow" }, { status: 503 });
  } finally {
    submitting = false;
  }
}
