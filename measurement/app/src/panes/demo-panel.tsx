/**
 * Demo — what an audience watches while a visitor's capture turns into a point cloud.
 *
 * Five stages, each lit as the studio reports it and timed by the gateway's own timeline, so the
 * seconds on screen are the measured ones (`docs/NEXT-DEMO.md`, phone ↔ gateway contract). The
 * frames appear as they are extracted, because seeing the video become a stack of photographs is
 * the step people do not expect. Nothing here advances on a clock except the live counter of the
 * stage in progress, which counts real elapsed time since that stage began.
 *
 * Text is Brazilian Portuguese: it is what a visitor reads.
 */

import { useEffect, useState } from "react";
import { useGraph } from "../graph/graph-store";
import type { FramesValue } from "../graph/nodes/frame-source";
import { frameUrl } from "../lib/infer-client";
import { FRAME_SOURCE_ID } from "../lib/load-clip";
import { usePhoneAuto } from "../lib/phone-auto";
import { useActivePhoneCapture, type PhoneCapture, type PhoneEvent } from "../lib/phone-link";

interface Stage {
  key: string;
  label: string;
  /** Any of these phases starts the stage. */
  starts: string[];
  /** The phase that ends it. */
  ends: string;
  detail?: (events: Map<string, PhoneEvent>, capture: PhoneCapture) => string | null;
}

const STAGES: Stage[] = [
  {
    key: "receive",
    label: "Recebendo o vídeo do celular",
    starts: ["created", "uploading"],
    ends: "received",
    detail: (_e, c) => (c.video ? `${(c.video.bytes / 1e6).toFixed(1)} MB pelo Wi-Fi` : null),
  },
  {
    key: "frames",
    label: "Separando o vídeo em quadros",
    starts: ["loaded", "extracting"],
    ends: "extracted",
    detail: (e) => {
      const n = e.get("extracted")?.detail?.frames;
      return typeof n === "number" ? `${n} quadros` : null;
    },
  },
  {
    key: "send",
    label: "Enviando os quadros para a GPU",
    starts: ["uploading-frames"],
    ends: "waking",
    detail: (e) => {
      const b = e.get("uploading-frames")?.detail?.bytes;
      return typeof b === "number" ? `${(b / 1e6).toFixed(1)} MB` : null;
    },
  },
  {
    key: "reconstruct",
    label: "Reconstruindo em 3D",
    starts: ["waking", "loading-model", "inferring"],
    ends: "inferred",
    detail: (e) => {
      const d = e.get("inferred")?.detail ?? e.get("inferring")?.detail;
      const gpu = typeof d?.gpu === "string" ? d.gpu.replace(/^NVIDIA /, "").replace(/ Blackwell Server Edition$/, "") : null;
      const seconds = typeof d?.gpuSeconds === "number" ? `${d.gpuSeconds.toFixed(1)} s de GPU` : null;
      return [gpu, seconds].filter(Boolean).join(" · ") || null;
    },
  },
  {
    key: "cloud",
    label: "Montando a nuvem de pontos",
    starts: ["inferred"],
    ends: "fetched",
  },
];

type StageState = "pending" | "active" | "done" | "failed";

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(1).replace(".", ",")} s`;
}

/** Up to `count` frames spread over the whole extraction, in order. */
function pick(paths: string[], count: number): string[] {
  if (paths.length <= count) return paths;
  return Array.from({ length: count }, (_, i) => paths[Math.round((i * (paths.length - 1)) / (count - 1))]!);
}

export function DemoPanel() {
  const { captures, auto, busyId, info } = usePhoneAuto();
  const active = useActivePhoneCapture();
  const graph = useGraph();
  const capture = captures.find((c) => c.id === busyId) ?? captures.find((c) => c.id === active) ?? captures[0];

  const events = new Map((capture?.events ?? []).map((e) => [e.phase, e] as const));
  const createdAt = capture?.events[0] ? Date.parse(capture.events[0].at) : null;
  const last = capture?.events.at(-1);
  const failed = last?.phase === "failed";
  const finished = last?.phase === "fetched" || last?.phase === "measured" || failed;
  const running = Boolean(capture && !finished && (busyId === capture.id || capture.state !== "received"));

  // Re-render ten times a second while something is in flight, for the live counters only.
  const [, tick] = useState(0);
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => tick((n) => n + 1), 100);
    return () => window.clearInterval(timer);
  }, [running]);

  const frames =
    capture && capture.id === active
      ? (graph.runtime[FRAME_SOURCE_ID]?.outputs?.frames?.value as FramesValue | undefined)
      : undefined;
  const extracted = Boolean(frames) && events.has("extracted");
  const thumbs = extracted && frames ? pick(frames.paths, 24) : [];

  const totalMs = last && createdAt !== null ? (finished ? last.tMs : Date.now() - createdAt) : null;

  return (
    <div className="demo-panel">
      <div className="demo-head">
        <span className={`demo-live${running ? " on" : ""}`}>{running ? "AO VIVO" : finished ? "PRONTO" : "AGUARDANDO"}</span>
        <span className="demo-total num">{totalMs === null ? "—" : seconds(totalMs)}</span>
      </div>
      {!capture && <p className="demo-idle">Grave um objeto no celular. Ele aparece aqui sozinho.</p>}
      {capture && (
        <ol className="demo-stages">
          {STAGES.map((stage) => {
            const started = stage.starts.map((p) => events.get(p)).find(Boolean);
            const ended = events.get(stage.ends);
            const state: StageState = ended
              ? "done"
              : started
                ? failed
                  ? "failed"
                  : "active"
                : "pending";
            const ms =
              started && ended
                ? ended.tMs - started.tMs
                : started && state === "active" && createdAt !== null
                  ? Date.now() - createdAt - started.tMs
                  : null;
            const detail = stage.detail?.(events, capture) ?? null;
            return (
              <li key={stage.key} className={`demo-stage ${state}`}>
                <span className="mark" aria-hidden="true" />
                <span className="label">{stage.label}</span>
                <span className="t num">{ms === null ? "" : seconds(ms)}</span>
                {detail && state !== "pending" && <span className="detail">{detail}</span>}
              </li>
            );
          })}
        </ol>
      )}
      {thumbs.length > 0 && (
        <div className="demo-frames" key={capture?.id}>
          {thumbs.map((path, i) => (
            <img key={path} src={frameUrl(path)} alt="" style={{ animationDelay: `${i * 45}ms` }} />
          ))}
        </div>
      )}
      {failed && typeof last?.detail?.error === "string" && <p className="demo-error">{last.detail.error}</p>}
      <div className="demo-foot">
        <span>{info?.enabled ? "celular conectado ao Mac" : "intake do celular desligado"}</span>
        <span>
          automático: {auto.load ? "carregar" : "—"}
          {auto.extract ? " · quadros" : ""}
          {auto.run ? " · GPU" : ""}
        </span>
      </div>
    </div>
  );
}
