/**
 * Runs pane — what evidence exists, what it costs on disk, and how to get rid of it.
 *
 * The storage policy in CLAUDE.md is "transient by default, persist only on explicit Save".
 * Before this pane the second half was unimplemented: there was no Save, no Delete, and no way
 * to see what a run had consumed. `scripts/save-run.sh` was the whole story, run by hand
 * during the exact window when a GPU instance is billing.
 */

import { useRef, useState } from "react";
import { refreshRuns, useRuns } from "../lib/runs-store";
import {
  deleteRun,
  estimateSaveBytes,
  formatBytes,
  importReadiness,
  importRun,
  saveRun,
  sortImportFiles,
  type RunRecord,
} from "../lib/runs";
import { removeObservationsForRun } from "../measurement/measurement-store";
import { setNodeParam, runAuto, useGraph } from "../graph/graph-store";
import { FIXTURE_RUN_ID } from "../graph/nodes";
import { DEFAULT_RUN_ID } from "../graph/nodes/fixture-run";
import { PaneControls } from "./pane-chrome";
import { HelpDot } from "./help";

function RunRow({
  run,
  active,
  busy,
  onSelect,
  onSave,
  onDelete,
}: {
  run: RunRecord;
  active: boolean;
  busy: boolean;
  onSelect: () => void;
  onSave: () => void;
  onDelete: () => void;
}) {
  const estimate = estimateSaveBytes(run);
  /**
   * Where an unsaved run's bytes actually are, which changed on 2026-08-06 and used to be
   * one answer for everybody: "on the instance, dies with it".
   *
   * Saving is still the only way to KEEP a run. What differs now is the consequence of not
   * saving yet, and a "degraded" run is the case that must not look like the healthy one —
   * it carries exactly the old urgency while sitting in a list where nothing else does.
   */
  const publishMode = run.manifest?.diagnostics?.publishMode;
  const degraded = !run.persisted && publishMode === "degraded";
  const saveHint = run.persisted
    ? ""
    : degraded
      ? `Download this run's artifacts to disk — about ${formatBytes(estimate)}. Publishing to durable storage FAILED for this run, so they exist only on the cloud instance and die with it. Save before teardown.`
      : publishMode === "gcs"
        ? `Download this run's artifacts to disk — about ${formatBytes(estimate)}. They are in durable storage and survive teardown, but only until the bucket expires them in ${run.manifest?.expiresAfterDays ?? 3} days.`
        : `Download this run's artifacts to disk — about ${formatBytes(estimate)}. Until then they exist only on the cloud instance and die with it.`;
  return (
    <div className={`run-row${active ? " active" : ""}`}>
      <button className="run-pick" onClick={onSelect} disabled={!run.persisted || !run.available}>
        <span className="run-label">
          <b>{run.label}</b>
          <small>
            {/*
              An imported run has no clip to name — it arrived as geometry, not as video — so it
              says where it came from instead of showing an em dash. "no frames" is on the same
              line rather than marked like a degraded run, because it is a property of the run
              and not something to act on: the geometry is complete, the photographs are absent.
            */}
            {run.source === "import"
              ? run.clipName || (run.framesAvailable === false ? "imported · no frames" : "imported")
              : run.clipName || "—"}
            {run.frameCount ? ` · ${run.frameCount}f` : ""}
            {run.processRes ? ` · ${run.processRes} px` : ""}
          </small>
        </span>
        <span className="run-state mono">
          {run.builtin
            ? "built-in"
            : run.persisted
              ? run.available
                ? formatBytes(run.sizeBytes)
                : "payload missing"
              : "transient"}
        </span>
      </button>
      <span className="run-actions">
        {/*
          Outside the run-pick button on purpose. A transient run cannot be selected, so that
          button is disabled and rendered at opacity 0.5 — and a warning inside it inherited the
          dimming, which put the one row that urgently needs action at half the contrast of every
          calm row beside it. Measured 2026-08-06 during the design review. It sits next to Save
          because Save is the action it is asking for.
        */}
        {/*
          The glyph alone, not "▲ instance only". Spelling it out cost 224 px of a 335 px row and
          squeezed the run label down to "de…", so the row shouted that something was wrong while
          hiding which run it was. The pane's note below defines the mark, and the tooltip carries
          the full sentence. Measured 2026-08-06.
        */}
        {degraded && (
          <span
            className="run-warning mono"
            title="Publishing to durable storage failed for this run, so its artifacts exist only on the cloud instance and die with it. Save it before teardown."
          >
            ▲
          </span>
        )}
        {!run.persisted && (
          <button
            className="pane-btn"
            disabled={busy}
            title={saveHint}
            onClick={onSave}
          >
            Save {formatBytes(estimate)}
          </button>
        )}
        {!run.builtin && (
          <button className="pane-btn danger" disabled={busy} onClick={onDelete} title="Delete this run's bytes from disk">
            Delete
          </button>
        )}
      </span>
    </div>
  );
}

