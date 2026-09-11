// Run with the dashboard, controller, and Python-capable workers online:
// node scripts/dashboard_templates_smoke_test.mjs
// Optional: pass a template key to run just that case.
// Creates bounded demo workflows and retains their execution history.
import assert from "node:assert/strict";

const dashboard = process.env.FORGE_DASHBOARD_URL ?? "http://localhost:3000";
const controller = process.env.FORGE_API_URL ?? "http://localhost:8080";
const cases = [
  ["video-processing", { "inspect-video": "SUCCEEDED", "transcode-000": "SUCCEEDED", "transcode-001": "SUCCEEDED", "transcode-002": "SUCCEEDED", "merge-video": "SUCCEEDED" }],
  ["self-verification", { "source-check": "SUCCEEDED", "controller-build": "SUCCEEDED", "worker-build": "SUCCEEDED", "python-checks": "SUCCEEDED", "release-gate": "SUCCEEDED" }],
  ["failure-retry", { prepare: "SUCCEEDED", "unstable-task": "SUCCEEDED", "parallel-check": "SUCCEEDED", release: "SUCCEEDED" }],
  ["orchestration-showcase", {
    ...Object.fromEntries([
      "prepare", "ingest-orders", "ingest-events", "ingest-catalog",
      "normalize-orders", "normalize-events", "normalize-catalog", "join-data",
      "test-shard-1", "test-shard-2", "test-shard-3", "test-shard-4",
      "flaky-integration", "quality-gate", "package", "publish-primary",
      "publish-secondary", "release-complete", "audit-inputs", "audit-report",
    ].map(key => [key, "SUCCEEDED"])),
    "experimental-check": "FAILED", "experimental-package": "SKIPPED", "experimental-publish": "SKIPPED",
  }],
  ["parallel-fan-out", { prepare: "SUCCEEDED", parse: "SUCCEEDED", transform: "SUCCEEDED", validate: "SUCCEEDED", publish: "SUCCEEDED" }],
  ["fan-in-barrier", { "fast-input": "SUCCEEDED", "medium-input": "SUCCEEDED", "slow-input": "SUCCEEDED", join: "SUCCEEDED" }],
  ["failure-isolation", { build: "SUCCEEDED", "quality-check": "FAILED", release: "SKIPPED", "independent-analysis": "SUCCEEDED", "analysis-report": "SUCCEEDED" }],
  ["cascading-skip", { "source-check": "FAILED", compile: "SKIPPED", package: "SKIPPED", deploy: "SKIPPED" }],
  ["retry-exhaustion", { prepare: "SUCCEEDED", "unavailable-service": "FAILED", publish: "SKIPPED" }],
  ["worker-load", Object.fromEntries(["prepare", ...Array.from({ length: 8 }, (_, i) => `job-${i + 1}`), "completion-gate"].map(key => [key, "SUCCEEDED"]))],
];

async function json(url, options) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15000) });
  assert.ok(response.ok, `${url}: ${response.status} ${await response.clone().text()}`);
  return response.json();
}

const workers = await json(`${controller}/api/workers`);
assert.ok(workers.some(worker => worker.online && worker.commandStreamConnected), "Need an online worker with a command stream");

for (const body of [null, {}, { template: "unknown" }, { template: 123 }]) {
  const response = await fetch(`${dashboard}/api/forge/templates`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  assert.equal(response.status, 400, `Invalid request accepted: ${JSON.stringify(body)}`);
}

const runs = [];
const selectedCases = process.argv[2] ? cases.filter(([key]) => key === process.argv[2]) : cases;
assert.ok(selectedCases.length, `Unknown template: ${process.argv[2]}`);
for (const [key, expected] of selectedCases) {
  const run = await json(`${dashboard}/api/forge/templates`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ template: key }) });
  runs.push({ key, expected, id: run.id, name: run.name });
  console.log(`Launched ${key}: ${run.id}`);
}

for (const run of runs) {
  const deadline = Date.now() + 660000;
  let workflow;
  while (Date.now() < deadline) {
    workflow = await json(`${controller}/api/workflows/${run.id}`);
    // A workflow can report FAILED while independent branches still execute,
    // or a failed task is waiting for its next automatic attempt.
    if (workflow.tasks.every(task => task.status === run.expected[task.key])) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.deepEqual(Object.fromEntries(workflow.tasks.map(task => [task.key, task.status])), run.expected, run.key);
  const expectedWorkflowStatus = Object.values(run.expected).includes("FAILED") ? "FAILED" : "SUCCEEDED";
  assert.equal(workflow.status, expectedWorkflowStatus, run.key);

  const attempts = new Map();
  for (const task of workflow.tasks) {
    const history = await json(`${controller}/api/tasks/${task.taskId}/attempts`);
    attempts.set(task.key, history);
    if (task.status === "SKIPPED") assert.equal(history.length, 0, `${task.key} must never execute`);
  }
  if (run.key === "failure-retry") {
    const recovery = attempts.get("unstable-task");
    assert.equal(recovery.length, 2);
    assert.equal(recovery.filter(attempt => attempt.status === "FAILED").length, 1);
    assert.equal(recovery.filter(attempt => attempt.status === "SUCCEEDED").length, 1);
  }
  if (run.key === "video-processing") {
    const artifact = `${dashboard}/api/forge/artifacts/video/${run.name.slice(-8)}`;
    const partial = await fetch(artifact, { headers: { Range: "bytes=0-31" } });
    assert.equal(partial.status, 206);
    const bytes = Buffer.from(await partial.arrayBuffer());
    assert.equal(bytes.length, 32);
    assert.equal(bytes.toString("ascii", 4, 8), "ftyp", "Expected an MP4 container");
    const invalid = await fetch(artifact, { headers: { Range: "bytes=-" } });
    assert.equal(invalid.status, 416);
    const download = await fetch(`${artifact}?download=1`, { method: "HEAD" });
    assert.equal(download.status, 200);
    assert.match(download.headers.get("content-disposition"), /attachment/);
  }
  if (run.key === "retry-exhaustion") {
    const history = attempts.get("unavailable-service");
    assert.equal(history.length, 3);
    assert.ok(history.every(attempt => attempt.status === "FAILED"));
  }
  if (run.key === "orchestration-showcase") {
    const recovery = attempts.get("flaky-integration");
    assert.equal(recovery.length, 2, "Integration should recover on its second attempt");
    assert.equal(recovery.filter(attempt => attempt.status === "FAILED").length, 1);
    assert.equal(recovery.filter(attempt => attempt.status === "SUCCEEDED").length, 1);
    const exhausted = attempts.get("experimental-check");
    assert.equal(exhausted.length, 3, "Experimental branch should exhaust its retry budget");
    assert.ok(exhausted.every(attempt => attempt.status === "FAILED"));
  }
  console.log(`PASS ${run.key}: ${workflow.status}`);
}
