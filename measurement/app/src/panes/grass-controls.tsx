/**
 * Starting a grass-grid run, and watching it happen.
 *
 * Advanced only, and a development instrument rather than part of the app's job: it exists so
 * somebody can look at the band, the cells and the pixels behind them and decide whether the
 * JSON is describing grass. When the pipeline ships it will have no reviewer, so what is being
 * checked here is whether the automatic parts can be trusted without one.
 *
 * Two honesty rules govern the readout, and both cost something to obey:
 *
 *  - The only proportion drawn is frames done over frames given, and both are counted by the
 *    calculation itself. There is no estimate of time remaining, because nothing here can
 *    measure one — the elapsed clock is elapsed time and says so.
 *  - The band comes from the camera path, which is not the road edge. Every state of this
 *    panel that shows a measurement also states that, because a band placed by an assumption
 *    produces numbers that inherit it.
 */

import { useEffect, useState } from "react";
import { useGraph } from "../graph/graph-store";
import { activeMeasurementObject, useMeasurementUi } from "../measurement/measurement-store";
import { grassElapsedMs, useGrassRun, clearGrassRun, selectGrassCell } from "../measurement/grass-grid-store";
import { planGrassRun, startGrassRun } from "../measurement/grass-run";
import { cellKey } from "./grass-overlay";
import { HelpDot, HelpTerm } from "./help";

/** Metres the band is pushed sideways from the camera path. */
const OFFSET_MIN = -6;
const OFFSET_MAX = 6;

function seconds(ms: number | null): string {
  if (ms === null) return "—";
  return `${(ms / 1000).toFixed(1)} s`;
}