/**
 * Bring in a reconstruction this app did not compute.
 *
 * Until this existed the registry had two doors, and both assumed the run was ours: the app paid
 * for it on a GPU, or it was one of the three door fixtures compiled in. A `.glb` and a `.npz`
 * produced anywhere else — by the GreenV worker, on another machine, in a session whose service
 * is long gone — could not be opened at all, although every stage downstream reads exactly those
 * two files.
 *
 * Both files at once, not one control each. They are halves of one reconstruction and importing
 * a mismatched pair is the failure worth designing against, so the selection is made in a single
 * gesture and reported as a whole before anything is written.
 */
function ImportZone({ busy, onImport }: { busy: boolean; onImport: (files: File[]) => void }) {
  const [dropping, setDropping] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  return (
    <div
      className={`run-import${dropping ? " dropping" : ""}`}
      onDragOver={(event) => {
        event.preventDefault();
        setDropping(true);
      }}
      onDragLeave={() => setDropping(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDropping(false);
        onImport([...event.dataTransfer.files]);
      }}
    >
      <input
        ref={fileInput}
        type="file"
        multiple
        accept=".glb,.npz,.jpg,.jpeg"
        style={{ display: "none" }}
        onChange={(event) => {
          onImport([...(event.target.files ?? [])]);
          // Clearing lets the same selection be picked again after a failure.
          event.target.value = "";
        }}
      />
      <span className="run-import-label">
        Import a run
        <HelpDot label="Importing a .glb and a .npz">
          <p>
            A reconstruction is two files: <b>scene.glb</b> carries the point cloud the model
            exported and the alignment that puts it the right way up, and <b>result.npz</b>{" "}
            carries the depth map, the camera intrinsics and the camera pose for every frame.
            Measurement reads the npz; the viewport can show either.
          </p>
          <p>
            Drop both here, with the frames they were computed from if you have them. The frames
            are optional: without them the cloud, the floor fit and the measurement all still
            work, but points lose the photograph&apos;s colour and a mask cannot be painted on a
            real image.
          </p>
          <p>
            Frame count, depth resolution and the file digests are read out of the files
            themselves. <b>GPU time and VRAM are recorded as zero</b>, because no GPU ran here —
            they are not missing numbers, they are numbers this machine never measured.
          </p>
        </HelpDot>
      </span>
      <button className="pane-btn" disabled={busy} onClick={() => fileInput.current?.click()}>
        Choose files
      </button>
    </div>
  );
}

