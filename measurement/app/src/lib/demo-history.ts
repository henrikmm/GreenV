/**
 * The Demo panel's history on the Mac: earlier captures, and the run each one produced.
 *
 * The hosted site has had this since 2026-10-21 (`hosted-demo.ts`), reading the API's list. On the
 * Mac the captures are the intake's inbox and the runs are this studio's registry, so the one new
 * fact is which run belongs to which capture. Captures since 2026-10-23 carry it: run-inference
 * reports `runId` with `inferred`. Earlier ones are matched by time — the run registered between
 * the capture's `inferred` and `fetched`, which is when this studio registers one.
 *
 * Opening an old capture points the measurement branch at its run exactly as the Runs pane does,
 * and that needs the run on disk. An unsaved run is offered as "salvar e abrir" and never saved
 * by a click meant for something else: nothing is kept unless somebody asks (AGENTS.md).
 */

import { useSyncExternalStore } from "react";
import { FIXTURE_RUN_ID } from "../graph/nodes";
import { runAuto, setNodeParams } from "../graph/graph-store";
import type { PhoneCapture } from "./phone-link";
import { saveRun, type RunRecord } from "./runs";
import { refreshRuns } from "./runs-store";

/** How far either side of the capture's own events a run's registration may fall. */
const MATCH_SLACK_MS = 10_000;

/** The run a capture produced, if this studio still lists it. */
export function runForCapture(capture: PhoneCapture, runs: readonly RunRecord[]): RunRecord | null {
  const inferred = capture.events.find((e) => e.phase === "inferred");
  const told = inferred?.detail?.runId;
  if (typeof told === "string") return runs.find((r) => r.id === told) ?? null;
  if (!inferred) return null;
  const from = Date.parse(inferred.at) - MATCH_SLACK_MS;
  const fetched = capture.events.find((e) => e.phase === "fetched");
  const to = Date.parse(fetched?.at ?? inferred.at) + MATCH_SLACK_MS;
  const candidates = runs.filter((r) => {
    const at = Date.parse(r.createdAt);
    return !r.builtin && at >= from && at <= to;
  });
  // Two runs in one window means two pages ran the same capture; refusing to guess is safer
  // than opening the wrong cloud under a visitor's name.
  return candidates.length === 1 ? candidates[0]! : null;
}

interface State {
  /** The capture on screen by choice, or null to follow whatever is live. */
  pinnedId: string | null;
  busy: boolean;
  error: string | null;
}

let state: State = { pinnedId: null, busy: false, error: null };
const listeners = new Set<() => void>();

function set(patch: Partial<State>): void {
  state = { ...state, ...patch };
  for (const listener of listeners) listener();
}

export function useDemoHistory(): State {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state,
    () => state,
  );
}

function showRun(runId: string): void {
  setNodeParams(FIXTURE_RUN_ID, { runId, source: "recorded" });
  void runAuto();
}

/**
 * Put an earlier capture on screen. `save` is the explicit "salvar e abrir": the run is
 * downloaded to `~/verge-runs` first, because only a saved run can be opened.
 */
export async function openCapture(capture: PhoneCapture, run: RunRecord | null, save = false): Promise<void> {
  set({ pinnedId: capture.id, error: null });
  if (!run) return;
  if (run.persisted) {
    showRun(run.id);
    return;
  }
  if (!save) return;
  set({ busy: true });
  try {
    await saveRun(run.id);
    await refreshRuns();
    showRun(run.id);
  } catch (e) {
    set({ error: e instanceof Error ? e.message : String(e) });
  } finally {
    set({ busy: false });
  }
}

/** A new capture is taking the screen; whatever it shows next is live, so nothing is pinned. */
export function releasePin(): void {
  if (state.pinnedId !== null) set({ pinnedId: null, error: null });
}

/** Back to the live capture: the session's own run, as the Runs pane's "This session's run". */
export function followLive(): void {
  if (state.pinnedId === null) return;
  set({ pinnedId: null, error: null });
  setNodeParams(FIXTURE_RUN_ID, { source: "live" });
  void runAuto();
}
