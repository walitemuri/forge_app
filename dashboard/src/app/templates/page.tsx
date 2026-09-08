import {
  ArrowLeft,
  LayoutTemplate,
} from "lucide-react";
import Link from "next/link";

import { TemplateLauncher } from "./template-launcher";

export default function TemplatesPage() {
  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <div className="mx-auto max-w-7xl px-6 py-8">
        <Link
          href="/"
          className="mb-8 inline-flex items-center gap-2 text-sm text-zinc-500 transition hover:text-zinc-200"
        >
          <ArrowLeft className="h-4 w-4" />
          Control Plane
        </Link>

        <header className="mb-8 border-b border-zinc-800 pb-8">
          <div className="mb-3 flex items-center gap-2 text-sm text-zinc-500">
            <LayoutTemplate className="h-4 w-4" />
            Workflow Templates
          </div>

          <h1 className="text-2xl font-semibold tracking-tight">
            Launch a workflow
          </h1>

          <p className="mt-2 max-w-2xl text-sm leading-6 text-zinc-500">
            Run controlled workloads that demonstrate
            Forge&apos;s scheduling, dependency,
            retry, and recovery behavior.
          </p>
        </header>

        <TemplateLauncher />
      </div>
    </main>
  );
}
