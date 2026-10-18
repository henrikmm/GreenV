#!/usr/bin/env node
// Does a new service image still measure what the old one measured? One paid run answers it.
//
// It sends a saved run's own frames, with that run's own inference parameters, to the deployed
// service; downloads what comes back into a scratch runs root; copies the saved run's frozen
// trials across; and replays every trial on both reconstructions with `inspect measurement`.
// The masks are the same pixels on the same frames, so any difference in the readings is the
// image's doing — the PyTorch, CUDA and attention change of 2026-10-17, or a different card.
//
//   node scripts/regress-run.mjs 20260811-161356-d387ec                 service from gcloud
//   node scripts/regress-run.mjs 20260811-161356-d387ec --label l4
//   node scripts/regress-run.mjs <run> --reuse <scratch-dir>             replay only, no GPU
//   node scripts/regress-run.mjs <run> --no-gpu                          rehearsal: every delta is 0
//
// PAID: one /infer request, which wakes the service if it is cold. Everything after the request
// is local. Nothing is written into ~/verge-runs: the candidate and the copied trials live in a
// scratch directory the script prints, so the study's own evidence is never counted twice.
//
// The floor is refitted on the candidate rather than reused from the trial packet, because the
// stored plane belongs to the old reconstruction. The stored confidence threshold is reused as
// is; it is an absolute value on the old run's scale, which is why the report shows how many
// points each mask kept on both.

import { execFile } from "node:child_process";
import { createWriteStream, existsSync } from "node:fs";
import { mkdir, mkdtemp, readdir, readFile, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { RUNS_ROOT } from "./inspect/source.mjs";

const run = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..");
const inspect = join(here, "inspect.mjs");

/** A reading that moves more than this, against the same mask on the same frames, is a regression. */
const TOLERANCE_FRACTION = 0.01;

function parse(argv) {
  const positional = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--")) flags[argv[i].slice(2)] = argv[i + 1]?.startsWith("--") || argv[i + 1] === undefined ? true : argv[++i];
    else positional.push(argv[i]);
  }
  return { positional, flags };
}

async function gcloud(args) {
  const { stdout } = await run("gcloud", args, { maxBuffer: 16 * 1024 * 1024 });
  return stdout.trim();
}

async function serviceUrl() {
  const project = process.env.PROJECT_ID;
  const region = process.env.REGION ?? "us-central1";
  const service = process.env.SERVICE ?? "verge-da3";
  if (!project) throw new Error("PROJECT_ID is unset; source scripts/cloud-common.sh or copy .env.local");
  const [url, gpu] = (
    await gcloud([
      "run", "services", "describe", service, `--region=${region}`, `--project=${project}`,
      "--format=value(status.url,spec.template.spec.nodeSelector['run.googleapis.com/accelerator'])",
    ])
  ).split(/\s+/);
  return { url, gpu };
}

async function infer(baseline, url, token) {
  const manifest = JSON.parse(await readFile(join(baseline, "manifest.json"), "utf8"));
  const frameDir = join(baseline, "frames");
  const names = (await readdir(frameDir)).filter((n) => n.endsWith(".jpg")).sort();
  const form = new FormData();
  let bytes = 0;
  for (const name of names) {
    const data = await readFile(join(frameDir, name));
    bytes += data.length;
    form.append("frames", new Blob([data], { type: "image/jpeg" }), name);
  }
  form.append("params", JSON.stringify(manifest.params));
  const started = performance.now();
  const response = await fetch(`${url}/infer`, { method: "POST", headers: { authorization: `Bearer ${token}` }, body: form });
  const body = await response.text();
  const wallMs = performance.now() - started;
  if (!response.ok) throw new Error(`/infer answered ${response.status}: ${body.slice(0, 400)}`);
  return { manifest: JSON.parse(body), frames: names.length, uploadBytes: bytes, wallMs };
}

