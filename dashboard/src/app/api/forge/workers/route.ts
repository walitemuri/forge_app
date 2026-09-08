import { NextResponse } from "next/server";

import { forgeFetch } from "@/lib/forge";
import type { ForgeWorker } from "@/lib/types";

export async function GET() {
  try {
    const workers =
      await forgeFetch<ForgeWorker[]>(
        "/api/workers",
      );

    return NextResponse.json(workers);
  } catch (error) {
    console.error(
      "Failed to load Forge workers:",
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
