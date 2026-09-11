// PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs node scripts/dashboard_video_demo.mjs
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? "playwright");
const dashboard = process.env.FORGE_DASHBOARD_URL ?? "http://127.0.0.1:3000";
const controller = process.env.FORGE_API_URL ?? "http://127.0.0.1:8080";
const artifacts = process.env.FORGE_EVIDENCE_DIR ?? "demo/video/docker-dashboard-evidence";
await mkdir(artifacts, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${dashboard}/templates`);
  assert.equal(await page.locator("article").count(), 10);
  await page.screenshot({ path: `${artifacts}/templates-desktop.png`, fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), "Mobile layout must not overflow");
  await page.screenshot({ path: `${artifacts}/templates-mobile.png`, fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  const card = page.locator("article").filter({ has: page.getByRole("heading", { name: "Distributed Video Processing", exact: true }) });
  await card.getByRole("button", { name: "Launch Distributed Video Processing" }).click();
  await page.waitForURL("**/workflows/*");
  const url = page.url();
  const id = url.split("/").at(-1);
  console.log(`Launched through dashboard: ${url}`);
  await page.screenshot({ path: `${artifacts}/running.png` });
  let workflow;
  const deadline = Date.now() + 900000;
  while (Date.now() < deadline) {
    const response = await fetch(`${controller}/api/workflows/${id}`);
    assert.ok(response.ok);
    workflow = await response.json();
    if (workflow.tasks.every(task => ["SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"].includes(task.status))) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(workflow.status, "SUCCEEDED", JSON.stringify(workflow));
  assert.equal(workflow.tasks.length, 5);
  assert.ok(workflow.tasks.every(task => task.status === "SUCCEEDED"));
  await page.reload();
  await page.waitForFunction(() => document.querySelector("video")?.readyState >= 1);
  const video = page.locator("video");
  const metadata = await video.evaluate(element => ({ duration: element.duration, width: element.videoWidth, height: element.videoHeight }));
  assert.ok(Math.abs(metadata.duration - 30) < 0.5);
  assert.equal(metadata.width, 1280);
  assert.equal(metadata.height, 720);
  const centering = await video.evaluate(element => {
    const player = element.getBoundingClientRect();
    const section = element.closest("section").getBoundingClientRect();
    return Math.abs(player.x + player.width / 2 - section.x - section.width / 2);
  });
  assert.ok(centering < 2, "Video player must be centered in its section");
  await video.evaluate(async element => { element.muted = true; await element.play(); });
  await page.waitForFunction(() => document.querySelector("video").currentTime > 0.3);
  await video.evaluate(element => { element.pause(); element.currentTime = 15; });
  await page.waitForFunction(() => { const v = document.querySelector("video"); return !v.seeking && v.currentTime >= 15; });
  const mediaUrl = await video.getAttribute("src");
  for (const [range, expectedStatus, length] of [["bytes=0-1023", 206, 1024], ["bytes=-512", 206, 512], ["bytes=999999999-", 416, null], ["bytes=-0", 416, null]]) {
    const response = await fetch(`${dashboard}${mediaUrl}`, { headers: { Range: range } });
    assert.equal(response.status, expectedStatus, range);
    if (length) assert.equal((await response.arrayBuffer()).byteLength, length);
  }
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download MP4" }).click();
  const download = await downloadPromise;
  assert.match(download.suggestedFilename(), /^forge-[a-f0-9]{8}\.mp4$/);
  await download.saveAs(`${artifacts}/forge-output.mp4`);
  await page.screenshot({ path: `${artifacts}/completed.png` });
  assert.deepEqual(errors, []);
  const events = await (await fetch(`${controller}/api/workflows/${id}/events`)).json();
  const workers = [...new Set(events.map(event => event.workerId).filter(Boolean))];
  const evidence = { url, workflow, workers, metadata, checks: ["UI launch", "10 template cards", "mobile layout", "5 successful tasks", "video playback", "seek to 15 seconds", "byte ranges", "download", "no browser errors"] };
  await writeFile(`${artifacts}/result.json`, JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify({ url, status: workflow.status, workers, metadata, checks: evidence.checks }, null, 2));
} finally {
  await browser.close();
}
