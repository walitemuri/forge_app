export const workflowTemplates = [
  {
    key: "video-processing", name: "Distributed Video Processing", icon: "video",
    description: "Inspect a 30-second Tears of Steel excerpt, transcode three segments in parallel with FFmpeg, then merge a playable MP4 with audio.",
    features: ["Real film footage", "Three parallel segments", "H.264 output", "Playback & download"],
    taskCount: 5, outcome: "All five tasks succeed and the finished video appears on the workflow page.",
    requirements: "The Docker image includes a 30-second 1280×572 Tears of Steel excerpt with audio. Allow up to two minutes.",
  },
  {
    key: "orchestration-showcase", name: "Full Orchestration Showcase", icon: "branch",
    description: "A 23-task release pipeline: merge three data streams, fan out a test matrix, recover an unstable integration, and publish to two destinations while an experimental branch exhausts its retries.",
    features: ["Nested fan-out / fan-in", "Four test shards", "Retry → recovery", "Retry budget exhaustion", "Cascading skips", "Independent release & audit"],
    taskCount: 23,
    outcome: "The main release and audit succeed. The experimental check fails after three attempts and its two descendants are skipped, so the overall workflow intentionally ends FAILED.",
    requirements: "Uses the shared Docker volume for retry recovery across workers. Allow 1–3 minutes.",
  },
  {
    key: "self-verification", name: "Distributed Build & Verification", icon: "branch",
    description: "Run Forge's Java, C++ and Python verification workload as a distributed DAG.",
    features: ["Parallel execution", "Multi-worker scheduling", "Dependency fan-out", "Join / release gate"],
    taskCount: 5, outcome: "Succeeds when all three verification branches pass.",
    requirements: "Docker workers include the source and build tools. Runs Java unit tests and C++ tests plus Python syntax checks; allow up to ten minutes.",
  },
  {
    key: "failure-retry", name: "Failure & Retry Recovery", icon: "retry",
    description: "Inject a first-attempt failure and watch Forge recover through a durable retry.",
    features: ["Intentional failure", "Retry backoff", "Attempt history", "Dependency recovery"],
    taskCount: 4, outcome: "Succeeds after the unstable task recovers on its second attempt.",
    requirements: "Uses the shared Docker volume so a retry can recover on another worker.",
  },
  {
    key: "parallel-fan-out", name: "Parallel Processing Pipeline", icon: "branch",
    description: "One preparation step releases three independent branches, then a final gate joins their results.",
    features: ["Fan-out", "Parallel branches", "Fan-in gate", "Dependency ordering"],
    taskCount: 5, outcome: "All tasks succeed; publish waits for every processing branch.",
    requirements: "Python 3 on workers. Parallelism depends on available capacity.",
  },
  {
    key: "fan-in-barrier", name: "Wait for Every Parent", icon: "branch",
    description: "Three independent inputs finish at different times. Watch the join stay blocked until the slowest is ready.",
    features: ["Multiple roots", "Staggered completion", "All-parent barrier", "Blocked → pending"],
    taskCount: 4, outcome: "The join succeeds only after all three inputs succeed.",
    requirements: "Python 3 on workers.",
  },
  {
    key: "failure-isolation", name: "Failure Isolation", icon: "failure",
    description: "A failing quality check prevents a shared release gate from running while an independent branch finishes normally.",
    features: ["Mixed parent results", "Skipped release", "Independent progress", "Failure timeline"],
    taskCount: 5, outcome: "Intentionally fails: release is skipped, but the independent branch succeeds.",
    requirements: "Python 3 on workers.",
  },
  {
    key: "cascading-skip", name: "Cascading Dependency Skip", icon: "failure",
    description: "Fail the first stage of a four-stage pipeline and follow the skip through every downstream dependency.",
    features: ["Transitive failure", "Three skipped stages", "No downstream attempts", "Durable event history"],
    taskCount: 4, outcome: "Intentionally fails: the remaining three tasks are skipped without execution.",
    requirements: "Python 3 on workers.",
  },
  {
    key: "retry-exhaustion", name: "Retry Budget Exhaustion", icon: "retry",
    description: "A consistently failing task uses all three attempts before Forge skips its dependent publish step.",
    features: ["Three attempts", "Retry backoff", "Terminal failure", "Skipped dependency"],
    taskCount: 3, outcome: "Intentionally fails after three attempts; publish never runs.",
    requirements: "Python 3 on workers. Allow time for retry backoff.",
  },
  {
    key: "worker-load", name: "Worker Capacity & Load", icon: "capacity",
    description: "Release eight bounded jobs together and watch available worker slots fill, then join at a final completion gate.",
    features: ["Eight runnable jobs", "Capacity-aware dispatch", "Worker telemetry", "Completion barrier"],
    taskCount: 10, outcome: "All tasks succeed. Jobs queue when available slots are fewer than eight.",
    requirements: "Python 3 on workers. Open Workers during the run to watch load.",
  },
] as const;

export type TemplateKey = typeof workflowTemplates[number]["key"];

