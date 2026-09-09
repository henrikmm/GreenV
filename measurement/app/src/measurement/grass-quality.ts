import type { GrassHeightAssessmentV1, GrassCellMeasurement, Plane, Vec3 } from '../../../geometry';
export interface SemanticMaskProvenance {
  kind: 'semantic'; modelId: string; modelRevision: string; runtime: string; device: 'cpu';
  probabilityFloor: number; counts: { total: number; grassWins: number; kept: number; droppedToFloor: number };
  inferenceMs: number; modelLoadMs: number;
}
export interface GrassQualityBundle {
  schemaVersion: 'verge.grass-quality/0.1.0'; runId: string; contentSha256: string;
  assessment: GrassHeightAssessmentV1 | null;
  ground: { plane: Plane | null };
  corridor: { polyline: Vec3[]; offsetM: number; lengthM: number | null };
  timing: { totalMs: number };
  frames: Array<{ frameIndex: number; canonicalFrame: number; status: string; sourceWidth: number; sourceHeight: number; depthWidth: number; depthHeight: number;
    mask?: { width: number; height: number; runs: number[] }; semantic?: SemanticMaskProvenance }>;
  cells: Array<GrassCellMeasurement & { key: string; pixels: Array<{ frameIndex: number; runs: number[] }> }>;
  quality: { blockers: string[]; measuredCells: number; abstainedCells: number; frames: { total: number; empty: number; failed: number } };
}
export function decodeQualityMask(runs: number[], length: number): Uint8Array {
  const data = new Uint8Array(length);
  if (runs.length % 2) throw new Error('invalid mask runs');
  let previousEnd = 0;
  for (let i = 0; i < runs.length; i += 2) {
    const start = runs[i], count = runs[i + 1];
    if (!Number.isInteger(start) || !Number.isInteger(count) || start < previousEnd || count <= 0 || start + count > length) throw new Error('invalid mask runs');
    data.fill(1, start, start + count); previousEnd = start + count;
  }
  return data;
}
