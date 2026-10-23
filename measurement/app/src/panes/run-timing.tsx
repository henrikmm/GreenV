/**
 * The timing log: every stretch of the last capture and run, laid out on one time axis.
 *
 * Off by default, because the phase readout above it is what an operator needs while a run is
 * going; this is for the question afterwards — was it the upload, the GPU, publishing or the
 * download. The switch is remembered per browser. Recording happens whether or not it is shown
 * (`lib/run-timeline.ts`), so turning it on after a slow run still shows that run.
 *
 * The bars are positions and lengths on a shared clock, not progress: each one is two observed
 * timestamps, or a duration the service reported itself, marked as such.
 */

import { useEffect, useState } from "react";
import { GROUP_LABEL, summarise, timelineText, useTimeline, type SpanGroup } from "../lib/run-timeline";

const STORAGE_KEY = "verge.run.timing-log";

function readEnabled(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

function seconds(ms: number | null): string {
  return ms === null ? "…" : `${(ms / 1000).toFixed(1)} s`;
}

export function RunTimingLog() {
  const [enabled, setEnabled] = useState(readEnabled);
  const [copied, setCopied] = useState(false);
  const spans = useTimeline();
  const open = spans.some((s) => s.endMs === null);

  // Live lengths for the stretch in progress; local arithmetic only.
  const [, tick] = useState(0);
  useEffect(() => {
    if (!enabled || !open) return;
    const timer = window.setInterval(() => tick((n) => n + 1), 250);
    return () => window.clearInterval(timer);
  }, [enabled, open]);

  const toggle = (next: boolean) => {
    setEnabled(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
    } catch {
      // private mode: the switch just does not persist
    }
  };

  const now = Date.now();
  const { rows, totalMs, byGroup, idleMs, previewOnScreenMs, fullOnScreenMs } = summarise(spans, now);
  const scale = totalMs && totalMs > 0 ? totalMs : 1;

  return (
    <div className="timing-log">
      <div className="inspector-row control">
        <span className="k">Timing log</span>
        <input
          type="checkbox"
          aria-label="Show where the last run's time went"
          checked={enabled}
          onChange={(event) => toggle(event.target.checked)}
        />
        <span className="v num">{enabled ? "on" : "off"}</span>
      </div>

      {enabled && rows.length === 0 && (
        <div className="phase-detail timing-empty">Nothing timed yet. Load a clip or a phone capture.</div>
      )}

      {enabled && rows.length > 0 && (
        <>
          <div className="timing-totals mono">
            <span>total {seconds(totalMs)}</span>
            {(Object.keys(byGroup) as SpanGroup[]).map((group) => (
              <span key={group} className={`timing-group g-${group}`}>
                {GROUP_LABEL[group]} {seconds(byGroup[group]!)}
              </span>
            ))}
            {idleMs >= 500 && <span className="timing-group g-idle">idle {seconds(idleMs)}</span>}
          </div>
          {(previewOnScreenMs !== null || fullOnScreenMs !== null) && (
            <div className="timing-milestones mono">
              {previewOnScreenMs !== null && <span>preview on screen +{seconds(previewOnScreenMs)}</span>}
              {fullOnScreenMs !== null && <span>full cloud +{seconds(fullOnScreenMs)}</span>}
            </div>
          )}
          <ol className="timing-rows">
            {rows.map((row) => {
              const end = row.span.endMs ?? now;
              const left = (row.offsetMs / scale) * 100;
              const width = Math.max(0.4, ((end - row.span.startMs) / scale) * 100);
              const facts = [
                row.span.bytes ? `${(row.span.bytes / 1e6).toFixed(1)} MB` : null,
                row.rate !== null ? `${row.rate.toFixed(1)} MB/s` : null,
                row.span.note ?? null,
                row.span.derived ? "service clock" : null,
              ].filter(Boolean);
              return (
                <li
                  key={row.span.key}
                  className={`timing-row g-${row.span.group}${row.span.parent ? " child" : ""}${
                    row.span.endMs === null ? " open" : ""
                  }`}
                >
                  {row.idleBeforeMs >= 500 && <div className="timing-idle mono">idle {seconds(row.idleBeforeMs)}</div>}
                  <div className="timing-line">
                    <span className="k" title={row.span.label}>
                      {row.span.label}
                    </span>
                    <span className="v num">{seconds(row.durationMs ?? now - row.span.startMs)}</span>
                  </div>
                  <div className="timing-axis">
                    <div className="timing-bar" style={{ left: `${left}%`, width: `${Math.min(width, 100 - left)}%` }} />
                  </div>
                  {facts.length > 0 && <div className="phase-detail mono">{facts.join(" · ")}</div>}
                </li>
              );
            })}
          </ol>
          <div className="inspector-actions">
            <button
              onClick={() => {
                void navigator.clipboard?.writeText(timelineText(spans)).then(
                  () => {
                    setCopied(true);
                    window.setTimeout(() => setCopied(false), 1500);
                  },
                  () => {},
                );
              }}
            >
              {copied ? "Copied" : "Copy as text"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
