# Dashboard video demo

Open http://localhost:3000/templates and launch **Distributed Video Processing**.
The workflow inspects the 4K Tears of Steel source, processes six five-second
segments on available workers, then merges and validates a 30-second H.264 MP4
at its original 3840 × 1714 resolution with audio. The completed workflow page
provides a centered player, seeking and download.

Requirements: dashboard and controller running, at least one connected worker
(three recommended), Python 3, FFmpeg with libx264, and FFprobe. Dashboard and
workers must share this checkout. The source is `demo/video/input/tears-of-steel-4k.webm`
and must contain at least 30 seconds of video. Each run keeps separate output
under `demo/video/dashboard-runs/<run-id>/`; existing videos are preserved.

## Verified run

- Workflow: `1b4d50e4-4bdf-4e50-8868-a367e644a38e`
- Dashboard: http://localhost:3000/workflows/1b4d50e4-4bdf-4e50-8868-a367e644a38e
- Result: all eight tasks succeeded across three workers.
- Output: `dashboard-runs/0a58ad6c/forge-output.mp4` (30 seconds, 3840 × 1714).
- Browser checks passed: template launch, centered player, playback, seeking to 15 seconds,
  range requests, invalid ranges, download, and no browser JavaScript errors.
- Screenshots, downloaded MP4 and execution evidence: `dashboard-evidence/`.

Repeat the browser verification with an installed Playwright Chromium:

```sh
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs node scripts/dashboard_video_demo.mjs
```

This launches a fresh workflow and replaces only the browser evidence files.
