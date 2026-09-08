#!/usr/bin/env python3

import json
import os
import shlex
import urllib.request
import uuid
from pathlib import Path


CONTROLLER_URL = os.environ.get(
    "FORGE_CONTROLLER_URL",
    "http://127.0.0.1:8080",
)

WORKFLOWS_URL = (
    f"{CONTROLLER_URL}/api/workflows"
)

REPO_ROOT = (
    Path(__file__)
    .resolve()
    .parent
    .parent
)


def post_json(url, body):
    data = json.dumps(
        body
    ).encode("utf-8")

    request = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Content-Type":
                "application/json",
        },
    )

    with urllib.request.urlopen(
        request,
        timeout=15,
    ) as response:
        return json.load(response)


def shell(
    command,
    max_attempts=1,
    timeout_seconds=120,
):
    return {
        "command": "bash",
        "arguments": [
            "-lc",
            command,
        ],
        "maxAttempts":
            max_attempts,
        "timeoutSeconds":
            timeout_seconds,
    }


def main():
    run_id = uuid.uuid4().hex[:8]

    workflow_name = (
        f"failure-recovery-demo-{run_id}"
    )

    marker = (
        f"/tmp/forge-retry-demo-{run_id}"
    )

    repo = shlex.quote(
        str(REPO_ROOT)
    )

    marker_q = shlex.quote(
        marker
    )

    workflow = {
        "name": workflow_name,

        "tasks": [
            {
                "key": "prepare",

                **shell(
                    f"""
set -euo pipefail

rm -f {marker_q}

echo "Preparing retry demonstration"

git -C {repo} \
    rev-parse \
    --short \
    HEAD

echo "Preparation complete"
"""
                ),

                "dependsOn": [],
            },

            {
                "key": "retrying-tests",

                **shell(
                    f"""
set -euo pipefail

echo "Running simulated unstable test"

if [ ! -f {marker_q} ]; then
    echo "First attempt: injecting failure"
    touch {marker_q}
    exit 42
fi

echo "Retry detected"
echo "Tests recovered successfully"
exit 0
""",
                    max_attempts=2,
                ),

                "dependsOn": [
                    "prepare",
                ],
            },

            {
                "key": "static-analysis",

                **shell(
                    f"""
set -euo pipefail

echo "Checking Python scripts"

cd {repo}

python3 -m compileall \
    -q \
    scripts

echo "Static analysis passed"
"""
                ),

                "dependsOn": [
                    "prepare",
                ],
            },

            {
                "key": "package",

                **shell(
                    """
set -euo pipefail

echo "All required checks passed"
echo "Package gate succeeded"
"""
                ),

                "dependsOn": [
                    "retrying-tests",
                    "static-analysis",
                ],
            },
        ],
    }

    print(
        "Submitting:",
        workflow_name,
    )

    result = post_json(
        WORKFLOWS_URL,
        workflow,
    )

    workflow_id = result["id"]

    print()
    print(
        "Workflow:",
        workflow_id,
    )

    print()
    print("Dashboard:")

    print(
        "http://localhost:3000"
        f"/workflows/{workflow_id}"
    )


if __name__ == "__main__":
    main()
