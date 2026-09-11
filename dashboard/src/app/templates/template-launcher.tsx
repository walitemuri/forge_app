"use client";

import { GitBranch, LoaderCircle, RefreshCcw, Video, CircleX, Cpu } from "lucide-react";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { workflowTemplates, type TemplateKey } from "@/lib/workflow-templates";

const icons = { branch: GitBranch, retry: RefreshCcw, video: Video, failure: CircleX, capacity: Cpu };

export function TemplateLauncher() {
  const router = useRouter();
  const inFlight = useRef(false);
  const [launching, setLaunching] = useState<TemplateKey | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function launch(template: TemplateKey) {
    if (inFlight.current) return;
    inFlight.current = true;
    setLaunching(template);
    setError(null);
    try {
      const response = await fetch("/api/forge/templates", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ template }),
      });
      const result = await response.json();
      if (!response.ok || typeof result.id !== "string") {
        throw new Error(result.error ?? "Workflow launch failed");
      }
      router.push(`/workflows/${result.id}`);
    } catch (launchError) {
      setError(launchError instanceof Error ? launchError.message : "Workflow launch failed");
      inFlight.current = false;
      setLaunching(null);
    }
  }

  return (
    <>
      {error && <div role="alert" className="mb-6 rounded-lg border border-amber-900/60 bg-amber-950/20 px-4 py-3 text-sm text-amber-200">{error}</div>}
      <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
        {workflowTemplates.map(template => {
          const Icon = icons[template.icon];
          const isLaunching = launching === template.key;
          return (
            <article key={template.key} className="flex flex-col rounded-xl border border-zinc-800 bg-zinc-950 p-6">
              <div className="mb-5 flex items-center justify-between">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-zinc-800 bg-zinc-900">
                  <Icon aria-hidden="true" className="h-5 w-5 text-zinc-300" />
                </div>
                <span className="text-xs text-zinc-400">{template.taskCount} tasks</span>
              </div>
              <h2 className="text-lg font-medium text-zinc-100">{template.name}</h2>
              <p className="mt-2 text-sm leading-6 text-zinc-400">{template.description}</p>
              <ul className="mt-5 grid grid-cols-2 gap-2">
                {template.features.map(feature => <li key={feature} className="rounded-md border border-zinc-800/80 bg-zinc-900/40 px-3 py-2 text-xs text-zinc-400">{feature}</li>)}
              </ul>
              <p className="mt-5 text-sm leading-6 text-zinc-300"><span className="font-medium text-zinc-100">Expected result: </span>{template.outcome}</p>
              <details className="mt-3 text-xs leading-5 text-zinc-400">
                <summary className="cursor-pointer rounded focus-visible:outline-2 focus-visible:outline-blue-400">Runtime details</summary>
                <p className="mt-2">{template.requirements}</p>
              </details>
              <div className="mt-auto pt-6">
                <button type="button" disabled={launching !== null} onClick={() => launch(template.key)} aria-label={`Launch ${template.name}`} className="inline-flex h-10 items-center justify-center gap-2 rounded-md border border-zinc-700 bg-zinc-100 px-4 text-sm font-medium text-zinc-950 transition hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-blue-400 disabled:cursor-not-allowed disabled:opacity-50">
                  {isLaunching ? <><LoaderCircle aria-hidden="true" className="h-4 w-4 animate-spin" />Launching</> : "Launch workflow"}
                </button>
              </div>
            </article>
          );
        })}
      </div>
    </>
  );
}
