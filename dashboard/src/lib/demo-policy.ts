import type { ForgeWorkflow } from "./types";

export class DemoLimitError extends Error {
  retryAfter: number;
  constructor(message: string, retryAfter = 30) {
    super(message);
    this.retryAfter = retryAfter;
  }
}

// Global budgets do not trust visitor-supplied IP headers. Persisted controller
// history keeps the budgets across dashboard and controller restarts.
export function checkDemoBudget(workflows: ForgeWorkflow[], now = Date.now()) {
  if (workflows.length >= 500) {
    throw new DemoLimitError("The demo archive is full. You can still explore existing runs.", 86400);
  }
  const today = workflows.filter(workflow => now - Date.parse(workflow.createdAt) < 86400000);
  if (today.length >= 40) {
    throw new DemoLimitError("Today's demo budget is used. Explore existing runs or return tomorrow.", 3600);
  }
  const latest = Math.max(0, ...workflows.map(workflow => Date.parse(workflow.createdAt)));
  if (now - latest < 20000) {
    throw new DemoLimitError("Another demo just launched. Please try again in a few seconds.", Math.max(1, Math.ceil((20000 - now + latest) / 1000)));
  }
}

export function validateLaunchBody(body: unknown): body is { template: string } {
  return typeof body === "object" && body !== null && !Array.isArray(body)
    && Object.keys(body).length === 1 && "template" in body && typeof body.template === "string";
}
