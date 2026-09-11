// Inside the dashboard image: node /tmp/public_demo_smoke_test.mjs
// Starts one temporary server in public mode and verifies its HTTP boundary.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";

const server = spawn(process.execPath, ["server.js"], {
  stdio: "inherit",
  env: { ...process.env, HOSTNAME: "127.0.0.1", PORT: "3000", FORGE_PUBLIC_DEMO: "true", FORGE_PUBLIC_ORIGIN: "http://127.0.0.1:3000", FORGE_CHAOS_ENABLED: "true" },
});
const base = "http://127.0.0.1:3000";
const post = (path, body = {}, headers = {}) => fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });
try {
  let ready = false;
  for (let n = 0; n < 60; n++) {
    try { if ((await fetch(`${base}/templates`)).ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.ok(ready, "Dashboard did not start");
  const page = await (await fetch(`${base}/templates`)).text();
  assert.equal((page.match(/<article /g) ?? []).length, 10);
  assert.ok(page.includes("shared live demo"));
  assert.equal((await fetch(`${base}/reliability`)).status, 404);
  for (const demo of ["worker-loss", "controller-restart"]) {
    assert.equal((await post(`/api/forge/reliability/${demo}`)).status, 403);
  }
  assert.equal((await post("/api/workflows", { command: "arbitrary" })).status, 404);
  assert.equal((await post("/api/forge/workflows", { command: "arbitrary" })).status, 405);
  assert.equal((await post("/api/forge/templates", { template: "worker-load", command: "arbitrary" })).status, 400);
  assert.equal((await post("/api/forge/templates", { template: "worker-load" }, { Origin: "https://other.example" })).status, 403);
  const launch = await post("/api/forge/templates", { template: "parallel-fan-out" });
  assert.ok([201, 429].includes(launch.status), `Unexpected launch status: ${launch.status} ${await launch.clone().text()}`);
  const repeated = await post("/api/forge/templates", { template: "parallel-fan-out" });
  assert.equal(repeated.status, 429, "Repeated launch should hit the persisted public budget");
  assert.ok(repeated.headers.get("retry-after"));
  console.log(`PASS public demo: 10 cards, private chaos controls, fixed-template-only mutation, cross-site rejection, persisted launch limits (first launch ${launch.status})`);
} finally {
  server.kill("SIGTERM");
}
