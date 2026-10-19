/**
 * The phone inbox as the studio follows it: the list of captures, and Auto — load, extract and
 * run whatever arrives next.
 *
 * This lives outside any pane on purpose. The Setup pane's Phone section and the Demo panel both
 * show it, and Demo mode hides Setup; if the poll belonged to a component, switching to Demo
 * would quietly stop the very automation the demo depends on.
 *
 * The poll reads a local directory through the loopback API once a second: it costs nothing and
 * wakes nothing. Captures already in the inbox when the studio opened are history and are never
 * processed automatically.
 *
 * In a hosted build none of this runs. The server processes every capture, so there is nothing
 * to automate and nothing may be started from a public page: the list is mirrored from
 * `hosted-demo.ts`, Auto stays off, and `processCapture` refuses.
 */

import { useSyncExternalStore } from "react";
import { HOSTED } from "./hosted";
import { hostedDemo, startHostedDemo } from "./hosted-demo";
import { extractClipFrames } from "./load-clip";
import { listPhoneCaptures, loadPhoneCapture, phoneInfo, type PhoneCapture, type PhoneInfo } from "./phone-link";
import { canRun, clearRunReadout, runInference } from "./run-inference";

export interface AutoFlags {
  load: boolean;
  extract: boolean;
  run: boolean;
}

export interface PhoneAutoState {
  info: PhoneInfo | null;
  captures: PhoneCapture[];
  auto: AutoFlags;
  /** The capture being loaded, extracted or run right now. */
  busyId: string | null;
  /** How the last loaded capture asked to be sampled, after clamping to the card. */
  planNote: string | null;
  error: string | null;
}

const AUTO_KEY = "verge.phone.auto";

function readAuto(): AutoFlags {
  if (HOSTED) return { load: false, extract: false, run: false };
  try {
    const parsed = JSON.parse(localStorage.getItem(AUTO_KEY) ?? "{}") as Partial<AutoFlags>;
    // Run is never remembered: a reload must not leave a box ticked that spends money.
    return { load: parsed.load === true, extract: parsed.extract === true, run: false };
  } catch {
    return { load: false, extract: false, run: false };
  }
}

let state: PhoneAutoState = {
  info: null,
  captures: [],
  auto: readAuto(),
  busyId: null,
  planNote: null,
  error: null,
};
const listeners = new Set<() => void>();

function set(patch: Partial<PhoneAutoState>): void {
  state = { ...state, ...patch };
  for (const listener of listeners) listener();
}

export function getPhoneAuto(): PhoneAutoState {
  return state;
}

export function usePhoneAuto(): PhoneAutoState {
  ensurePolling();
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state,
    () => state,
  );
}

export function setAuto(next: AutoFlags): void {
  if (HOSTED) return;
  // Each step needs the one before it.
  const flags = { load: next.load, extract: next.load && next.extract, run: next.load && next.extract && next.run };
  set({ auto: flags });
  try {
    localStorage.setItem(AUTO_KEY, JSON.stringify({ load: flags.load, extract: flags.extract }));
  } catch {
    // private mode: the flags just do not persist
  }
}

/** Load a capture, then extract and run as asked. Shared by the buttons and by Auto. */
export async function processCapture(id: string, steps: AutoFlags): Promise<void> {
  if (HOSTED) {
    set({ error: "the demo site only shows captures; the server processes them" });
    return;
  }
  set({ busyId: id, error: null });
  try {
    const capture = state.captures.find((c) => c.id === id);
    const loaded = await loadPhoneCapture(id, capture?.sampling);
    set({ planNote: loaded.plan });
    clearRunReadout();
    if (steps.extract) await extractClipFrames();
    if (steps.extract && steps.run && canRun()) await runInference();
  } catch (e) {
    set({ error: e instanceof Error ? e.message : String(e) });
  } finally {
    set({ busyId: null });
  }
}

export function forgetCapture(id: string): void {
  set({ captures: state.captures.filter((c) => c.id !== id) });
}

let polling = false;
let seen: Set<string> | null = null;

function ensurePolling(): void {
  if (polling || typeof window === "undefined") return;
  polling = true;
  if (HOSTED) {
    followHosted();
    return;
  }
  phoneInfo().then(
    (info) => set({ info }),
    (e) => set({ error: e instanceof Error ? e.message : String(e) }),
  );
  const tick = async () => {
    try {
      const list = await listPhoneCaptures();
      set({ captures: list });
      if (seen === null) {
        seen = new Set(list.filter((c) => c.video).map((c) => c.id));
      } else {
        const arrived = list.filter((c) => c.video && !seen!.has(c.id));
        for (const c of arrived) seen.add(c.id);
        const newest = arrived[0];
        if (newest && state.auto.load && state.busyId === null) void processCapture(newest.id, state.auto);
      }
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
    window.setTimeout(tick, 1000);
  };
  void tick();
}

/** Hosted: one poll, owned by `hosted-demo.ts`; this store only mirrors what it read. */
function followHosted(): void {
  const demo = hostedDemo();
  phoneInfo().then(
    (info) => set({ info }),
    (e) => set({ error: e instanceof Error ? e.message : String(e) }),
  );
  const mirror = () => {
    const hosted = demo.getState();
    if (hosted.captures !== state.captures || hosted.error !== state.error) {
      set({ captures: hosted.captures, error: hosted.error });
    }
  };
  demo.subscribe(mirror);
  mirror();
  startHostedDemo();
}