export function isTemplateKey(value: unknown): value is TemplateKey {
  return typeof value === "string" && workflowTemplates.some(template => template.key === value);
}

export interface WorkflowTaskRequest {
  key: string;
  command: string;
  arguments: string[];
  maxAttempts: number;
  timeoutSeconds: number;
  dependsOn: string[];
}

export interface WorkflowRequest {
  name: string;
  tasks: WorkflowTaskRequest[];
}

function task(key: string, seconds: number, dependsOn: string[] = [], exitCode = 0, maxAttempts = 1): WorkflowTaskRequest {
  return {
    key, command: "python3",
    arguments: ["-u", "-c", [
      "import sys, time",
      `print(${JSON.stringify(`${key}: started`)}, flush=True)`,
      `time.sleep(${seconds})`,
      `print(${JSON.stringify(`${key}: ${exitCode ? "intentional demo failure" : "completed"}`)}, flush=True)`,
      `sys.exit(${exitCode})`,
    ].join("\n")],
    dependsOn, maxAttempts, timeoutSeconds: 30,
  };
}

// Adapted from dag_smoke_test.py (fan-in/out, multi-parent failure,
// transitive skip, live load) and timeline_smoke_test.py (retry history).
// The combined showcase uses a run-specific shared retry marker, matching
// the existing retry demo. No workflow restarts workers or the controller.
export function buildCapabilityWorkflow(key: Exclude<TemplateKey, "self-verification" | "failure-retry" | "video-processing">, runId: string): WorkflowRequest {
  if (!/^[a-f0-9]{8}$/.test(runId)) throw new Error("Invalid template run ID");
  let tasks: WorkflowTaskRequest[];
  switch (key) {
    case "orchestration-showcase": {
      const streams = ["orders", "events", "catalog"];
      const shards = Array.from({ length: 4 }, (_, index) => task(`test-shard-${index + 1}`, 6 + index * 2, ["join-data"]));
      const integration = task("flaky-integration", 2, ["join-data"], 0, 2);
      integration.arguments = ["-u", "-c", [
        "from pathlib import Path",
        "import sys, time",
        `marker = Path(${JSON.stringify(`/workspace/shared/showcase-retry-${runId}`)})`,
        "marker.parent.mkdir(parents=True, exist_ok=True)",
        "time.sleep(2)",
        "try:",
        "    marker.touch(exist_ok=False)",
        "except FileExistsError:",
        "    print('Integration recovered on retry; quality gate may proceed', flush=True)",
        "else:",
        "    print('Injecting a transient integration failure', flush=True)",
        "    sys.exit(42)",
      ].join("\n")];
      tasks = [
        task("prepare", 2),
        ...streams.map((stream, index) => task(`ingest-${stream}`, 3 + index * 2, ["prepare"])),
        ...streams.map(stream => task(`normalize-${stream}`, 3, [`ingest-${stream}`])),
        task("join-data", 2, streams.map(stream => `normalize-${stream}`)),
        ...shards,
        integration,
        task("quality-gate", 2, [...shards.map(shard => shard.key), "flaky-integration"]),
        task("package", 3, ["quality-gate"]),
        task("publish-primary", 3, ["package"]),
        task("publish-secondary", 5, ["package"]),
        task("release-complete", 2, ["publish-primary", "publish-secondary"]),
        task("experimental-check", 2, ["join-data"], 42, 3),
        task("experimental-package", 1, ["experimental-check"]),
        task("experimental-publish", 1, ["experimental-package"]),
        task("audit-inputs", 6, ["prepare"]),
        task("audit-report", 2, ["audit-inputs", "release-complete"]),
      ];
      break;
    }
    case "parallel-fan-out":
      tasks = [task("prepare", 2), ...["parse", "transform", "validate"].map((key, index) => task(key, 4 + index * 2, ["prepare"])), task("publish", 2, ["parse", "transform", "validate"])];
      break;
    case "fan-in-barrier":
      tasks = [task("fast-input", 2), task("medium-input", 5), task("slow-input", 9), task("join", 2, ["fast-input", "medium-input", "slow-input"])];
      break;
    case "failure-isolation":
      tasks = [task("build", 3), task("quality-check", 5, [], 42), task("release", 1, ["build", "quality-check"]), task("independent-analysis", 7), task("analysis-report", 2, ["independent-analysis"])];
      break;
    case "cascading-skip":
      tasks = [task("source-check", 3, [], 42), task("compile", 1, ["source-check"]), task("package", 1, ["compile"]), task("deploy", 1, ["package"])];
      break;
    case "retry-exhaustion":
      tasks = [task("prepare", 2), task("unavailable-service", 2, ["prepare"], 42, 3), task("publish", 1, ["unavailable-service"])];
      break;
    case "worker-load": {
      const jobs = Array.from({ length: 8 }, (_, index) => task(`job-${index + 1}`, 8, ["prepare"]));
      tasks = [task("prepare", 2), ...jobs, task("completion-gate", 2, jobs.map(job => job.key))];
      break;
    }
    default:
      throw new Error("Unknown workflow template");
  }
  return { name: `${key}-${runId}`, tasks };
}
