"use client";

import {
  GitBranch,
  LoaderCircle,
  RefreshCcw,
} from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

type TemplateKey =
  | "self-verification"
  | "failure-retry";

interface TemplateDefinition {
  key: TemplateKey;
  name: string;
  description: string;
  features: string[];
  icon:
    | "branch"
    | "retry";
}

const templates:
  TemplateDefinition[] = [
    {
      key:
        "self-verification",

      name:
        "Distributed Build & Verification",

      description:
        "Run Forge's own Java, C++ and Python verification workload as a distributed DAG.",

      features: [
        "Parallel execution",
        "Multi-worker scheduling",
        "Dependency fan-out",
        "Join / release gate",
      ],

      icon: "branch",
    },

    {
      key:
        "failure-retry",

      name:
        "Failure & Retry Recovery",

      description:
        "Inject a deterministic first-attempt failure and watch Forge recover through a durable retry.",

      features: [
        "Intentional failure",
        "Retry backoff",
        "Multiple physical attempts",
        "Downstream dependency recovery",
      ],

      icon: "retry",
    },
  ];

export function TemplateLauncher() {
  const router = useRouter();

  const [
    launching,
    setLaunching,
  ] =
    useState<
      TemplateKey | null
    >(null);

  const [
    error,
    setError,
  ] =
    useState<string | null>(
      null,
    );

  async function launch(
    template: TemplateKey,
  ) {
    setLaunching(template);
    setError(null);

    try {
      const response =
        await fetch(
          "/api/forge/templates",
          {
            method: "POST",

            headers: {
              "Content-Type":
                "application/json",
            },

            body:
              JSON.stringify({
                template,
              }),
          },
        );

      const result:
        | {
            id: string;
            name: string;
          }
        | {
            error: string;
          } =
        await response.json();

      if (
        !response.ok ||
        !("id" in result)
      ) {
        throw new Error(
          "error" in result
            ? result.error
            : "Workflow launch failed",
        );
      }

      router.push(
        `/workflows/${result.id}`,
      );
    } catch (launchError) {
      setError(
        launchError
          instanceof Error
          ? launchError.message
          : "Workflow launch failed",
      );

      setLaunching(null);
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
        {templates.map(
          (template) => {
            const Icon =
              template.icon ===
              "branch"
                ? GitBranch
                : RefreshCcw;

            const isLaunching =
              launching ===
              template.key;

            return (
              <article
                key={
                  template.key
                }
                className="flex min-h-[310px] flex-col rounded-xl border border-zinc-800 bg-zinc-950 p-6"
              >
                <div className="mb-5 flex h-10 w-10 items-center justify-center rounded-lg border border-zinc-800 bg-zinc-900">
                  <Icon className="h-5 w-5 text-zinc-300" />
                </div>

                <h2 className="text-lg font-medium text-zinc-100">
                  {
                    template.name
                  }
                </h2>

                <p className="mt-2 text-sm leading-6 text-zinc-500">
                  {
                    template.description
                  }
                </p>

                <div className="mt-5 grid grid-cols-2 gap-2">
                  {template.features.map(
                    (feature) => (
                      <div
                        key={
                          feature
                        }
                        className="rounded-md border border-zinc-800/80 bg-zinc-900/40 px-3 py-2 text-xs text-zinc-500"
                      >
                        {
                          feature
                        }
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
                        template.key,
                      )
                    }
                    className="inline-flex h-10 items-center justify-center gap-2 rounded-md border border-zinc-700 bg-zinc-100 px-4 text-sm font-medium text-zinc-950 transition hover:bg-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {isLaunching ? (
                      <>
                        <LoaderCircle className="h-4 w-4 animate-spin" />
                        Launching
                      </>
                    ) : (
                      "Launch workflow"
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
