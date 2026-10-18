/**
 * Phone — the captures the demo app sent, and the switch that makes the next one flow on its own.
 *
 * Loading a capture is the same act as dropping its file on the Clip section: it points Frame
 * Source at the video and nothing more. Auto adds the next steps for captures that arrive while
 * this pane is open — load, then extract (free), then, only if that box is ticked as well, Run,
 * which spends GPU time on every capture. Captures that were already in the inbox are never
 * processed automatically.
 *
 * Polling here reads a local directory through the loopback API, so it costs nothing and wakes
 * nothing. Once a second, because the poll is the whole delay between a capture arriving and
 * Auto loading it.
 */

import { useState } from "react";
import {
  discardPhoneCapture,
  setPhoneTruth,
  useActivePhoneCapture,
  type PhoneCapture,
} from "../lib/phone-link";
import { forgetCapture, processCapture, setAuto, usePhoneAuto, type AutoFlags } from "../lib/phone-auto";

function time(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function mb(bytes: number): string {
  return `${(bytes / 1e6).toFixed(1)} MB`;
}

/** The format the phone actually wrote, which is not always the one it was asked for. */
function formatLine(c: PhoneCapture): string {
  const p = c.video?.probe;
  if (!p?.storedWidth) return "not probed";
  const portrait = Math.abs(p.rotation) % 180 === 90;
  const w = portrait ? p.storedHeight : p.storedWidth;
  const h = portrait ? p.storedWidth : p.storedHeight;
  const rate = p.videoBitRate ? ` · ${(p.videoBitRate / 1e6).toFixed(1)} Mbit/s` : "";
  return `${w}×${h} · ${p.avgFps ?? "?"} fps · ${p.codec ?? "?"}${p.hdr ? " HDR" : ""}${rate}`;
}

function Timeline({ capture }: { capture: PhoneCapture }) {
  const events = capture.events;
  return (
    <div className="phone-timeline">
      {events.map((e, i) => {
        const delta = i === 0 ? null : (e.tMs - events[i - 1].tMs) / 1000;
        const d = e.detail ?? {};
        const extra = [
          typeof d.bytes === "number" ? mb(d.bytes) : null,
          typeof d.megabytesPerSecond === "number" ? `${d.megabytesPerSecond} MB/s` : null,
          typeof d.frames === "number" ? `${d.frames} frames` : null,
          typeof d.gpu === "string" ? d.gpu : null,
          typeof d.gpuSeconds === "number" ? `GPU ${d.gpuSeconds.toFixed(1)} s` : null,
          d.mock === true ? "mock" : null,
          typeof d.error === "string" ? d.error : null,
        ]
          .filter(Boolean)
          .join(" · ");
        return (
          <div key={`${e.phase}-${i}`} className={`phone-event${e.phase === "failed" ? " failed" : ""}`}>
            <span className="phase">{e.phase}</span>
            <span className="extra" title={extra}>
              {extra}
            </span>
            <span className="t num">{delta === null ? "0.0" : `+${delta.toFixed(1)}`} s</span>
          </div>
        );
      })}
      <div className="phone-event total">
        <span className="phase">total</span>
        <span className="extra" />
        <span className="t num">{((events.at(-1)?.tMs ?? 0) / 1000).toFixed(1)} s</span>
      </div>
    </div>
  );
}

function TruthInput({ capture }: { capture: PhoneCapture }) {
  const [draft, setDraft] = useState(capture.truth ? String(Math.round(capture.truth.truthM * 1000) / 10) : "");
  const [saved, setSaved] = useState(false);
  const save = async () => {
    const cm = Number(draft.replace(",", "."));
    await setPhoneTruth(capture.id, draft.trim() === "" ? null : cm / 100);
    setSaved(true);
  };
  return (
    <div className="inspector-row control phone-truth">
      <span className="k">Tape (cm)</span>
      <input
        type="text"
        inputMode="decimal"
        value={draft}
        placeholder="—"
        onChange={(e) => {
          setDraft(e.target.value);
          setSaved(false);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") void save();
        }}
      />
      <button onClick={() => void save()} disabled={saved}>
        {saved ? "saved" : "save"}
      </button>
    </div>
  );
}

export function PhoneSection() {
  const { info, captures, auto, busyId, planNote, error } = usePhoneAuto();
  const [openId, setOpenId] = useState<string | null>(null);
  const active = useActivePhoneCapture();
  const handle = (id: string, steps: AutoFlags) => {
    setOpenId(id);
    void processCapture(id, steps);
  };

  return (
    <div className="inspector-section">
      <h3>Phone</h3>
      {info && !info.enabled && (
        <div className="inspector-note">
          Intake is off. Start the studio with VERGE_PHONE_INTAKE=1 (launch configuration “verge-phone”).
        </div>
      )}
      {info?.enabled && (
        <div className="inspector-row" title={`inbox ${info.inbox}`}>
          <span className="k">Gateway</span>
          <span className="v">
            {info.urls[0] ?? `port ${info.port}`} · code {info.code}
          </span>
        </div>
      )}
      <div className="phone-auto">
        <label>
          <input type="checkbox" checked={auto.load} onChange={(e) => setAuto({ ...auto, load: e.target.checked, extract: e.target.checked && auto.extract, run: e.target.checked && auto.run })} />
          auto-load new
        </label>
        <label className={auto.load ? "" : "disabled"}>
          <input type="checkbox" disabled={!auto.load} checked={auto.extract} onChange={(e) => setAuto({ ...auto, extract: e.target.checked, run: e.target.checked && auto.run })} />
          extract
        </label>
        <label className={auto.extract ? "spend" : "disabled"} title="Runs DA3 on every new capture. Spends GPU time; never remembered across reloads.">
          <input type="checkbox" disabled={!auto.extract} checked={auto.run} onChange={(e) => setAuto({ ...auto, run: e.target.checked })} />
          run (GPU)
        </label>
      </div>
      {captures.length === 0 && <div className="phone-empty">No captures yet. Record one on the phone.</div>}
      <div className="phone-list">
        {captures.map((c) => {
          const isActive = c.id === active;
          const isOpen = c.id === openId;
          return (
            <div key={c.id} className={`phone-capture${isActive ? " active" : ""}`}>
              <button className="phone-head" onClick={() => setOpenId(isOpen ? null : c.id)}>
                <span className="when num">{time(c.capturedAt)}</span>
                <span className="what">
                  {c.durationMs ? `${(c.durationMs / 1000).toFixed(1)} s` : "—"} · {c.video ? mb(c.video.bytes) : "uploading"}
                  {typeof c.orbit?.degrees === "number" ? ` · ${Math.round(c.orbit.degrees)}°` : ""}
                  {c.truth ? ` · tape ${(c.truth.truthM * 100).toFixed(1)} cm` : ""}
                </span>
                <span className={`state ${c.state}`}>{isActive ? "loaded" : c.state}</span>
              </button>
              {isOpen && (
                <div className="phone-body">
                  <div className="inspector-row">
                    <span className="k">Recorded</span>
                    <span className="v">{c.video ? formatLine(c) : "—"}</span>
                  </div>
                  {c.sampling && (
                    <div className="inspector-row">
                      <span className="k">Sampling</span>
                      <span className="v">
                        {c.sampling.fps ?? "studio"} fps · ≤ {c.sampling.maxFrames ?? "studio"} frames
                      </span>
                    </div>
                  )}
                  <div className="inspector-row">
                    <span className="k">Asked</span>
                    <span className="v">
                      {String(c.settings?.resolution ?? "?")} @ {String(c.settings?.fps ?? "?")} · lock {String(c.applied?.focusAndExposureLocked ?? "?")} · stab {String(c.applied?.stabilization ?? "?")}
                      {c.applied?.torch ? " · torch" : ""} · {String(c.applied?.exposureOffset ?? 0)} EV
                    </span>
                  </div>
                  {c.video && (
                    <div className="inspector-row">
                      <span className="k">Wi-Fi</span>
                      <span className="v">
                        {(c.video.transferMs / 1000).toFixed(1)} s · {c.video.megabytesPerSecond ?? "?"} MB/s
                      </span>
                    </div>
                  )}
                  <TruthInput capture={c} />
                  <Timeline capture={c} />
                  <div className="inspector-actions">
                    <button className="primary" disabled={!c.video || busyId !== null} onClick={() => handle(c.id, { load: true, extract: false, run: false })}>
                      {busyId === c.id ? "…" : isActive ? "Reload" : "Load"}
                    </button>
                    <button disabled={!c.video || busyId !== null} onClick={() => handle(c.id, { load: true, extract: true, run: false })}>
                      Load + extract
                    </button>
                    <button
                      className="danger"
                      disabled={busyId !== null}
                      onClick={() => {
                        if (!window.confirm(`Delete capture ${c.id}? Its video is not kept anywhere else.`)) return;
                        void discardPhoneCapture(c.id).then(() => forgetCapture(c.id));
                      }}
                    >
                      Delete
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
      {planNote && <div className="phone-empty">Sampling from the phone: {planNote}</div>}
      {error && <div className="inspector-note error">{error}</div>}
    </div>
  );
}
