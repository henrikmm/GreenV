// The depth stage: sampled JPEGs in, a reconstruction out.
//
// This is the only part of the chain that needs a GPU, and it is the only part that costs money.
// The service bills for the machine's whole lifetime rather than for the seconds it computes, so
// a run's real price includes a cold start of about a minute and roughly fifteen minutes of idle
// afterwards before Cloud Run scales to zero (measurement/docs/REGISTRY.md). Segments arriving
// back to back from one drive ride a single warm instance; a lone segment pays the whole tail.
//
// `baseUrl` carries whatever path prefix the deployment needs — `http://host:5173/api` for the
// Vite mock, the bare service origin for the deployed FastAPI. Artifact URLs are resolved against
// that base's ORIGIN, because both the mock and the real service return them rooted at `/`.

import { basename } from "node:path";

// Cloud Run caps an HTTP/1 response at 32 MiB and answers a larger whole-file GET with 500 and
// zero bytes. A 112-frame npz is around 108 MB, so it can only ever arrive in pieces. The
// service does honour Range; 24 MiB leaves headroom under the cap.
const CHUNK_BYTES = 24 * 1024 * 1024;

export function inferClient({ baseUrl, token, processRes, maxFrames, fps, timeoutMs }, fetchImpl = fetch) {
  const base = new URL(baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`);
  const origin = base.origin;
  const headers = token ? { authorization: `Bearer ${token}` } : {};

  async function request(url, extra = {}) {
    const response = await fetchImpl(url, {
      ...extra,
      headers: { ...headers, ...(extra.headers ?? {}) },
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      throw new Error(`${extra.method ?? "GET"} ${url} failed with ${response.status}: ${detail.slice(0, 400)}`);
    }
    return response;
  }

  return {
    /** Send every sampled frame in recorded order and return the service's manifest. */
    async infer(frames, { sourceDurationSeconds } = {}) {
      if (frames.length < 2) {
        // Not a transport limit. Depth Anything recovers geometry by comparing views, and a
        // single image skips cross-view attention entirely, so one frame is not a small answer
        // — it is a wrong one.
        throw new Error(`depth inference needs at least 2 frames, got ${frames.length}`);
      }
      if (frames.length > maxFrames) {
        throw new Error(`${frames.length} frames exceeds max_frames=${maxFrames}`);
      }

      const form = new FormData();
      for (const frame of frames) {
        form.append("frames", new Blob([frame.bytes], { type: "image/jpeg" }), basename(frame.name));
      }
      form.append("params", JSON.stringify({
        fps,
        process_res: processRes,
        max_frames: maxFrames,
        ...(sourceDurationSeconds ? { source_duration_s: sourceDurationSeconds } : {}),
      }));

      const response = await request(new URL("infer", base), { method: "POST", body: form });
      return response.json();
    },

    /**
     * Fetch one artifact named by a manifest.
     *
     * `gs_uri` is deliberately ignored: resolving it needs the operator's gcloud credentials, and
     * a worker holding those is a larger decision than this stage. That leaves `url`, which is a
     * signed bucket link on a normally published run and a service path on a degraded one — the
     * degraded case being the one that needs ranging.
     */
    async artifact(descriptor) {
      const url = /^https?:/i.test(descriptor.url) ? descriptor.url : new URL(descriptor.url, origin).toString();
      const size = Number(descriptor.size_bytes ?? 0);

      if (!/^https?:/i.test(descriptor.url) && size > CHUNK_BYTES) {
        const chunks = [];
        for (let offset = 0; offset < size; offset += CHUNK_BYTES) {
          const end = Math.min(offset + CHUNK_BYTES, size) - 1;
          const part = await request(url, { headers: { range: `bytes=${offset}-${end}` } });
          chunks.push(Buffer.from(await part.arrayBuffer()));
        }
        return Buffer.concat(chunks);
      }

      const response = await request(url);
      return Buffer.from(await response.arrayBuffer());
    },
  };
}

/** The artifact a manifest lists under one kind, or null when the service did not publish it. */
export function artifactOfKind(manifest, kind) {
  return (manifest.artifacts ?? []).find((artifact) => artifact.kind === kind) ?? null;
}
