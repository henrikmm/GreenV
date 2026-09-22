/**
 * Sorting a dropped selection, and saying what is wrong with it.
 *
 * These decide what an operator is told when an import cannot proceed, which is the part of this
 * feature they will actually meet — a correct importer with a useless message is a feature nobody
 * can use. Asserted rather than eyeballed because every branch below is a mistake somebody makes
 * once: dropping half the pair, dropping two runs together, dropping the frames and forgetting
 * the geometry.
 */

import { describe, expect, it } from "vitest";
import { importReadiness, sortImportFiles } from "./runs";

/** Only the name matters: nothing here reads a byte. */
const named = (...names: string[]) => names.map((name) => ({ name }));

const readinessOf = (...names: string[]) => importReadiness(sortImportFiles(named(...names)));

describe("sorting a dropped selection", () => {
  it("files each extension where it belongs, case regardless", () => {
    const selection = sortImportFiles(
      named("scene.GLB", "result.npz", "frame-0001.jpg", "frame-0002.JPEG", "notes.txt"),
    );
    expect(selection.glb.map((f) => f.name)).toEqual(["scene.GLB"]);
    expect(selection.npz.map((f) => f.name)).toEqual(["result.npz"]);
    expect(selection.frames).toHaveLength(2);
    expect(selection.ignored.map((f) => f.name)).toEqual(["notes.txt"]);
  });

  it("puts an extensionless file out of the way rather than guessing at it", () => {
    expect(sortImportFiles(named("scene")).ignored.map((f) => f.name)).toEqual(["scene"]);
  });
});

describe("what an operator is told", () => {
  it("accepts the pair, and says plainly that no frames came with it", () => {
    const { ready, detail } = readinessOf("scene.glb", "result.npz");
    expect(ready).toBe(true);
    expect(detail).toContain("no frames");
    // The consequence, not just the absence — this is the whole difference it makes.
    expect(detail).toContain("photo colour and mask painting will be unavailable");
  });

  it("accepts the pair with its frames, and counts them", () => {
    const { ready, detail } = readinessOf("scene.glb", "result.npz", "a.jpg", "b.jpg");
    expect(ready).toBe(true);
    expect(detail).toContain("2 frames");
  });

  it("names which half of the pair is missing, not merely that something is", () => {
    expect(readinessOf("scene.glb")).toMatchObject({ ready: false });
    expect(readinessOf("scene.glb").detail).toContain("No .npz");
    expect(readinessOf("result.npz").detail).toContain("No .glb");
  });

  it("refuses two runs at once instead of silently importing one of them", () => {
    const { ready, detail } = readinessOf("a.glb", "b.glb", "result.npz");
    expect(ready).toBe(false);
    expect(detail).toContain("One run at a time");
  });

  it("asks for the pair when the selection holds neither", () => {
    expect(readinessOf("frame-0001.jpg").detail).toContain("Drop a .glb and a .npz");
  });

  it("says how much it is ignoring, so a mis-drop is visible", () => {
    const { detail } = readinessOf("scene.glb", "result.npz", "notes.txt", "SHA256SUMS");
    expect(detail).toContain("Ignoring 2 other files");
  });

  it("nothing at all is the same question as half a pair", () => {
    expect(importReadiness(sortImportFiles([]))).toMatchObject({ ready: false });
  });
});
