import { readFile } from "node:fs/promises";
import path from "node:path";

export async function GET(request: Request, { params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  if (!/^[a-f0-9]{8}$/.test(runId)) return new Response("Not found", { status: 404 });
  let data: Buffer;
  try {
    data = await readFile(path.resolve(process.cwd(), "../demo/video/dashboard-runs", runId, "forge-output.mp4"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return new Response("Video is not available yet", { status: 404 });
    throw error;
  }
  const headers = new Headers({ "Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Cache-Control": "no-store" });
  const range = request.headers.get("range");
  let start = 0;
  let end = data.length - 1;
  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (match && (match[1] || match[2])) {
      start = match[1] ? Number(match[1]) : Math.max(0, data.length - Number(match[2]));
      end = match[1] && match[2] ? Math.min(Number(match[2]), end) : end;
    } else start = data.length;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= data.length) {
      headers.set("Content-Range", `bytes */${data.length}`);
      return new Response(null, { status: 416, headers });
    }
    headers.set("Content-Range", `bytes ${start}-${end}/${data.length}`);
  }
  headers.set("Content-Length", String(end - start + 1));
  return new Response(new Uint8Array(data.subarray(start, end + 1)), { status: range ? 206 : 200, headers });
}
