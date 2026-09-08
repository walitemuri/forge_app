import { NextResponse } from "next/server";

import { forgeFetch } from "@/lib/forge";
import type { ForgeWorkflow } from "@/lib/types";

export async function GET() {
  try {
    const workflows =
      await forgeFetch<ForgeWorkflow[]>(
        "/api/workflows",
      );

    return NextResponse.json(workflows);
  } catch (error) {
    console.error(
      "Failed to load Forge workflows:",
      error,
    );

    return NextResponse.json(
      {
        error:
          "Forge controller is unavailable",
      },
      {
        status: 503,
      },
    );
  }
}
