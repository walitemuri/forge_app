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


def shell(command):

    return {
        "command": "bash",
        "arguments": [
            "-lc",
            command,
        ],
        "maxAttempts": 1,
        "timeoutSeconds": 180,
    }


def main():

    repo = shlex.quote(
        str(REPO_ROOT)
    )

    workflow_name = (
        "forge-self-verification-"
        + uuid.uuid4().hex[:8]
    )

    workflow = {
        "name": workflow_name,

        "tasks": [

            {
                "key": "source-check",

                **shell(
                    f"""
set -euo pipefail

echo "=== Forge source ==="

git -C {repo} rev-parse --short HEAD
git -C {repo} diff --check

echo "Source tree validated"
"""
                ),

                "dependsOn": [],
            },

            {
                "key": "controller-build",

                **shell(
                    f"""
set -euo pipefail

echo "=== Java controller ==="

cd {repo}/controller

./gradlew classes --no-daemon

echo "Controller compilation passed"
"""
                ),

                "dependsOn": [
                    "source-check",
                ],
            },

            {
                "key": "worker-build",

                **shell(
                    f"""
set -euo pipefail

echo "=== C++ worker ==="

cmake --build {repo}/worker/build \
    --parallel 2

test -x \
    {repo}/worker/build/forge-worker

echo "Worker compilation passed"
"""
                ),

                "dependsOn": [
                    "source-check",
                ],
            },

            {
                "key": "python-checks",

                **shell(
                    f"""
set -euo pipefail

echo "=== Python tooling ==="

cd {repo}

python3 -m compileall \
    -q \
    scripts

echo "Python tooling validated"
"""
                ),

                "dependsOn": [
                    "source-check",
                ],
            },

            {
                "key": "release-gate",

                **shell(
                    """
set -euo pipefail

echo "=============================="
echo " Forge verification complete "
echo "=============================="
"""
                ),

                "dependsOn": [
                    "controller-build",
                    "worker-build",
                    "python-checks",
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
    print(
        "Dashboard:"
    )

    print(
        "http://localhost:3000"
        f"/workflows/{workflow_id}"
    )


if __name__ == "__main__":
    main()
