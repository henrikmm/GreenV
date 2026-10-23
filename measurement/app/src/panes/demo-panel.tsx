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
import {
  SHOWN_PHASE,
  deleteHostedCapture,
  displayEvents,
  selectHostedCapture,
  startedAt,
  useHostedDemo,
} from "../lib/hosted-demo";
import { frameUrl } from "../lib/infer-client";
import { FRAME_SOURCE_ID } from "../lib/load-clip";
import { usePhoneAuto } from "../lib/phone-auto";
import { useActivePhoneCapture, type PhoneCapture, type PhoneEvent } from "../lib/phone-link";
import { useCloudPreview } from "../lib/cloud-preview";
import { followLive, openCapture, runForCapture, useDemoHistory } from "../lib/demo-history";
import { useRuns } from "../lib/runs-store";

interface Stage {
  key: string;
  label: string;
  /** Any of these phases starts the stage. */
  starts: string[];
  /** Any of these phases ends it. */
  ends: string[];
  detail?: (events: Map<string, PhoneEvent>, capture: PhoneCapture) => string | null;
  /** A measured duration that beats the gap between the two phases, when there is one. */
  measuredMs?: (events: Map<string, PhoneEvent>) => number | null;
  /** Left out of a timeline where the step never happens. */
  absent?: (events: Map<string, PhoneEvent>) => boolean;
}

/** The video was cut on the GPU machine (`GREENV_DEMO_SAMPLE_ON=gpu`), not by the API. */
const onGpu = (events: Map<string, PhoneEvent>) => events.has("sampling") || events.has("sampled");

