import { buildCapabilityWorkflow } from "./workflow-templates";
import type { TemplateKey, WorkflowRequest, WorkflowTaskRequest } from "./workflow-templates";

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
    arguments: ["-lc", command],
    maxAttempts: options?.maxAttempts ?? 1,
    timeoutSeconds: options?.timeoutSeconds ?? 180,
    dependsOn,
  };
}

function selfVerificationWorkflow(
  runId: string,
): WorkflowRequest {
  const workspace =
    `/tmp/forge-verification-${runId}`;

  return {
    name:
      `distributed-build-verification-${runId}`,

    tasks: [
      shell(
        "source-check",
        `
set -euo pipefail

echo "======================================"
echo " Forge distributed build verification"
echo "======================================"
echo
echo "Worker: $(hostname)"
echo "Source: /workspace/forge"
echo

test -f /workspace/forge/controller/build.gradle
test -f /workspace/forge/worker/CMakeLists.txt
test -f /workspace/forge/proto/forge.proto


echo -n "Commit: "
cat /workspace/forge/REVISION

echo
echo "Required source files validated"
`,
        [],
      ),

      shell(
        "controller-build",
        `
set -euo pipefail

ROOT="${workspace}-controller"

rm -rf "$ROOT"
mkdir -p "$ROOT"
trap 'rm -rf "$ROOT"' EXIT

mkdir -p "$ROOT/controller"
tar -C /workspace/forge/controller --exclude=build --exclude=.gradle -cf - . | tar -C "$ROOT/controller" -xf -
cp -a /workspace/forge/proto "$ROOT/proto"

rm -rf \
  "$ROOT/controller/build" \
  "$ROOT/controller/.gradle"

chmod +x "$ROOT/controller/gradlew"

cd "$ROOT/controller"

echo "================================"
echo " Java / Spring Boot controller"
echo "================================"
echo "Worker: $(hostname)"
echo "Java: $(java -version 2>&1 | head -1)"
echo

./gradlew test --tests '*Test' --offline --max-workers=2 \
  --no-daemon \
  --console=plain

echo
echo "Controller compilation and tests passed"

rm -rf "$ROOT"
`,
        [
          "source-check",
        ],
        {
          timeoutSeconds: 600,
        },
      ),

      shell(
        "worker-build",
        `
set -euo pipefail

ROOT="${workspace}-worker"

rm -rf "$ROOT"
mkdir -p "$ROOT"
trap 'rm -rf "$ROOT"' EXIT

mkdir -p "$ROOT/worker"
tar -C /workspace/forge/worker --exclude=build --exclude='build-*' -cf - . | tar -C "$ROOT/worker" -xf -
cp -a /workspace/forge/proto "$ROOT/proto"

rm -rf "$ROOT/worker/build"

echo "======================="
echo " C++20 Forge worker"
echo "======================="
echo "Worker: $(hostname)"
echo "Compiler: $(c++ --version | head -1)"
echo "CMake: $(cmake --version | head -1)"
echo

cmake \
  -S "$ROOT/worker" \
  -B "$ROOT/worker/build" \
  -DCMAKE_BUILD_TYPE=Release

cmake \
  --build "$ROOT/worker/build" \
  --parallel 2

test -x \
  "$ROOT/worker/build/forge-worker"

echo
ctest --test-dir "$ROOT/worker/build" --output-on-failure
echo "Worker compilation and tests passed"

rm -rf "$ROOT"
`,
        [
          "source-check",
        ],
        {
          timeoutSeconds: 600,
        },
      ),

      shell(
        "python-checks",
        `
set -euo pipefail

ROOT="${workspace}-python"

rm -rf "$ROOT"
mkdir -p "$ROOT"
trap 'rm -rf "$ROOT"' EXIT

cp -a \
  /workspace/forge/scripts \
  "$ROOT/scripts"

echo "======================"
echo " Python demo tooling"
echo "======================"
echo "Worker: $(hostname)"
echo "Python: $(python3 --version)"
echo

python3 -m compileall \
  -q \
  "$ROOT/scripts"

echo "Python tooling validated"

rm -rf "$ROOT"
`,
        [
          "source-check",
        ],
      ),

      shell(
        "release-gate",
        `
set -euo pipefail

echo "================================"
echo " Forge verification complete"
echo "================================"
echo
echo "Java controller: PASS"
echo "C++ worker:      PASS"
echo "Python tooling:  PASS"
echo
echo "All distributed verification branches joined."
echo "Release gate worker: $(hostname)"
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
  const marker =
    `/workspace/shared/retry-${runId}`;

  return {
    name:
      `failure-retry-recovery-${runId}`,

    tasks: [
      shell(
        "prepare",
        `
set -euo pipefail

MARKER="${marker}"
mkdir -p /workspace/shared

rm -f "$MARKER"

echo "================================"
echo " Forge failure + retry recovery"
echo "================================"
echo
echo "Preparing deterministic failure"
echo "Worker: $(hostname)"
`,
        [],
      ),

      shell(
        "unstable-task",
        `
set -euo pipefail

MARKER="${marker}"

echo "Unstable task executing"
echo "Worker: $(hostname)"
echo

if [ ! -f "$MARKER" ]; then
    echo "Attempt 1: injecting exit code 42"
    echo "$(hostname)" > "$MARKER"
    exit 42
fi

FIRST_WORKER="$(cat "$MARKER")"

echo "Retry detected"
echo "Original attempt worker: $FIRST_WORKER"
echo "Current worker:          $(hostname)"
echo
echo "Recovered successfully"

`,
        ["prepare"],
        {
          maxAttempts: 2,
          timeoutSeconds: 60,
        },
      ),

      shell(
        "parallel-check",
        `
set -euo pipefail

echo "Independent branch executing"
echo "Worker: $(hostname)"

python3 - <<'PY'
import hashlib

payload = b"forge-distributed-recovery-demo"

for _ in range(500000):
    payload = hashlib.sha256(payload).digest()

print("Independent integrity workload complete")
print("digest =", payload.hex())
PY
`,
        ["prepare"],
      ),

      shell(
        "release",
        `
set -euo pipefail

echo "================================"
echo " Recovery workflow succeeded"
echo "================================"
echo
echo "Retry recovered successfully"
echo "Parallel dependency succeeded"
echo "Downstream release gate opened"
echo
echo "Final worker: $(hostname)"
`,
        [
          "unstable-task",
          "parallel-check",
        ],
      ),
    ],
  };
}

function videoWorkflow(
  runId: string,
): WorkflowRequest {
  const source =
    "/workspace/forge/demo/video/deploy/tears-of-steel-excerpt.mp4";

  const output =
    `/workspace/shared/video-${runId}`;

  function transcode(
    key: string,
    index: number,
    start: number,
  ) {
    const chunk =
      index.toString().padStart(3, "0");

    return shell(
      key,
      `
set -euo pipefail

SOURCE="${source}"
OUT="${output}"

mkdir -p "$OUT"

echo "================================"
echo " Video segment ${chunk}"
echo "================================"
echo "Worker: $(hostname)"
echo "Start:  ${start}s"
echo "Length: 10s"

ffmpeg \
  -y \
  -hide_banner \
  -loglevel warning \
  -threads 1 \
  -ss ${start} \
  -i "$SOURCE" \
  -t 10 \
  -filter_threads 1 \
  -vf "scale=1280:-2,eq=contrast=1.03:saturation=1.05" \
  -map 0:v:0 \
  -map '0:a:0?' \
  -c:v libx264 \
  -preset veryfast \
  -crf 22 \
  -threads 1 \
  -c:a aac \
  -b:a 128k \
  -avoid_negative_ts make_zero \
  "$OUT/chunk_${chunk}.mp4"

echo
echo "Segment ${chunk} complete"

ls -lh "$OUT/chunk_${chunk}.mp4"
`,
      ["inspect-video"],
      {
        timeoutSeconds: 180,
      },
    );
  }

  return {
    name:
      `distributed-video-processing-${runId}`,

    tasks: [
      shell(
        "inspect-video",
        `
set -euo pipefail

SOURCE="${source}"
OUT="${output}"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "================================"
echo " Forge distributed video demo"
echo "================================"
echo
echo "Probe worker: $(hostname)"
echo "Source: $SOURCE"
echo

test -f "$SOURCE"

ffprobe \
  -v error \
  -show_entries \
  stream=codec_name,codec_type,width,height \
  -show_entries \
  format=duration,size,bit_rate \
  -of default=noprint_wrappers=1 \
  "$SOURCE"
`,
        [],
      ),

      transcode(
        "transcode-000",
        0,
        0,
      ),

      transcode(
        "transcode-001",
        1,
        10,
      ),

      transcode(
        "transcode-002",
        2,
        20,
      ),

      shell(
        "merge-video",
        `
set -euo pipefail

OUT="${output}"

echo "================================"
echo " Merging distributed segments"
echo "================================"
echo "Worker: $(hostname)"

cat > "$OUT/concat.txt" <<'MANIFEST'
file 'chunk_000.mp4'
file 'chunk_001.mp4'
file 'chunk_002.mp4'
MANIFEST

cd "$OUT"

ffmpeg \
  -y \
  -hide_banner \
  -loglevel warning \
  -f concat \
  -safe 0 \
  -i concat.txt \
  -c copy \
  -movflags +faststart \
  forge-demo-output.partial.mp4

mv forge-demo-output.partial.mp4 forge-demo-output.mp4

echo
echo "Final output:"
echo "$OUT/forge-demo-output.mp4"
echo

ffprobe \
  -v error \
  -show_entries \
  stream=codec_name,width,height \
  -show_entries \
  format=duration,size,bit_rate \
  -of default=noprint_wrappers=1 \
  "$OUT/forge-demo-output.mp4"

echo
ls -lh "$OUT/forge-demo-output.mp4"
`,
        [
          "transcode-000",
          "transcode-001",
          "transcode-002",
        ],
        {
          timeoutSeconds: 120,
        },
      ),
    ],
  };
}

export function buildTemplateWorkflow(template: TemplateKey, runId: string): WorkflowRequest {
  if (!/^[a-f0-9]{8}$/.test(runId)) throw new Error("Invalid template run ID");
  switch (template) {
    case "self-verification": return selfVerificationWorkflow(runId);
    case "failure-retry": return failureRetryWorkflow(runId);
    case "video-processing": return videoWorkflow(runId);
    default: return buildCapabilityWorkflow(template, runId);
  }
}
