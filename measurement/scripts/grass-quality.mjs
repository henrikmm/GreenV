// Quality is an evidence ledger, never a probability that the centimetres are correct.
export const QUALITY_SCHEMA = "verge.grass-quality/0.1.0";

export function encodeRuns(values) {
  const runs = [];
  for (let i = 0; i < values.length;) {
    if (!values[i]) { i++; continue; }
    const start = i;
    while (i < values.length && values[i]) i++;
    runs.push(start, i - start);
  }
  return runs;
}
export function decodeRuns(runs, length) {
  const data = new Uint8Array(length);
  if (runs.length % 2) throw new Error("invalid mask runs");
  let end = 0;
  for (let i = 0; i < runs.length; i += 2) {
    const [start, count] = [runs[i], runs[i + 1]];
    if (!Number.isInteger(start) || !Number.isInteger(count) || start < end || count <= 0 || start + count > length) throw new Error("invalid mask runs");
    data.fill(1, start, start + count); end = start + count;
  }
  return data;
}
export function roadContext(value = {}) {
  const text = (v) => typeof v === "string" && v.trim() ? v.trim() : null;
  if (value.km != null && (!Number.isFinite(value.km) || value.km < 0)) throw new Error("km must be nonnegative kilometres or null");
  if (value.capturado_em != null && !Number.isFinite(Date.parse(value.capturado_em))) throw new Error("capturado_em must be an ISO timestamp or null");
  return { rodovia: text(value.rodovia), sentido: text(value.sentido), km: value.km ?? null, capturado_em: text(value.capturado_em) };
}

export function qualitySummary(assessment, frames, context, ground, corridor, scale = null) {
  const measured = assessment?.measurements.filter((c) => c.status === "measured") ?? [];
  const empty = frames.filter((f) => f.status === "no-grass-detected").length;
  const errors = frames.filter((f) => f.status === "failed").length;
  const blockers = ["physical-height-unvalidated", "semantic-mask-unvalidated", "local-ground-unvalidated", "mowing-policy-unconfirmed"];
  if (Object.values(context).some((v) => v === null)) blockers.push("road-metadata-missing");
  if (corridor.source !== "surveyed") blockers.push("road-boundary-assumed");
  if (!ground.available) blockers.push("ground-unavailable");
  // The floor was found only at twice the tolerance: a wet road, a thin ground. Say so.
  if (ground.relaxedFit) blockers.push("ground-fit-relaxed");
  // The reconstruction never saw the vehicle move: nothing in the band is a verge.
  if (corridor.status === "degenerate") blockers.push("camera-track-too-short");
  // A scale anchor was asked for and could not be honoured, so the metres are the model's own.
  if (scale?.requested && !scale.applied) blockers.push("scale-anchor-unusable");
  if (errors) blockers.push("frame-processing-failed");
  if (!measured.length) blockers.push("no-measurable-cells");
  // Unsigned distances fold both sides together. Do not manufacture an area denominator.
  blockers.push("intended-area-coverage-unknown");
  return {
    operationalStatus: "not-ready", blockers,
    frames: { total: frames.length, processed: frames.length - errors, empty, failed: errors },
    measuredCells: measured.length,
    abstainedCells: assessment?.reviewEvidence.abstainedCellCount ?? 0,
    // Cells taller than a verge can be, kept in the packet with their numbers and counted here.
    canopyCells: assessment?.reviewEvidence.canopyCellCount ?? 0,
    // Cells from a slope's foot outward: the embankment, kept with their numbers, aggregated nowhere.
    slopeCells: assessment?.reviewEvidence.slopeCellCount ?? 0,
    // Cells enough frames saw a fence, a wall, a pole or a building standing in: the same.
    structureCells: assessment?.reviewEvidence.structureCellCount ?? 0,
    observedCellCoverage: assessment?.reviewEvidence.coverageFraction ?? 0,
    intendedAreaCoverage: null,
    missingAreaMeaning: "unknown: unobserved, occluded, excluded or no grass; not short grass",
    h95SpreadMaxM: measured.reduce((n, c) => Math.max(n, c.h95SpreadM ?? 0), 0),
    uncertaintyMeaning: "between-frame disagreement only; not a calibrated error interval",
    // Fractions are over measured cells only; these are sensitivity summaries, not priorities.
    thresholdExploration: [0.1, 0.3].map((heightM) => ({ heightM,
      // On the default reading: height above each cell's own ground, not above the plane.
      measuredCellsAbove: measured.filter((c) => c.extent95M > heightM).length,
      measuredCellCount: measured.length, policyStatus: "draft-not-motiva-approved" })),
  };
}

export function compareAssessments(automatic, human) {
  const key = (c) => `${c.coordinate.alongRoadM},${c.coordinate.distanceFromRoadM}`;
  const all = new Map();
  for (const c of automatic.measurements) all.set(key(c), { automatic: c });
  for (const c of human.measurements) all.set(key(c), { ...all.get(key(c)), human: c });
  return [...all].map(([cell, pair]) => ({ cell,
    automaticStatus: pair.automatic?.status ?? "not-observed",
    humanStatus: pair.human?.status ?? "not-observed",
    deltaH95M: pair.automatic?.h95M != null && pair.human?.h95M != null ? pair.automatic.h95M - pair.human.h95M : null,
    deltaExtent95M: pair.automatic?.extent95M != null && pair.human?.extent95M != null ? pair.automatic.extent95M - pair.human.extent95M : null,
    thresholdChanges: [0.1, 0.3].map((heightM) => ({ heightM,
      changed: pair.automatic?.extent95M != null && pair.human?.extent95M != null ? (pair.automatic.extent95M > heightM) !== (pair.human.extent95M > heightM) : null })),
  }));
}
