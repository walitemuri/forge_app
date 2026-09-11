import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { runInThisContext } from "node:vm";
import test from "node:test";
import ts from "typescript";

const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, "../src");
// Compile the real server modules in memory, substituting only I/O boundaries.
function load(relative, mocks = {}, cache = new Map()) {
  const file = path.resolve(root, relative.endsWith(".ts") ? relative : `${relative}.ts`);
  if (cache.has(file)) return cache.get(file).exports;
  const loaded = { exports: {} };
  cache.set(file, loaded);
  const code = ts.transpileModule(readFileSync(file, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const localRequire = id => {
    if (id in mocks) return mocks[id];
    if (id.startsWith("@/")) return load(id.slice(2), mocks, cache);
    if (id.startsWith(".")) return load(path.relative(root, path.resolve(path.dirname(file), id)), mocks, cache);
    return require(id);
  };
  runInThisContext(`(function(require,module,exports){${code}\n})`, { filename: file })(localRequire, loaded, loaded.exports);
  return loaded.exports;
}

const { workflowTemplates, isTemplateKey } = load("lib/workflow-templates");
const { buildTemplateWorkflow } = load("lib/template-workflows");
const { checkDemoBudget, validateLaunchBody } = load("lib/demo-policy");

for (const template of workflowTemplates) {
  test(`${template.key}: bounded, connected, acyclic DAG matches catalog`, () => {
    const workflow = buildTemplateWorkflow(template.key, "deadbeef");
    assert.equal(workflow.tasks.length, template.taskCount);
    const tasks = new Map(workflow.tasks.map(task => [task.key, task]));
    assert.equal(tasks.size, workflow.tasks.length);
    const visited = new Set();
    function visit(key, ancestors = new Set()) {
      assert.ok(tasks.has(key), `Missing dependency ${key}`);
      assert.ok(!ancestors.has(key), `Cycle at ${key}`);
      if (visited.has(key)) return;
      const task = tasks.get(key);
      assert.ok(task.timeoutSeconds > 0 && task.timeoutSeconds <= 600);
      assert.ok(task.maxAttempts >= 1 && task.maxAttempts <= 3);
      assert.ok(["python3", "bash"].includes(task.command));
      for (const dep of task.dependsOn) visit(dep, new Set([...ancestors, key]));
      visited.add(key);
    }
    for (const key of tasks.keys()) visit(key);
    assert.equal(visited.size, tasks.size);
    const commands = JSON.stringify(workflow);
    assert.ok(!commands.includes("/home/"));
    assert.ok(!commands.includes("docker.sock"));
    assert.ok(!commands.includes("/tmp/forge-showcase-retry"));
  });
}

test("template allowlist rejects arbitrary commands and unsafe identifiers", () => {
  for (const value of [null, [], {}, "toString", "__proto__", "unknown", 123]) assert.equal(isTemplateKey(value), false);
  for (const value of [null, [], {}, { template: 123 }, { template: "worker-load", command: "bad" }]) assert.equal(validateLaunchBody(value), false);
  assert.throws(() => buildTemplateWorkflow("failure-retry", "$(id)"));
});

test("public budgets use persisted history and enforce cooldown, day and archive limits", () => {
  const now = Date.now();
  const run = age => ({ createdAt: new Date(now - age).toISOString() });
  assert.doesNotThrow(() => checkDemoBudget([], now));
  assert.throws(() => checkDemoBudget([run(1000)], now), /just launched/);
  assert.doesNotThrow(() => checkDemoBudget([run(20000)], now));
  assert.throws(() => checkDemoBudget(Array(40).fill(run(60000)), now), /budget/);
  assert.throws(() => checkDemoBudget(Array(500).fill(run(86400001)), now), /archive/);
});

function request(body, headers = {}) {
  const req = new Request("https://forge.test/api/forge/templates", { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: typeof body === "string" ? body : JSON.stringify(body) });
  req.nextUrl = new URL(req.url);
  return req;
}
const next = { NextResponse: { json: (body, init) => Response.json(body, init) } };

test("launch route rejects invalid, oversized and cross-site input before contacting controller", async () => {
  const { POST } = load("app/api/forge/templates/route", { "next/server": next, "@/lib/forge": { forgeFetch: () => assert.fail("Unexpected controller access") } });
  for (const body of [null, {}, [], { template: "unknown" }, { template: 123 }, { template: "worker-load", command: "evil" }, "{"]) {
    assert.equal((await POST(request(body))).status, 400);
  }
  assert.equal((await POST(request("x".repeat(1025)))).status, 413);
  assert.equal((await POST(request({}, { Origin: "https://other.test" }))).status, 403);
  assert.equal((await POST(request({}, { "Sec-Fetch-Site": "cross-site" }))).status, 403);
  assert.equal((await POST(request({}, { "Content-Type": "text/plain" }))).status, 415);
});

test("every catalog entry launches through the actual route", async () => {
  const submitted = [];
  const { POST } = load("app/api/forge/templates/route", { "next/server": next, "@/lib/forge": { forgeFetch: async (url, options) => {
    if (url === "/api/workers") return [{ online: true, commandStreamConnected: true }];
    assert.equal(url, "/api/workflows");
    submitted.push(JSON.parse(options.body));
    return { id: "workflow-id" };
  } } });
  for (const template of workflowTemplates) {
    assert.equal((await POST(request({ template: template.key }))).status, 201);
    assert.equal(submitted.at(-1).tasks.length, template.taskCount);
  }
});

test("simultaneous submissions cannot bypass admission; failures release the lock", async () => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const { POST } = load("app/api/forge/templates/route", { "next/server": next, "@/lib/forge": { forgeFetch: async () => { await gate; return []; } } });
  const first = POST(request({ template: "worker-load" }));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal((await POST(request({ template: "worker-load" }))).status, 429);
  release();
  assert.equal((await first).status, 503);
  assert.equal((await POST(request({ template: "worker-load" }))).status, 503);
});

test("local Docker origin uses the browser Host rather than the server bind address", async () => {
  const { POST } = load("app/api/forge/templates/route", { "next/server": next, "@/lib/forge": { forgeFetch: async () => [] } });
  const req = request({ template: "worker-load" }, { Host: "localhost:3000", Origin: "http://localhost:3000" });
  req.nextUrl = new URL("http://0.0.0.0:3000/api/forge/templates");
  assert.equal((await POST(req)).status, 503, "Origin should pass admission and reach worker availability check");
});

test("public admission counts running tasks even in FAILED workflows and disables chaos", async () => {
  process.env.FORGE_PUBLIC_DEMO = "true";
  try {
    const { POST } = load("app/api/forge/templates/route", { "next/server": next, "@/lib/forge": { forgeFetch: async url => {
      if (url === "/api/workers") return [{ online: true, commandStreamConnected: true }];
      if (url === "/api/workflows") return Array.from({ length: 3 }, (_, i) => ({ id: String(i), status: "FAILED", createdAt: new Date(Date.now() - 60000).toISOString() }));
      return { tasks: [{ status: "BLOCKED" }] };
    } } });
    const response = await POST(request({ template: "worker-load" }));
    assert.equal(response.status, 429);
    assert.ok(response.headers.get("retry-after"));
    for (const route of ["worker-loss", "controller-restart"]) {
      const { POST: chaos } = load(`app/api/forge/reliability/${route}/route`, { "next/server": next });
      assert.equal((await chaos()).status, 403);
    }
  } finally { delete process.env.FORGE_PUBLIC_DEMO; }
});
