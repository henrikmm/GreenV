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
 *
 * On the demo site (`lib/hosted.ts`) the server writes every phase, `loaded` never appears, and
 * `fetched` only means the artifacts are published. The last stage therefore ends on the page's
 * own `shown` — the cloud actually on screen — and the frames are the server's signed links.
 */

import { useEffect, useState } from "react";
import { useGraph } from "../graph/graph-store";
import type { FramesValue } from "../graph/nodes/frame-source";
import { DEMO_API_BASE, HOSTED } from "../lib/hosted";
import { SHOWN_PHASE, displayEvents, selectHostedCapture, startedAt, useHostedDemo } from "../lib/hosted-demo";
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
    detail: (_e, c) => (c.video ? `${(c.video.bytes / 1e6).toFixed(1)} MB ${HOSTED ? "pela internet" : "pelo Wi-Fi"}` : null),
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
    ends: HOSTED ? SHOWN_PHASE : "fetched",
  },
];

/** Where the demo site reads from, as a visitor would recognise it. */
function apiHost(): string {
  try {
    return new URL(DEMO_API_BASE).host;
  } catch {
    return "API não configurada";
  }
}

type StageState = "pending" | "active" | "done" | "failed";

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(1).replace(".", ",")} s`;
}

function gpuName(gpu: unknown): string | null {
  return typeof gpu === "string" ? gpu.replace(/^NVIDIA /, "").replace(/ Blackwell Server Edition$/, "").replace(/ 80GB HBM3$/, "") : null;
}

const WHEN = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });

/**
 * Every capture the server still holds, newest first: the hosted site's history. A row is the
 * server's own record — when it started, how it ended, its total and its card — and clicking one
 * puts that capture's frames and cloud on screen until "ao vivo" is chosen again. Server times
 * only: the page's `shown` exists for the capture it built, not for the rest of the list.
 */
function DemoHistory({ captures, activeId, pinnedId }: { captures: PhoneCapture[]; activeId: string | null; pinnedId: string | null }) {
  if (captures.length === 0) return null;
  const rows = [...captures].sort((a, b) => startedAt(b) - startedAt(a));
  return (
    <section className="demo-history" aria-label="Histórico de capturas">
      <div className="demo-history-head">
        <span>HISTÓRICO · {rows.length}</span>
        {pinnedId && (
          <button type="button" className="demo-history-live" onClick={() => selectHostedCapture(null)}>
            voltar ao vivo
          </button>
        )}
      </div>
      <ol className="demo-history-list">
        {rows.map((c) => {
          const last = c.events.at(-1);
          const fetched = c.events.find((e) => e.phase === "fetched" || e.phase === "measured");
          const state = last?.phase === "failed" ? "failed" : fetched ? "done" : "active";
          const start = Date.parse(c.events[0]?.at ?? c.capturedAt ?? "");
          const gpu = gpuName(c.events.find((e) => e.phase === "inferred")?.detail?.gpu);
          return (
            <li key={c.id}>
              <button
                type="button"
                className={`demo-history-row ${state}${c.id === activeId ? " current" : ""}`}
                aria-current={c.id === activeId ? "true" : undefined}
                title={c.id}
                onClick={() => selectHostedCapture(c.id)}
              >
                <span className="mark" aria-hidden="true" />
                <span className="when num">{Number.isFinite(start) ? WHEN.format(start) : c.id}</span>
                <span className="what">{state === "failed" ? "falhou" : state === "active" ? "processando" : gpu ?? "pronto"}</span>
                <span className="t num">{fetched ? seconds(fetched.tMs) : ""}</span>
              </button>
            </li>
          );
        })}
      </ol>
    </section>
  );
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
  const hosted = useHostedDemo();
  const capture = captures.find((c) => c.id === busyId) ?? captures.find((c) => c.id === active) ?? captures[0];

  // Hosted: the server's timeline plus the page's own last step. Locally the timeline is whole.
  const timeline = capture ? (HOSTED && capture.id === hosted.activeId ? displayEvents(capture, hosted.shown) : capture.events) : [];
  const events = new Map(timeline.map((e) => [e.phase, e] as const));
  const createdAt = timeline[0] ? Date.parse(timeline[0].at) : null;
  const last = timeline.at(-1);
  const failed = last?.phase === "failed";
  const finished = HOSTED
    ? last?.phase === SHOWN_PHASE || failed
    : last?.phase === "fetched" || last?.phase === "measured" || failed;
  // Hosted: the server is always working on a capture it has not finished.
  const running = Boolean(capture && !finished && (HOSTED || busyId === capture.id || capture.state !== "received"));

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
  // Local frames are paths read back through the dev server; hosted ones are already URLs.
  const thumbs = HOSTED
    ? capture && capture.id === hosted.activeId
      ? pick(hosted.frames, 24)
      : []
    : extracted && frames
      ? pick(frames.paths, 24).map(frameUrl)
      : [];

  const totalMs = last && createdAt !== null ? (finished ? last.tMs : Date.now() - createdAt) : null;

  return (
    <div className="demo-panel">
      <div className="demo-head">
        <span className={`demo-live${running ? " on" : ""}`}>
          {running ? "AO VIVO" : HOSTED && hosted.pinnedId && finished ? "HISTÓRICO" : finished ? "PRONTO" : "AGUARDANDO"}
        </span>
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
      {/*
        Above the frames, not below: switching captures empties the frame grid until the next
        one's links arrive, and a list under it would jump on every click.
      */}
      {HOSTED && <DemoHistory captures={hosted.captures} activeId={hosted.activeId} pinnedId={hosted.pinnedId} />}
      {thumbs.length > 0 && (
        <div className="demo-frames" key={capture?.id}>
          {/*
            Hosted frames come from the bucket's origin, and the same objects are later read with
            CORS (Depth 2D, the cloud's colours). Loading them here with CORS too keeps one kind of
            cached answer per link, so the later reads never meet one cached without the header.
          */}
          {thumbs.map((url, i) => (
            <img
              key={url}
              src={url}
              alt=""
              crossOrigin={HOSTED ? "anonymous" : undefined}
              style={{ animationDelay: `${i * 45}ms` }}
            />
          ))}
        </div>
      )}
      {failed && typeof last?.detail?.error === "string" && <p className="demo-error">{last.detail.error}</p>}
      {HOSTED ? (
        <div className="demo-foot">
          <span>{hosted.error ? "sem resposta da API" : `ao vivo de ${apiHost()}`}</span>
          <span>processado na nuvem</span>
        </div>
      ) : (
        <div className="demo-foot">
          <span>{info?.enabled ? "celular conectado ao Mac" : "intake do celular desligado"}</span>
          <span>
            automático: {auto.load ? "carregar" : "—"}
            {auto.extract ? " · quadros" : ""}
            {auto.run ? " · GPU" : ""}
          </span>
        </div>
      )}
    </div>
  );
}
