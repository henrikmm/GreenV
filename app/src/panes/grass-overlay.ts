/**
 * The grass grid, drawn where it was measured.
 *
 * The JSON says a cell 4.25 m along and 0.75 m out reads 1.18 m. That sentence is only
 * checkable if the cell is a square you can see, standing on the ground it was measured
 * against, next to the cells either side of it. So this draws the band, the cells, and — for
 * one selected cell — the points that produced its number.
 *
 * ## Height is data, so it may have a ramp; status is not, so it may not
 *
 * DESIGN.md forbids hue carrying state, and a cell's H95 is not state, it is a measurement.
 * It still gets a NEUTRAL brightness ramp rather than a colour one, for two reasons: the pane
 * already spends its hues on the port legend and the floor layers, and a brightness ramp
 * survives the greyscale screenshot that acceptance item 2 is graded on.
 *
 * An abstained cell is a different KIND of thing, not a dimmer version of a measured one, so
 * it is drawn as an outline with no fill. That reads as "nothing was claimed here", which is
 * what abstention means, and it cannot be confused with a short-grass cell.
 */

import * as THREE from "three";
import type { GrassHeightAssessmentV1, Plane, Vec3 } from "../../../geometry";
import { buildBandSegments, cellQuad } from "../measurement/grass-grid";

/** The band's rails and rungs: the one place the road-edge assumption is visible. */
const BAND_HUE = "#f3c969"; // --port-plane: the band is a claim about the ground
const SELECTED_HUE = "#f4f4f6"; // --emph-hi: the highlighted thing, per DESIGN.md
const ABSTAIN_HUE = "#8a8a90"; // --text-dim: a cell that claims nothing states it quietly

export interface GrassOverlayInput {
  assessment: GrassHeightAssessmentV1;
  polyline: Vec3[];
  plane: Plane;
  /** `${alongIndex},${distIndex}`, or null. */
  selectedCell: string | null;
  layers: { band: boolean; cells: boolean };
}

/**
 * Height to a neutral 0..1 brightness, over the range this run actually produced.
 *
 * Ranged over the run rather than over a fixed 0–1 m, because a mown verge and an unmown one
 * differ by less than the ramp would then resolve. The legend states the range for exactly
 * this reason: without it the shade means nothing.
 */
export function heightShade(h95M: number, min: number, max: number): number {
  if (!Number.isFinite(h95M)) return 0;
  const span = max - min;
  if (!(span > 1e-9)) return 0.75;
  return Math.min(1, Math.max(0, (h95M - min) / span));
}

/** Cell key as the store and the measurement both spell it. */
export function cellKey(alongRoadM: number, distanceFromRoadM: number, cellSizeM: number): string {
  return `${Math.round(alongRoadM / cellSizeM - 0.5)},${Math.round(distanceFromRoadM / cellSizeM - 0.5)}`;
}

/**
 * Rebuild the overlay into `group`, replacing whatever it held.
 *
 * The caller disposes the old children — this only adds — because the pane already owns a
 * disposal routine that walks whole subtrees, and two of them would eventually disagree.
 */
export function buildGrassOverlay(group: THREE.Group, input: GrassOverlayInput): void {
  const { assessment, polyline, plane, selectedCell, layers } = input;
  const cellSizeM = assessment.band.cellSizeM;

  if (layers.band && polyline.length >= 2) {
    const segments = buildBandSegments(polyline, plane, assessment.band.maxDistanceFromRoadM, 1);
    if (segments.length > 0) {
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute("position", new THREE.BufferAttribute(segments, 3));
      const band = new THREE.LineSegments(
        geometry,
        new THREE.LineBasicMaterial({ color: BAND_HUE, transparent: true, opacity: 0.55 }),
      );
      band.name = "grass-band";
      group.add(band);
    }
  }

  if (layers.cells) {
    const range = assessment.reviewEvidence.h95RangeM;
    const min = range?.min ?? 0;
    const max = range?.max ?? 1;

    const filled: number[] = [];
    const shades: number[] = [];
    const outlines: number[] = [];
    const highlight: number[] = [];

    for (const cell of assessment.measurements) {
      const quad = cellQuad(
        polyline,
        plane,
        cell.coordinate.alongRoadM,
        cell.coordinate.distanceFromRoadM,
        // Slightly inset, so neighbouring cells read as separate squares rather than a sheet.
        cellSizeM * 0.92,
      );
      const key = cellKey(cell.coordinate.alongRoadM, cell.coordinate.distanceFromRoadM, cellSizeM);
      const isSelected = key === selectedCell;

      if (cell.status === "measured") {
        const shade = 0.25 + 0.75 * heightShade(cell.h95M as number, min, max);
        // Two triangles, per-vertex colour, one draw call for the whole grid.
        for (const [a, b, c] of [
          [0, 1, 2],
          [0, 2, 3],
        ]) {
          for (const corner of [quad[a], quad[b], quad[c]]) {
            filled.push(corner[0], corner[1], corner[2]);
            shades.push(shade, shade, shade);
          }
        }
      } else {
        for (let i = 0; i < 4; i++) {
          const from = quad[i];
          const to = quad[(i + 1) % 4];
          outlines.push(from[0], from[1], from[2], to[0], to[1], to[2]);
        }
      }

      if (isSelected) {
        for (let i = 0; i < 4; i++) {
          const from = quad[i];
          const to = quad[(i + 1) % 4];
          highlight.push(from[0], from[1], from[2], to[0], to[1], to[2]);
        }
      }
    }

    if (filled.length > 0) {
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute("position", new THREE.Float32BufferAttribute(filled, 3));
      geometry.setAttribute("color", new THREE.Float32BufferAttribute(shades, 3));
      const mesh = new THREE.Mesh(
        geometry,
        new THREE.MeshBasicMaterial({
          vertexColors: true,
          transparent: true,
          opacity: 0.55,
          side: THREE.DoubleSide,
          depthWrite: false,
        }),
      );
      mesh.name = "grass-cells";
      group.add(mesh);
    }

    if (outlines.length > 0) {
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute("position", new THREE.Float32BufferAttribute(outlines, 3));
      const abstained = new THREE.LineSegments(
        geometry,
        new THREE.LineBasicMaterial({ color: ABSTAIN_HUE, transparent: true, opacity: 0.6 }),
      );
      abstained.name = "grass-abstained";
      group.add(abstained);
    }

    if (highlight.length > 0) {
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute("position", new THREE.Float32BufferAttribute(highlight, 3));
      const selected = new THREE.LineSegments(
        geometry,
        // Drawn over everything: it answers "which cell am I reading", and a highlight the
        // cloud can hide is not one.
        new THREE.LineBasicMaterial({ color: SELECTED_HUE, depthTest: false }),
      );
      selected.name = "grass-selected-cell";
      selected.renderOrder = 3;
      group.add(selected);
    }
  }
}