async function download(artifacts, dir) {
  const timings = [];
  for (const artifact of artifacts) {
    const started = performance.now();
    const response = await fetch(artifact.url);
    if (!response.ok) throw new Error(`artifact ${artifact.name}: HTTP ${response.status}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(join(dir, artifact.name)));
    timings.push({ name: artifact.name, bytes: artifact.size_bytes ?? null, ms: Math.round(performance.now() - started) });
  }
  return timings;
}

async function replay(root, runId, trialId) {
  const { stdout } = await run("node", [inspect, "measurement", runId, trialId, "--json"], {
    cwd: repo,
    env: { ...process.env, VERGE_RUNS_ROOT: root, VERGE_INSPECT_OUT: join(root, ".inspect") },
    maxBuffer: 64 * 1024 * 1024,
  });
  return JSON.parse(stdout);
}

/** The reading, and the number of mask points it rests on. */
function reading(result) {
  return {
    value: typeof result.replayM === "number" ? result.replayM : null,
    points: typeof result.roundTripHits === "number" ? result.roundTripHits : null,
  };
}

/**
 * The saved run's trials, re-addressed to another run id. The stored floor goes: a trial replays
 * against the plane recorded with it, and a candidate must get its own, as the app would give it.
 * The baseline is replayed the same way (`-refit`), so both sides of the comparison fit a floor.
 */
async function copyTrials(baseline, baselineId, targetDir, targetId) {
  await mkdir(join(targetDir, "measurements"), { recursive: true });
  const trialDir = join(baseline, "measurements");
  if (!existsSync(trialDir)) return;
  for (const name of (await readdir(trialDir)).filter((n) => n.endsWith(".json"))) {
    const text = (await readFile(join(trialDir, name), "utf8")).replaceAll(baselineId, targetId);
    const packet = JSON.parse(text);
    if (packet.live) delete packet.live.ground;
    await writeFile(join(targetDir, "measurements", name), JSON.stringify(packet));
  }
}

async function main() {
  const { positional, flags } = parse(process.argv.slice(2));
  const baselineId = positional[0];
  if (!baselineId) throw new Error("usage: node scripts/regress-run.mjs <saved-run-id> [--label name] [--reuse dir]");
  const baseline = join(RUNS_ROOT, baselineId);
  if (!existsSync(join(baseline, "frames"))) throw new Error(`${baseline} has no frames/ to resend`);
  const label = String(flags.label ?? "candidate");
  const candidateId = `${baselineId}-${label}`;

  const root = flags.reuse ? resolve(String(flags.reuse)) : await mkdtemp(join(tmpdir(), "verge-regress-"));
  const candidate = join(root, candidateId);
  const report = { baseline: baselineId, candidate: candidateId, scratch: root };

  const refitId = `${baselineId}-refit`;
  if (!flags.reuse) {
    await symlink(baseline, join(root, baselineId));
    const refit = join(root, refitId);
    await mkdir(refit, { recursive: true });
    for (const name of ["frames", "manifest.json", "scene.glb", "verge-result.npz"]) {
      if (existsSync(join(baseline, name))) await symlink(join(baseline, name), join(refit, name));
    }
    await copyTrials(baseline, baselineId, refit, refitId);
    await mkdir(candidate, { recursive: true });
    await symlink(join(baseline, "frames"), join(candidate, "frames"));
    if (flags["no-gpu"]) {
      // A rehearsal: the "candidate" is the baseline's own reconstruction, so every delta must be 0.
      for (const name of ["manifest.json", "scene.glb", "verge-result.npz"]) await symlink(join(baseline, name), join(candidate, name));
      await copyTrials(baseline, baselineId, candidate, candidateId);
    }
  }
  if (!flags.reuse && !flags["no-gpu"]) {
    const { url, gpu } = await serviceUrl();
    const token = await gcloud(["auth", "print-identity-token"]);
    console.error(`[regress] sending ${baselineId}'s frames to ${url} (${gpu ?? "unknown card"})`);
    const sent = await infer(baseline, url, token);
    const downloads = await download(sent.manifest.artifacts, candidate);
    await writeFile(join(candidate, "manifest.json"), JSON.stringify(sent.manifest, null, 2));
    await copyTrials(baseline, baselineId, candidate, candidateId);
    report.service = { url, gpu };
    report.timing = {
      frames: sent.frames,
      uploadBytes: sent.uploadBytes,
      requestWallSeconds: Math.round(sent.wallMs) / 1000,
      gpuSeconds: sent.manifest.timing?.gpu_seconds ?? null,
      serverWallSeconds: sent.manifest.timing?.wall_seconds ?? null,
      modelLoadSeconds: sent.manifest.timing?.model_load_seconds ?? null,
      device: sent.manifest.vram?.device_name ?? null,
      peakVramGiB: sent.manifest.vram?.peak_bytes ? Math.round((sent.manifest.vram.peak_bytes / 1024 ** 3) * 100) / 100 : null,
      downloads,
    };
  }

  // A run with no painted trials (a phone capture) still gets its timing and its reconstruction;
  // there is just nothing to replay.
  const trialDir = join(baseline, "measurements");
  const trials = existsSync(trialDir) ? (await readdir(trialDir)).filter((n) => n.endsWith(".json")) : [];
  const rows = [];
  for (const name of trials) {
    const packet = JSON.parse(await readFile(join(baseline, "measurements", name), "utf8"));
    const trialId = packet.observation.id;
    const row = { target: packet.target?.name ?? "?", truthM: packet.target?.truthM ?? null, trial: trialId };
    try {
      row.storedM = reading(await replay(root, baselineId, trialId)).value;
      const before = reading(await replay(root, refitId, trialId.replaceAll(baselineId, refitId)));
      const after = reading(await replay(root, candidateId, trialId.replaceAll(baselineId, candidateId)));
      row.baselineM = before.value;
      row.candidateM = after.value;
      row.baselinePoints = before.points;
      row.candidatePoints = after.points;
      if (before.value !== null && after.value !== null) {
        row.deltaMm = Math.round((after.value - before.value) * 10000) / 10;
        row.deltaFraction = (after.value - before.value) / before.value;
        row.regressed = Math.abs(row.deltaFraction) > TOLERANCE_FRACTION;
      }
      if (row.truthM && after.value !== null) row.candidateErrorFraction = (after.value - row.truthM) / row.truthM;
    } catch (error) {
      row.error = String(error?.message ?? error).split("\n")[0];
    }
    rows.push(row);
  }
  report.rows = rows;
  report.passed = rows.length === 0 ? null : rows.every((r) => r.error || r.regressed === false) && rows.some((r) => r.regressed === false);

  await writeFile(join(root, `report-${label}.json`), JSON.stringify(report, null, 2));
  if (flags.json) return console.log(JSON.stringify(report, null, 2));

  if (report.timing) {
    const t = report.timing;
    console.log(`${t.device ?? report.service?.gpu}: ${t.frames} frames, ${(t.uploadBytes / 1e6).toFixed(1)} MB up, request ${t.requestWallSeconds} s, GPU ${t.gpuSeconds?.toFixed?.(2)} s, model load ${t.modelLoadSeconds ?? "—"} s, peak ${t.peakVramGiB} GiB`);
    for (const d of t.downloads) console.log(`  down ${d.name}: ${d.bytes ? (d.bytes / 1e6).toFixed(1) + " MB" : "?"} in ${(d.ms / 1000).toFixed(1)} s`);
  }
  console.log("target        truth   stored    baseline  candidate   delta    vs tape   (baseline and candidate both refit the floor)");
  for (const r of rows) {
    if (r.error) {
      console.log(`${r.target.padEnd(12)}  ${r.trial}: ${r.error}`);
      continue;
    }
    const fmt = (v) => (v === null || v === undefined ? "   —   " : v.toFixed(4));
    const pct = (f) => (f === undefined ? "—" : `${f >= 0 ? "+" : ""}${(f * 100).toFixed(1)}%`);
    console.log(
      `${r.target.padEnd(12)}  ${fmt(r.truthM)}  ${fmt(r.storedM)}  ${fmt(r.baselineM)}  ${fmt(r.candidateM)}  ${String(r.deltaMm ?? "—").padStart(6)} mm  ${pct(r.candidateErrorFraction)}${r.regressed ? "  REGRESSED" : ""}`,
    );
  }
  console.log(`${report.passed === null ? "TIMING ONLY" : report.passed ? "PASS" : "FAIL"} · scratch ${root}`);
  process.exitCode = report.passed === false ? 1 : 0;
}

main().catch((error) => {
  console.error(error.message ?? error);
  process.exit(2);
});
