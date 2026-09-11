import json
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


WORKERS = {
    "forge-worker-a": "forge-worker-a",
    "forge-worker-b": "forge-worker-b",
    "forge-worker-c": "forge-worker-c",
}


def docker(*args):
    return subprocess.run(
        ["docker", *args],
        check=True,
        capture_output=True,
        text=True,
    )


def wait_for_controller(timeout=30):
    deadline = time.monotonic() + timeout

    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                "http://controller:8080/api/workers",
                timeout=1,
            ) as response:
                if response.status == 200:
                    return True
        except Exception:
            pass

        time.sleep(0.5)

    return False


def controller_restart_sequence():
    # Give the browser time to open the workflow page first.
    time.sleep(2)

    try:
        print(
            "[chaos] killing forge-controller",
            flush=True,
        )

        docker(
            "kill",
            "forge-controller",
        )

        # Keep the control plane unavailable long enough
        # for startup reconciliation to be meaningful.
        time.sleep(5)

        print(
            "[chaos] starting forge-controller",
            flush=True,
        )

        docker(
            "start",
            "forge-controller",
        )

        if wait_for_controller():
            print(
                "[chaos] controller healthy",
                flush=True,
            )
        else:
            print(
                "[chaos] controller failed health check",
                flush=True,
            )

    except Exception as exc:
        print(
            f"[chaos] controller restart failed: {exc}",
            flush=True,
        )


class Handler(BaseHTTPRequestHandler):
    def send_json(self, status, body):
        payload = json.dumps(body).encode("utf-8")

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()

        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self.send_json(
                200,
                {"status": "ok"},
            )
            return

        self.send_json(
            404,
            {"error": "not found"},
        )

    def do_POST(self):
        length = int(
            self.headers.get(
                "Content-Length",
                "0",
            )
        )

        try:
            raw = (
                self.rfile.read(length)
                if length
                else b"{}"
            )

            body = json.loads(raw)
        except Exception:
            self.send_json(
                400,
                {"error": "invalid json"},
            )
            return

        # ==========================================
        # Worker failure
        # ==========================================

        if self.path == "/v1/kill-worker":
            worker_id = body.get("workerId")
            container = WORKERS.get(worker_id)

            if not container:
                self.send_json(
                    400,
                    {"error": "unknown worker"},
                )
                return

            try:
                print(
                    f"[chaos] killing {container}",
                    flush=True,
                )

                docker(
                    "kill",
                    container,
                )

                self.send_json(
                    202,
                    {
                        "workerId": worker_id,
                        "action": "killed",
                    },
                )
            except Exception as exc:
                self.send_json(
                    500,
                    {"error": str(exc)},
                )

            return

        # ==========================================
        # Controller restart
        # ==========================================

        if self.path == "/v1/restart-controller":
            print(
                "[chaos] scheduling controller crash",
                flush=True,
            )

            threading.Thread(
                target=controller_restart_sequence,
                daemon=True,
            ).start()

            self.send_json(
                202,
                {
                    "action":
                        "controller-restart-scheduled",
                    "crashAfterSeconds":
                        2,
                },
            )

            return

        self.send_json(
            404,
            {"error": "not found"},
        )

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    print(
        "[chaos] listening on :8090",
        flush=True,
    )

    server = ThreadingHTTPServer(
        ("0.0.0.0", 8090),
        Handler,
    )

    server.serve_forever()
