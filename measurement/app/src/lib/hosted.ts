/**
 * Hosted mode: the studio built as a public, read-only page for the GreenV demo site.
 *
 * Decided at build time, never at runtime: `VITE_VERGE_HOSTED=1` and `VITE_DEMO_API_BASE` are
 * read once by Vite and baked in. A hosted build has no dev server behind it — no ffmpeg, no
 * runs on disk, no cloud control, no nonce — so every seam that would reach `/api` asks this
 * module first. With the variables unset, nothing here is true and the studio is unchanged.
 *
 * In hosted mode the server does all the work (`/v1/demo/*` on the GreenV video API): it
 * receives the phone's video, extracts the frames, runs the GPU job and publishes signed links.
 * The page only reads, which is why it can be public.
 */

import type { PhoneCapture } from "./phone-link";

export const HOSTED: boolean = import.meta.env.VITE_VERGE_HOSTED === "1";

/** Always ends in `/`, so every route below is relative to it, as the contract is written. */
export const DEMO_API_BASE: string = withSlash(import.meta.env.VITE_DEMO_API_BASE ?? "");

export function withSlash(base: string): string {
  const trimmed = base.trim();
  return trimmed === "" || trimmed.endsWith("/") ? trimmed : `${trimmed}/`;
}

/** A route under the demo base. `path` is relative: `captures`, not `/captures`. */
export function demoUrl(base: string, path: string): string {
  if (!base) throw new Error("VITE_DEMO_API_BASE is not set in this build");
  return `${withSlash(base)}${path.replace(/^\/+/, "")}`;
}

/**
 * The capture list, in either shape the API may answer with: a bare array, or the
 * `{ captures: [...] }` envelope the local intake uses. Anything else is a contract break and
 * says so, rather than rendering as "no captures yet".
 */
export function parseCaptureList(body: unknown): PhoneCapture[] {
  const list = Array.isArray(body)
    ? body
    : body && typeof body === "object" && Array.isArray((body as { captures?: unknown }).captures)
      ? (body as { captures: unknown[] }).captures
      : null;
  if (!list) throw new Error("demo API: the capture list is neither an array nor { captures }");
  return list.filter((c): c is PhoneCapture => Boolean(c && typeof c === "object" && typeof (c as PhoneCapture).id === "string"))
    .map((c) => ({ ...c, events: Array.isArray(c.events) ? c.events : [] }));
}

/**
 * How long one read may take. The poll waits for each read before scheduling the next, so a
 * request that never answers — a scale-to-zero API mid-start, a dropped connection on the event
 * Wi-Fi — would otherwise freeze the page on its last capture for good.
 */
export const DEMO_READ_TIMEOUT_MS = 15_000;

export async function demoGet(base: string, path: string, fetchImpl: typeof fetch = fetch): Promise<unknown> {
  // No credentials and no custom headers: reads are public, and a plain GET stays a simple
  // CORS request with no preflight.
  const response = await fetchImpl(demoUrl(base, path), { signal: AbortSignal.timeout(DEMO_READ_TIMEOUT_MS) });
  if (!response.ok) {
    const detail = await response.text().catch(() => response.statusText);
    throw new Error(`demo API ${path}: ${response.status} ${detail.slice(0, 200)}`);
  }
  return response.json();
}
