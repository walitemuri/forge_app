import Link from "next/link";
import { notFound } from "next/navigation";

export const dynamic = "force-dynamic";

import {
  ArrowLeft,
  ShieldCheck,
} from "lucide-react";

import {
  ReliabilityLauncher,
} from "./reliability-launcher";


export default function ReliabilityPage() {
  if (process.env.FORGE_PUBLIC_DEMO === "true" || process.env.FORGE_CHAOS_ENABLED !== "true") notFound();
  return (
    <main className="min-h-screen bg-[#09090b] text-zinc-100">
      <div className="mx-auto max-w-7xl px-6 py-8">
        <Link
          href="/templates"
          className="mb-8 inline-flex items-center gap-2 text-sm text-zinc-500 transition hover:text-zinc-200"
        >
          <ArrowLeft className="h-4 w-4" />
          Templates
        </Link>

        <header className="mb-8 border-b border-zinc-800 pb-8">
          <div className="mb-3 flex items-center gap-2 text-sm text-blue-400">
            <ShieldCheck className="h-4 w-4" />
            Reliability Lab
          </div>

          <h1 className="text-2xl font-semibold tracking-tight">
            Failure Injection & Recovery
          </h1>

          <p className="mt-3 max-w-2xl text-sm leading-6 text-zinc-500">
            Inject real infrastructure failures and watch Forge recover using worker-loss detection, retries, durable events, session recovery, and reconnect logic.
          </p>
        </header>

        <ReliabilityLauncher />
      </div>
    </main>
  );
}
