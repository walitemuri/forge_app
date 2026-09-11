"use client";

import {
  LoaderCircle,
  RotateCcw,
  ServerCrash,
} from "lucide-react";

import {
  useRouter,
} from "next/navigation";

import {
  useState,
} from "react";


type Demo =
  | "worker-loss"
  | "controller-restart";


const demos = [
  {
    key:
      "worker-loss" as Demo,

    name:
      "Worker Loss Recovery",

    description:
      "Kill the worker executing a live task, detect the lost attempt through heartbeat failure, and retry the work on another worker.",

    features: [
      "Real worker termination",
      "LOST detection",
      "Cross-worker retry",
      "Automatic recovery",
    ],

    icon:
      ServerCrash,
  },

  {
    key:
      "controller-restart" as Demo,

    name:
      "Controller Crash Recovery",

    description:
      "Crash the control plane during a live execution, recover persisted state, mark the interrupted attempt lost, and automatically retry the task after restart.",

    features: [
      "Real controller SIGKILL",
      "Startup reconciliation",
      "LOST attempt recovery",
      "Automatic retry",
    ],

    icon:
      RotateCcw,
  },
];


export function ReliabilityLauncher() {
  const router =
    useRouter();

  const [
    launching,
    setLaunching,
  ] =
    useState<
      Demo | null
    >(null);

  const [
    error,
    setError,
  ] =
    useState<
      string | null
    >(null);


  async function launch(
    demo: Demo,
  ) {
    setLaunching(
      demo,
    );

    setError(
      null,
    );

    try {
      const response =
        await fetch(
          `/api/forge/reliability/${demo}`,
          {
            method:
              "POST",
          },
        );

      const result =
        await response.json();

      if (
        !response.ok ||
        !result.id
      ) {
        throw new Error(
          result.error ??
          "Demo launch failed",
        );
      }

      router.push(
        `/workflows/${result.id}`,
      );

    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Demo launch failed",
      );

      setLaunching(
        null,
      );
    }
  }


  return (
    <>
      {error && (
        <div className="mb-6 rounded-lg border border-red-900/60 bg-red-950/20 px-4 py-3 text-sm text-red-300">
          {error}
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        {demos.map(
          (demo) => {
            const Icon =
              demo.icon;

            const active =
              launching ===
              demo.key;

            return (
              <article
                key={demo.key}
                className="flex min-h-[320px] flex-col rounded-xl border border-blue-950/60 bg-zinc-950 p-6"
              >
                <div className="mb-5 flex h-10 w-10 items-center justify-center rounded-lg border border-blue-950 bg-blue-950/20">
                  <Icon className="h-5 w-5 text-blue-400" />
                </div>

                <h2 className="text-lg font-medium text-zinc-100">
                  {demo.name}
                </h2>

                <p className="mt-2 text-sm leading-6 text-zinc-500">
                  {demo.description}
                </p>

                <div className="mt-5 grid grid-cols-2 gap-2">
                  {demo.features.map(
                    (feature) => (
                      <div
                        key={feature}
                        className="rounded-md border border-zinc-800 bg-zinc-900/40 px-3 py-2 text-xs text-zinc-500"
                      >
                        {feature}
                      </div>
                    ),
                  )}
                </div>

                <div className="mt-auto pt-6">
                  <button
                    type="button"
                    disabled={
                      launching !==
                      null
                    }
                    onClick={() =>
                      launch(
                        demo.key,
                      )
                    }
                    className="inline-flex h-10 items-center justify-center gap-2 rounded-md border border-blue-900/70 bg-blue-600 px-4 text-sm font-medium text-white transition hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {active ? (
                      <>
                        <LoaderCircle className="h-4 w-4 animate-spin" />
                        Injecting failure
                      </>
                    ) : (
                      "Run recovery demo"
                    )}
                  </button>
                </div>
              </article>
            );
          },
        )}
      </div>
    </>
  );
}
