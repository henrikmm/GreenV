/**
 * Opening an old capture shows a run, so the one claim worth pinning is that the run is the
 * capture's own: told by the capture when it can be, matched by time only when unambiguous.
 */

import { describe, expect, it } from "vitest";
import { runForCapture } from "./demo-history";
import type { PhoneCapture } from "./phone-link";
import type { RunRecord } from "./runs";

function capture(events: PhoneCapture["events"]): PhoneCapture {
  return {
    id: "cap-1",
    state: "fetched",
    capturedAt: null,
    durationMs: null,
    settings: null,
    applied: null,
    orbit: null,
    truth: null,
    sampling: null,
    video: null,
    events,
  };
}

function run(id: string, createdAt: string, patch: Partial<RunRecord> = {}): RunRecord {
  return { id, label: id, clipName: "", clipSha256: "", createdAt, source: "cloud", builtin: false, persisted: false, available: true, sizeBytes: 0, ...patch } as RunRecord;
}

const at = (s: number) => new Date(Date.UTC(2026, 9, 23, 2, 50, s)).toISOString();

describe("runForCapture", () => {
  it("takes the run the capture names", () => {
    const c = capture([{ phase: "inferred", at: at(30), tMs: 30_000, detail: { runId: "b" } }]);
    expect(runForCapture(c, [run("a", at(31)), run("b", at(59))])?.id).toBe("b");
  });

  it("matches by time between inferred and fetched when the capture predates runId", () => {
    const c = capture([
      { phase: "inferred", at: at(30), tMs: 30_000 },
      { phase: "fetched", at: at(37), tMs: 37_000 },
    ]);
    expect(runForCapture(c, [run("early", at(0)), run("mine", at(31))])?.id).toBe("mine");
  });

  it("refuses to guess between two runs in one window, and never offers a built-in", () => {
    const c = capture([{ phase: "inferred", at: at(30), tMs: 30_000 }]);
    expect(runForCapture(c, [run("x", at(31)), run("y", at(33))])).toBeNull();
    expect(runForCapture(c, [run("door", at(31), { builtin: true })])).toBeNull();
  });

  it("has nothing for a capture that never reached the GPU", () => {
    expect(runForCapture(capture([{ phase: "extracted", at: at(3), tMs: 3_000 }]), [run("a", at(3))])).toBeNull();
  });
});
