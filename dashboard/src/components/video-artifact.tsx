"use client";

import {
  Download,
  Film,
} from "lucide-react";

import {
  useState,
} from "react";

interface VideoArtifactProps {
  workflowName: string;
  status: string;
}

interface VideoMetadata {
  duration: number;
  width: number;
  height: number;
}

export function VideoArtifact({
  workflowName,
  status,
}: VideoArtifactProps) {
  const [
    metadata,
    setMetadata,
  ] =
    useState<
      VideoMetadata | null
    >(null);

  const match =
    /^distributed-video-processing-([a-f0-9]{8})$/i.exec(
      workflowName,
    );

  if (
    !match ||
    status !== "SUCCEEDED"
  ) {
    return null;
  }

  const runId =
    match[1];

  const src =
    `/api/forge/artifacts/video/${runId}`;

  return (
    <section className="mb-8 overflow-hidden rounded-xl border border-blue-950/70 bg-zinc-950">
      <div className="flex flex-col gap-3 border-b border-zinc-800 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <Film className="h-4 w-4 text-blue-400" />

            <h2 className="font-medium">
              Output Artifact
            </h2>

            <span className="rounded-md bg-emerald-500/10 px-2 py-0.5 text-[10px] font-medium tracking-wide text-emerald-400 ring-1 ring-inset ring-emerald-500/20">
              READY
            </span>
          </div>

          <p className="mt-1 text-xs text-zinc-500">
            Tears of Steel excerpt processed by the distributed FFmpeg workflow
          </p>
        </div>

        <a
          href={`${src}?download=1`}
          className="inline-flex h-9 items-center justify-center gap-2 rounded-md border border-zinc-700 bg-zinc-900 px-3 text-xs font-medium text-zinc-300 transition hover:border-blue-800 hover:text-blue-300"
        >
          <Download className="h-3.5 w-3.5" />

          Download MP4
        </a>
      </div>

      <div className="p-5">
        <div className="overflow-hidden rounded-lg border border-zinc-800 bg-black shadow-2xl shadow-blue-950/10">
          <video
            controls
            preload="metadata"
            playsInline
            src={src}
            className="aspect-video w-full bg-black object-contain"
            onLoadedMetadata={(
              event,
            ) => {
              const video =
                event.currentTarget;

              setMetadata({
                duration:
                  video.duration,

                width:
                  video.videoWidth,

                height:
                  video.videoHeight,
              });
            }}
          >
            Your browser does not support HTML5 video.
          </video>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-zinc-900 pt-4">
          <div>
            <div className="text-[10px] uppercase tracking-wider text-zinc-600">
              Artifact
            </div>

            <div className="mt-1 font-mono text-xs text-zinc-300">
              forge-demo-output.mp4
            </div>
          </div>

          {metadata && (
            <>
              <div>
                <div className="text-[10px] uppercase tracking-wider text-zinc-600">
                  Duration
                </div>

                <div className="mt-1 font-mono text-xs text-zinc-300">
                  {metadata.duration.toFixed(
                    1,
                  )}
                  s
                </div>
              </div>

              <div>
                <div className="text-[10px] uppercase tracking-wider text-zinc-600">
                  Resolution
                </div>

                <div className="mt-1 font-mono text-xs text-zinc-300">
                  {metadata.width}
                  ×
                  {metadata.height}
                </div>
              </div>
            </>
          )}

          <div>
            <div className="text-[10px] uppercase tracking-wider text-zinc-600">
              Pipeline
            </div>

            <div className="mt-1 font-mono text-xs text-blue-400">
              3-way FFmpeg fan-out → merge
            </div>
          </div>

          <div className="ml-auto">
            <div className="text-[10px] uppercase tracking-wider text-zinc-600">
              Run
            </div>

            <div className="mt-1 font-mono text-xs text-zinc-400">
              {runId}
            </div>
          </div>
        </div>
      </div>

      <p className="px-5 pb-5 text-[11px] leading-5 text-zinc-600">
        Source footage: <a className="underline hover:text-zinc-400" href="https://mango.blender.org/" target="_blank" rel="noreferrer">Tears of Steel</a> by the Blender Foundation, licensed CC BY 3.0.
      </p>
    </section>
  );
}
