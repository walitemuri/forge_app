const FORGE_API_URL = process.env.FORGE_API_URL ?? "http://127.0.0.1:8080";

export async function forgeFetch<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await fetch(`${FORGE_API_URL}${path}`, {
    ...options,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      ...options?.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`Forge API ${response.status}: ${response.statusText}`);
  }

  return response.json() as Promise<T>;
}