export function RunsPane() {
  const runs = useRuns();
  const graph = useGraph();
  const [busy, setBusy] = useState(false);
  /**
   * The pane's one status line, with the tone it should be read in.
   *
   * It used to be a bare string rendered in the amber warning style whatever it said, so
   * "Deleted Door · 504 px" and a failed save arrived looking identically alarming. Importing
   * made that worse rather than revealing it: the common outcome of an import is success, and a
   * success reported in the colour reserved for things needing attention teaches an operator to
   * stop reading the line.
   */
  const [note, setNote] = useState<{ text: string; tone: "info" | "warn" } | null>(null);
  const say = (text: string) => setNote({ text, tone: "info" });
  const warn = (text: string) => setNote({ text, tone: "warn" });

  const fixture = graph.nodes.find((node) => node.id === FIXTURE_RUN_ID);
  const activeId = String(fixture?.params.runId ?? DEFAULT_RUN_ID);
  const liveSelected = String(fixture?.params.source ?? "live") === "live";
  // A live run exists once DA3 holds an output. Its manifest is not a run record until saved,
  // so the graph runtime — not the registry — is what knows whether there is one.
  const hasLiveRun = Boolean(graph.runtime["da3-depth"]?.outputs?.depth);

  const savedBytes = runs.runs.reduce((total, run) => total + (run.sizeBytes || 0), 0);
  const transient = runs.runs.filter((run) => !run.persisted).length;

  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    setNote(null);
    try {
      await fn();
      await refreshRuns();
    } catch (e) {
      warn(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  /**
   * Sort a selection, refuse it with a reason, or import it — and then SELECT it.
   *
   * Selecting is not a convenience. Somebody who has just imported a run has said what they want
   * to look at as plainly as it can be said, and leaving the measurement branch pointed at
   * whatever it was showing before is the mistake the Source control already made once: the
   * viewers kept displaying the door fixture while the operator believed they were looking at
   * their own run.
   */
  const take = (files: File[]) => {
    if (files.length === 0) return;
    const selection = sortImportFiles(files);
    const { ready, detail } = importReadiness(selection);
    if (!ready) {
      warn(detail);
      return;
    }
    void act(async () => {
      say(`Importing ${detail}`);
      const run = await importRun(
        { glb: selection.glb[0], npz: selection.npz[0], frames: selection.frames },
        { label: selection.glb[0].name.replace(/\.glb$/i, "") },
      );
      setNodeParam(FIXTURE_RUN_ID, "runId", run.id);
      setNodeParam(FIXTURE_RUN_ID, "source", "recorded");
      void runAuto();
      say(
        `Imported ${run.label} · ${run.frameCount ?? 0}f${
          run.framesAvailable === false ? " · no frames, so points keep the height ramp" : ""
        }`,
      );
    });
  };

  return (
    <div className="pane">
      <PaneControls
        status={runs.loading ? "Loading" : "Idle"}
        elapsedMs={0}
        paused={false}
        paneId="runs"
        onPause={() => void refreshRuns()}
        extra={
          <span className="pane-note">
            {runs.runs.length} runs · {formatBytes(savedBytes)} on disk
            {transient > 0 ? ` · ${transient} transient` : ""}
            {/*
              The paragraph that used to close this pane. It explains the storage policy, which is
              a thing you read once — while the pane's job is to show what exists and what it
              costs. The ▲ that needs acting on keeps its own visible mark on the row it belongs
              to, and its tooltip still spells the urgency out there.
            */}
            <HelpDot label="How runs are stored">
              <p>
                <b>Runs are transient by default.</b> A finished cloud run is registered here as a
                stub so it stays selectable, but nothing large is written to disk until you press
                Save.
              </p>
              <p>
                A stub's artifacts sit in cloud storage and are deleted after three days — sooner
                if you delete the bucket. A stub marked <b>▲</b> never reached storage and dies
                with its instance instead, so save that one before teardown.
              </p>
            </HelpDot>
          </span>
        }
      />
      <div className="pane-body runs-pane">
        <div className="runs-root mono" title="Runs live outside the repository so a 135 MB artifact can never be staged by accident.">
          {runs.root || "~/verge-runs"}
        </div>
        <ImportZone busy={busy} onImport={take} />
        {runs.error && <div className="evidence-warning">{runs.error}</div>}
        {note && <div className={note.tone === "warn" ? "evidence-warning" : "pane-hint"}>{note.text}</div>}
        {/*
          The way back to the live run, because this pane now owns the choice entirely — the
          Source control in Setup became a readout, so without this a recorded run would be a
          one-way door.
        */}
        <button
          className={`run-live-row${liveSelected ? " active" : ""}`}
          disabled={!hasLiveRun}
          title={
            hasLiveRun
              ? "Point the measurement branch back at this session's DA3 run."
              : "No live run in this session yet. Load a clip in Setup and press Run."
          }
          onClick={() => {
            setNodeParam(FIXTURE_RUN_ID, "source", "live");
            void runAuto();
          }}
        >
          <span className="run-live-glyph" aria-hidden="true">
            {liveSelected ? "●" : "○"}
          </span>
          <span className="run-live-label">This session's run</span>
          <span className="run-live-note mono">
            {hasLiveRun ? (liveSelected ? "showing" : "live DA3") : "not run yet"}
          </span>
        </button>
        {runs.runs.length === 0 && !runs.loading && (
          <div className="pane-hint">No runs yet. Load a clip in Setup and press Run.</div>
        )}
        {runs.runs.map((run) => (
          <RunRow
            key={run.id}
            run={run}
            active={run.id === activeId}
            busy={busy}
            onSelect={() => {
              setNodeParam(FIXTURE_RUN_ID, "runId", run.id);
              setNodeParam(FIXTURE_RUN_ID, "source", "recorded");
              void runAuto();
            }}
            onSave={() =>
              void act(async () => {
                const { output } = await saveRun(run.id);
                say(output.slice(-400));
              })
            }
            onDelete={() =>
              void act(async () => {
                if (
                  !window.confirm(
                    `Delete ${run.label}?\n\nIts artifacts go from disk permanently — rerunning the clip is the only way back. Recorded trials are archived to ~/verge-runs/.archive first, and stop appearing in this session.`,
                  )
                ) {
                  return;
                }
                const { archived } = await deleteRun(run.id);
                // The rows outlived the run: no run left to select them under, nothing to remove
                // them, and the export still counting them. Their packets are in the archive.
                const dropped = removeObservationsForRun(run.id);
                say(
                  archived || dropped.length
                    ? `Deleted ${run.label}; archived ${archived} recorded trial${archived === 1 ? "" : "s"}`
                    : `Deleted ${run.label}`,
                );
              })
            }
          />
        ))}
      </div>
    </div>
  );
}
