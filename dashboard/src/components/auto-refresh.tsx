"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export function AutoRefresh({
  enabled,
  intervalMs = 1000,
}: {
  enabled: boolean;
  intervalMs?: number;
}) {
  const router = useRouter();

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const timer = window.setInterval(
      () => {
        router.refresh();
      },
      intervalMs,
    );

    return () => {
      window.clearInterval(timer);
    };
  }, [
    enabled,
    intervalMs,
    router,
  ]);

  return null;
}