const STAGES: Stage[] = [
  {
    key: "receive",
    label: "Recebendo o vídeo do celular",
    starts: ["created", "uploading"],
    ends: ["received"],
    detail: (_e, c) => (c.video ? `${(c.video.bytes / 1e6).toFixed(1)} MB ${HOSTED ? "pela internet" : "pelo Wi-Fi"}` : null),
  },
  {
    key: "frames",
    label: "Separando o vídeo em quadros",
    starts: ["loaded", "extracting", "sampling"],
    ends: ["extracted", "sampled"],
    detail: (e) => {
      const n = (e.get("extracted") ?? e.get("sampled"))?.detail?.frames;
      return typeof n === "number" ? `${n} quadros${onGpu(e) ? " · na GPU" : ""}` : null;
    },
    // On the GPU the API sees the sampling only between two of its two-second polls; the
    // handler's own clock is the honest figure (download of the video plus ffmpeg).
    measuredMs: (e) => {
      const d = e.get("sampled")?.detail;
      const fetch = typeof d?.fetchSeconds === "number" ? d.fetchSeconds : 0;
      return typeof d?.sampleSeconds === "number" ? Math.round((fetch + d.sampleSeconds) * 1000) : null;
    },
  },
  {
    key: "send",
    label: "Enviando os quadros para a GPU",
    starts: ["uploading-frames"],
    ends: ["waking"],
    // Sampled on the GPU, the frames never travel from here: only the video does.
    absent: onGpu,
    detail: (e) => {
      const b = e.get("uploading-frames")?.detail?.bytes;
      return typeof b === "number" ? `${(b / 1e6).toFixed(1)} MB` : null;
    },
  },
  {
    key: "reconstruct",
    label: "Reconstruindo em 3D",
    starts: ["waking", "loading-model", "inferring"],
    ends: ["inferred"],
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
    ends: [HOSTED ? SHOWN_PHASE : "fetched"],
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
 * A finished or failed row can be deleted, with the pairing code typed in for that delete.
 */
function DemoHistory({ captures, activeId, pinnedId }: { captures: PhoneCapture[]; activeId: string | null; pinnedId: string | null }) {
  // The capture whose delete is being confirmed, and the code typed for it. The code lives only
  // in this form: the site is public and on a shared screen, so it is asked for every time.
  const [deleting, setDeleting] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (captures.length === 0) return null;

  const close = () => {
    setDeleting(null);
    setCode("");
    setProblem(null);
  };
  const confirm = async (id: string) => {
    setBusy(true);
    setProblem(null);
    try {
      await deleteHostedCapture(id, code);
      close();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
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
              {/* The API refuses a capture still being processed, so the control is not offered. */}
              {state !== "active" && deleting !== c.id && (
                <button
                  type="button"
                  className="demo-history-delete"
                  aria-label={`Apagar a captura de ${Number.isFinite(start) ? WHEN.format(start) : c.id}`}
                  onClick={() => {
                    close();
                    setDeleting(c.id);
                  }}
                >
                  apagar
                </button>
              )}
              {deleting === c.id && (
                <form
                  className="demo-history-confirm"
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (code.trim() && !busy) void confirm(c.id);
                  }}
                >
                  <input
                    type="password"
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    onKeyDown={(e) => e.key === "Escape" && close()}
                    placeholder="código de pareamento"
                    aria-label="Código de pareamento"
                    autoComplete="off"
                    autoFocus
                  />
                  <button type="submit" disabled={!code.trim() || busy}>
                    {busy ? "apagando…" : "apagar"}
                  </button>
                  <button type="button" onClick={close}>
                    cancelar
                  </button>
                  {problem && <span className="demo-history-problem">{problem}</span>}
                </form>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/**
 * The same history on the Mac: the intake's captures, newest first, each opened through the run
 * it produced (`lib/demo-history.ts`). A saved run opens with a click; an unsaved one offers
 * "salvar e abrir", because only a run on disk can be shown and saving is never implied.
 */
function LocalHistory({ captures, shownId, busyId }: { captures: PhoneCapture[]; shownId: string | null; busyId: string | null }) {
  const history = useDemoHistory();
  const { runs } = useRuns();
  if (captures.length === 0) return null;
  const rows = [...captures].sort((a, b) => startedAt(b) - startedAt(a));
  return (
    <section className="demo-history" aria-label="Histórico de capturas">
      <div className="demo-history-head">
        <span>HISTÓRICO · {rows.length}</span>
        {history.pinnedId && (
          <button type="button" className="demo-history-live" onClick={followLive}>
            voltar ao vivo
          </button>
        )}
      </div>
      <ol className="demo-history-list">
        {rows.map((c) => {
          const last = c.events.at(-1);
          const fetched = c.events.find((e) => e.phase === "fetched" || e.phase === "measured");
          // Only the capture Auto is working on is in progress; one that stopped short is history.
          const state = last?.phase === "failed" ? "failed" : fetched ? "done" : c.id === busyId ? "active" : "done";
          const start = startedAt(c);
          const gpu = gpuName(c.events.find((e) => e.phase === "inferred")?.detail?.gpu);
          const run = runForCapture(c, runs);
          const what =
            state === "failed" ? "falhou" : !fetched ? (state === "active" ? "processando" : "sem nuvem") : run ? (gpu ?? "pronto") : "sem nuvem";
          return (
            <li key={c.id}>
              <button
                type="button"
                className={`demo-history-row ${state}${c.id === shownId ? " current" : ""}`}
                aria-current={c.id === shownId ? "true" : undefined}
                title={run ? `${c.id} · ${run.id}${run.persisted ? "" : " · não salvo"}` : c.id}
                onClick={() => void openCapture(c, run)}
              >
                <span className="mark" aria-hidden="true" />
                <span className="when num">{Number.isFinite(start) ? WHEN.format(start) : c.id}</span>
                <span className="what">{what}</span>
                <span className="t num">{fetched ? seconds(fetched.tMs) : ""}</span>
              </button>
              {run && !run.persisted && run.available && c.id === history.pinnedId && (
                <button
                  type="button"
                  className="demo-history-delete"
                  disabled={history.busy}
                  title="Baixa a nuvem e os quadros para ~/verge-runs (~110 MB) e abre"
                  onClick={() => void openCapture(c, run, true)}
                >
                  {history.busy ? "salvando…" : "salvar e abrir"}
                </button>
              )}
            </li>
          );
        })}
      </ol>
      {history.error && <p className="demo-error">{history.error}</p>}
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
  const history = useDemoHistory();
  const pinned = HOSTED ? undefined : captures.find((c) => c.id === history.pinnedId);
  const capture =
    pinned ?? captures.find((c) => c.id === busyId) ?? captures.find((c) => c.id === active) ?? captures[0];

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
  // The preview belongs to the run this studio is sending, which is the active capture's.
  const previewState = useCloudPreview();
  const previewAtMs =
    previewState.status === "shown" && capture?.id === active && createdAt !== null
      ? previewState.preview.shownAt - createdAt
      : null;

  return (
    <div className="demo-panel">
      <div className="demo-head">
        <span className={`demo-live${running ? " on" : ""}`}>
          {running
            ? "AO VIVO"
            : (HOSTED ? hosted.pinnedId : pinned) && finished
              ? "HISTÓRICO"
              : finished
                ? "PRONTO"
                : "AGUARDANDO"}
        </span>
        <span className="demo-total num">{totalMs === null ? "—" : seconds(totalMs)}</span>
      </div>
      {!capture && <p className="demo-idle">Grave um objeto no celular. Ele aparece aqui sozinho.</p>}
      {capture && (
        <ol className="demo-stages">
          {STAGES.filter((stage) => !stage.absent?.(events)).map((stage) => {
            const started = stage.starts.map((p) => events.get(p)).find(Boolean);
            const ended = stage.ends.map((p) => events.get(p)).find(Boolean);
            const state: StageState = ended
              ? "done"
              : started
                ? failed
                  ? "failed"
                  : "active"
                : "pending";
            const ms =
              started && ended
                ? (stage.measuredMs?.(events) ?? ended.tMs - started.tMs)
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
      {HOSTED ? (
        <DemoHistory captures={hosted.captures} activeId={hosted.activeId} pinnedId={hosted.pinnedId} />
      ) : (
        <LocalHistory captures={captures} shownId={capture?.id ?? null} busyId={busyId} />
      )}
      {previewAtMs !== null && previewState.status === "shown" && (
        <p className="demo-preview num">
          Prévia na tela em {seconds(previewAtMs)} · {Math.round(previewState.preview.points / 1000)} mil pontos · a nuvem
          completa chega em seguida
        </p>
      )}
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
      {capture && !failed && !auto.run && events.has("extracted") && !events.has("uploading-frames") && busyId === null && (
        <p className="demo-error">Quadros prontos, mas a GPU automática está desligada: marque “run (GPU)” no Setup.</p>
      )}
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