export function GrassControls() {
  const graph = useGraph();
  const ui = useMeasurementUi();
  const run = useGrassRun();
  const [offsetM, setOffsetM] = useState(2);
  const [tick, setTick] = useState(0);

  const target = activeMeasurementObject();
  const plan = planGrassRun(graph, target?.id ?? null);
  const running = run.status === "running";

  // The elapsed clock has to advance while a frame is being folded in, and nothing else in the
  // store changes during that. One second is the resolution the readout shows.
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setTick((n) => n + 1), 250);
    return () => window.clearInterval(timer);
  }, [running]);
  void tick;

  const elapsed = grassElapsedMs(run);
  const progress = run.progress;
  const assessment = run.assessment;
  const evidence = assessment?.reviewEvidence;

  const start = () => {
    if (!plan.ready || !target) return;
    void startGrassRun(graph, target.id, offsetM).catch(() => {
      // The store publishes the failure; this only stops an unhandled rejection.
    });
  };

  return (
    <>
      <div className="output-row">
        <span className="output-label">GRASS GRID</span>
        <HelpDot label="What the grass grid is">
          Measures grass height per half-metre cell in a band beside the road, from the frames of
          this target that carry a painted mask. It is unvalidated: no lawn height has ever been
          taped in this project, and nothing here changes that.
        </HelpDot>
        <button className="chip-toggle" onClick={start} disabled={!plan.ready || running}>
          {running ? "Measuring…" : "Measure band"}
        </button>
        <label className="grass-offset">
          <HelpTerm
            help={
              <>
                How far sideways the band is pushed from the camera path, in metres. Positive is
                one side of travel, negative the other. It exists because the camera path is not
                the road edge, and nothing here knows the difference.
              </>
            }
          >
            Band offset
          </HelpTerm>
          <input
            type="range"
            min={OFFSET_MIN}
            max={OFFSET_MAX}
            step={0.25}
            value={offsetM}
            disabled={running}
            onChange={(event) => setOffsetM(Number(event.target.value))}
          />
          <span className="mono">{offsetM.toFixed(2)} m</span>
        </label>
        {run.status !== "idle" && (
          <button className="chip-toggle" onClick={clearGrassRun} disabled={running}>
            Clear
          </button>
        )}
      </div>

      {!plan.ready && (
        <div className="output-row">
          <span className="output-label">CANNOT RUN</span>
          <span className="mono grass-dim">{plan.blocker}</span>
        </div>
      )}

      {plan.ready && run.status === "idle" && (
        <div className="output-row">
          <span className="output-label">READY</span>
          <span className="mono">
            {plan.maskedFrames.length} masked / {plan.framesOffered} frames · target {target?.name ?? "—"}
          </span>
        </div>
      )}

      {progress && (
        <div className="output-row">
          <span className="output-label">{running ? "◐ READING" : "● READ"}</span>
          <span className="mono">
            frame {progress.framesDone}/{progress.frameTotal}
          </span>
          {/* The one proportion in this panel, and both of its terms are counted. */}
          {/* Counted over counted: frames folded in, out of frames handed over. The same rule
              the upload bar follows, and the only proportion this panel is entitled to draw. */}
          <span className="phase-track grass-bar" aria-hidden>
            <span
              className="phase-fill"
              style={{ width: `${(progress.framesDone / progress.frameTotal) * 100}%` }}
            />
          </span>
          <span className="mono grass-dim">
            {progress.observationsRetained.toLocaleString()} pts · {progress.cellsTouched} cells ·{" "}
            {seconds(elapsed)} elapsed
          </span>
        </div>
      )}

      {run.roadEdge && (
        <div className="output-row">
          <span className="output-label">BAND FROM</span>
          <span className="mono">
            camera path {run.roadEdge.source.offsetM >= 0 ? "+" : ""}
            {run.roadEdge.source.offsetM.toFixed(2)} m · {run.roadEdge.source.lengthM.toFixed(1)} m long ·{" "}
            {run.roadEdge.source.keptPoses}/{run.roadEdge.source.sourcePoses} poses
          </span>
          <HelpDot label="Where the band comes from">
            Nothing in this project segments a road, so the band is placed from the reconstructed
            camera path — where the vehicle drove — pushed sideways by the offset. It is an
            assumption, not a measurement, and every number measured inside it inherits that.
          </HelpDot>
        </div>
      )}

      {run.status === "failed" && (
        <div className="output-row">
          <span className="output-label">▲ FAILED</span>
          <span className="mono grass-warn">{run.error}</span>
        </div>
      )}

      {assessment && evidence && (
        <>
          <div className="output-row">
            <span className="output-label">● CELLS</span>
            <span className="mono">
              {evidence.measuredCellCount} measured · {evidence.abstainedCellCount} abstained ·{" "}
              {(evidence.coverageFraction * 100).toFixed(0)}% of observed cells measured
            </span>
            {evidence.h95RangeM && (
              <span className="mono grass-dim">
                H95 {evidence.h95RangeM.min.toFixed(3)}–{evidence.h95RangeM.max.toFixed(3)} m
              </span>
            )}
          </div>
          <div className="output-row">
            <span className="output-label">INSPECT</span>
            {evidence.samples.map((sample) => {
              const key = cellKey(
                sample.coordinate.alongRoadM,
                sample.coordinate.distanceFromRoadM,
                assessment.band.cellSizeM,
              );
              return (
                <button
                  key={`${sample.reason}-${key}`}
                  className={`chip-toggle${run.selectedCell === key ? " on" : ""}`}
                  aria-pressed={run.selectedCell === key}
                  title={`${sample.coordinate.alongRoadM.toFixed(2)} m along, ${sample.coordinate.distanceFromRoadM.toFixed(2)} m out · frames ${sample.frameIndices.join(", ")}`}
                  onClick={() => selectGrassCell(key)}
                >
                  {sample.reason.replace("-h95", "").replace("-", " ")}
                </button>
              );
            })}
          </div>
          <div className="output-row">
            <span className="output-label">UNVALIDATED</span>
            <span className="mono grass-dim">
              {assessment.validationStatus} · review {assessment.review.status} · frame {ui.canonicalFrame} shown
            </span>
          </div>
        </>
      )}
    </>
  );
}
