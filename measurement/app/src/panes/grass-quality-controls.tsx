import { resampleMaskNearest } from "../../../geometry";
import { useEffect, useSyncExternalStore } from 'react';
import { getGraph, resolveInput, useGraph } from '../graph/graph-store';
import { POINT_CLOUD_ID } from '../graph/nodes';
import type { DepthFieldValue } from '../measurement/depth-field';
import { activeClipKey, addTarget, setActiveMeasurementObject, setMaskData } from '../measurement/measurement-store';
import { clearGrassRun, getGrassRun, showGrassQuality } from '../measurement/grass-grid-store';
import { decodeQualityMask, type GrassQualityBundle } from '../measurement/grass-quality';
import { localApiHeaders } from '../lib/local-api';
import { HelpDot } from './help';

type Job = { id: string; status: string; progress: { phase: string; done: number; total: number; elapsedMs: number } | null; error: string | null };
// A pane can be hidden while a CPU job runs. Keep its temporary job in this browser session,
// independent of component mounts, so returning to Advanced restores progress and report links.
let qualityState: { job: Job | null; error: string; starting: boolean } = { job: null, error: '', starting: false };
let jobGeneration = 0;
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };
const snapshot = () => qualityState;
const publish = (patch: Partial<typeof qualityState>) => { qualityState = { ...qualityState, ...patch }; for (const listener of listeners) listener(); };
const setJob = (value: Job | null | ((previous: Job | null) => Job | null)) => publish({ job: typeof value === 'function' ? value(qualityState.job) : value });
const setError = (error: string) => publish({ error });

export function GrassQualityControls({ offsetM }: { offsetM: number }) {
  const graph = useGraph();
  const field = resolveInput(graph, POINT_CLOUD_ID, 'depth')?.value as DepthFieldValue | undefined;
  const { job, error, starting } = useSyncExternalStore(subscribe, snapshot, snapshot);
  useEffect(() => {
    const displayed = getGrassRun().assessment;
    if (displayed && displayed.runId !== field?.manifest.runId) clearGrassRun();
  }, [field?.manifest.runId]);
  const busy = starting || job?.status === 'running';
  async function start() {
    if (!field || qualityState.starting || qualityState.job?.status === 'running') return;
    publish({ starting: true });
    const mine = ++jobGeneration, runId = field.manifest.runId, clip = activeClipKey();
    setError('');
    try {
      const response = await fetch('/api/grass-quality', { method: 'POST', headers: localApiHeaders({ 'content-type': 'application/json' }), body: JSON.stringify({ runId, offsetM }) });
      if (!response.ok) throw new Error(await response.text());
      let next = await response.json() as Job;
      while (mine === jobGeneration) {
        setJob(next);
        if (next.status !== 'running') break;
        await new Promise((r) => setTimeout(r, 750));
        const status = await fetch(`/api/grass-quality/${next.id}`);
        if (!status.ok) throw new Error('The report expired or was discarded.');
        next = await status.json() as Job;
      }
      if (mine !== jobGeneration) return;
      if (next.status !== 'done') throw new Error(next.error ?? 'Local assessment failed.');
      const responseBundle = await fetch(`/api/grass-quality/${next.id}/assessment.json`);
      if (!responseBundle.ok) throw new Error('Could not read the evidence.');
      const bundle = await responseBundle.json() as GrassQualityBundle;
      // Switching runs during a job must not put old masks over the newly selected video.
      const current = resolveInput(getGraph(), POINT_CLOUD_ID, 'depth')?.value as DepthFieldValue | undefined;
      if (current?.manifest.runId !== runId || activeClipKey() !== clip) return;
      const objectId = `automatic-grass-${runId}-${bundle.contentSha256.slice(0, 8)}`;
      addTarget({ id: objectId, temporary: true, code: 'AUTO', name: 'Automatic grass', definition: 'Experimental semantic mask; unvalidated', truthM: null,
        mode: 'top_above_floor', suggestedFrame: bundle.frames[0].canonicalFrame, maskInstruction: 'Inspect included and excluded pixels.' });
      setActiveMeasurementObject(objectId);
      for (const frame of bundle.frames) if (frame.mask) setMaskData(objectId, frame.canonicalFrame, frame.sourceWidth, frame.sourceHeight,
        resampleMaskNearest(decodeQualityMask(frame.mask.runs, frame.mask.width * frame.mask.height), frame.mask.width, frame.mask.height, frame.sourceWidth, frame.sourceHeight), { source: 'model', semantic: frame.semantic, temporary: true, nativeSemanticMask: { width: frame.mask.width, height: frame.mask.height, data: decodeQualityMask(frame.mask.runs, frame.mask.width * frame.mask.height) } });
      showGrassQuality(bundle);
    } catch (e) { if (mine === jobGeneration) { setError(e instanceof Error ? e.message : String(e)); setJob((j) => j ? { ...j, status: 'failed' } : null); } }
    finally { if (mine === jobGeneration) publish({ starting: false }); }
  }
  async function discard() { jobGeneration++; publish({ starting: false }); if (job) await fetch(`/api/grass-quality/${job.id}`, { method: 'DELETE', headers: localApiHeaders() }); setJob(null); }
  const phase = { reading: 'Reading data', segmenting: 'Segmenting', measuring: 'Measuring', packaging: 'Packaging evidence' }[job?.progress?.phase ?? ''] ?? 'Starting';
  return <>
    <div className="output-row"><span className="output-label">EVIDENCE</span>
      <button className="chip-toggle" disabled={!field || busy} onClick={() => void start()}>Measure automatically</button>
      <HelpDot label="Local automatic measurement">Processes every frame of a saved reconstruction on the local CPU. Produces masks, heights and a report with images. Ground and band are estimates; physical height is unvalidated. The report expires after one hour unless saved.</HelpDot>
      {job && <button className="chip-toggle" onClick={() => void discard()}>{busy ? 'Cancel' : 'Discard report'}</button>}
      {job?.status === 'done' && <><a className="chip-toggle" href={`/api/grass-quality/${job.id}/report.html`} target="_blank" rel="noreferrer">Inspect report</a><a className="chip-toggle" href={`/api/grass-quality/${job.id}/assessment.json`} download>Save JSON</a></>}
    </div>
    {busy && <div className="output-row"><span className="mono">{phase} · {job?.progress?.done ?? 0}/{job?.progress?.total ?? 0} frames · {((job?.progress?.elapsedMs ?? 0) / 1000).toFixed(1)} s</span></div>}
    {error && <div className="output-row"><span className="grass-warn">{error}</span></div>}
  </>;
}
