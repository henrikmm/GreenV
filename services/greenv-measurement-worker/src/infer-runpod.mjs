// The depth stage against a RunPod serverless endpoint.
//
// Same two methods as `infer.mjs`, a different conversation. The FastAPI service answers one
// request: frames go up as multipart, the reconstruction comes back. RunPod serverless is a job
// queue — `POST /run` returns an id, and the answer is collected from `GET /status/<id>` — and its
// request body is JSON with a payload ceiling well under a hundred JPEGs.
//
// So frames travel by reference. They are already in object storage, which is where the extractor
// put them and where the handler can read them, so the input carries their keys and the bucket to
// find them in. That is the one thing this adapter asks of a handler that does not exist yet:
//
//   input  { frames: [{ name, key }], storage: { bucket, endpoint, region, prefix }, params: {...} }
//   output the same manifest the FastAPI returns — run_id, frames.count, artifacts[{kind,url,...}]
//
// Nothing here wakes a GPU by itself: a RunPod endpoint scales to zero and bills for the machine's
// lifetime the same way Cloud Run does, so the cost note at the top of `infer.mjs` applies
// unchanged, and `AGENTS.md` asks for the user's agreement before every run.

/** Statuses RunPod reports while the job is still alive. */
const PENDING = new Set(["IN_QUEUE", "IN_PROGRESS"]);

export function runpodInferClient(
  { endpoint, apiKey, pollIntervalMs, timeoutMs, processRes, maxFrames, fps, storage },
  fetchImpl = fetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
) {
  const base = endpoint.endsWith("/") ? endpoint.slice(0, -1) : endpoint;
  const headers = { authorization: `Bearer ${apiKey}`, "content-type": "application/json" };

  async function call(url, init = {}) {
    const response = await fetchImpl(url, {
      ...init,
      headers: { ...headers, ...(init.headers ?? {}) },
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      throw new Error(
        `${init.method ?? "GET"} ${url} failed with ${response.status}: ${detail.slice(0, 400)}`,
      );
    }
    return response.json();
  }

  return {
    async infer(frames, { sourceDurationSeconds } = {}) {
      if (frames.length < 2) {
        // The same reason as the HTTP client: one view has no cross-view attention to do, so a
        // single frame is a wrong answer rather than a small one.
        throw new Error(`depth inference needs at least 2 frames, got ${frames.length}`);
      }
      if (frames.length > maxFrames) {
        throw new Error(`${frames.length} frames exceeds max_frames=${maxFrames}`);
      }
      const missing = frames.find((frame) => !frame.key);
      if (missing) {
        // Bytes are useless here and keys are the whole contract, so a frame without one is a
        // wiring mistake worth naming rather than an empty upload worth paying for.
        throw new Error(`frame ${missing.name} has no object key to send to the depth handler`);
      }

      const started = await call(`${base}/run`, {
        method: "POST",
        body: JSON.stringify({
          input: {
            frames: frames.map((frame) => ({ name: frame.name, key: frame.key })),
            storage: {
              bucket: storage.bucket ?? null,
              endpoint: storage.endpoint ?? null,
              region: storage.region ?? null,
            },
            params: {
              fps,
              process_res: processRes,
              max_frames: maxFrames,
              ...(sourceDurationSeconds ? { source_duration_s: sourceDurationSeconds } : {}),
            },
          },
        }),
      });

      const jobId = started.id;
      if (!jobId) throw new Error(`RunPod accepted no job: ${JSON.stringify(started).slice(0, 200)}`);
      return await collect(jobId, started);
    },

    /**
     * Fetch one artifact named by the manifest.
     *
     * A RunPod handler has no origin of its own to serve files from — it returns links to wherever
     * it put them, which is a bucket. So an artifact URL must be absolute, and a relative one means
     * the handler is speaking the FastAPI's dialect and should be reached with `infer.mjs` instead.
     */
    async artifact(descriptor) {
      if (!/^https?:/i.test(descriptor.url)) {
        throw new Error(`RunPod artifact URL must be absolute, got ${JSON.stringify(descriptor.url)}`);
      }
      const response = await fetchImpl(descriptor.url, { signal: AbortSignal.timeout(timeoutMs) });
      if (!response.ok) {
        throw new Error(`GET ${descriptor.url} failed with ${response.status}`);
      }
      return Buffer.from(await response.arrayBuffer());
    },
  };

  async function collect(jobId, first) {
    // One deadline for the whole job, not per poll: a queued job that never starts costs the same
    // wall clock as a running one that never finishes, and both have to end.
    const deadline = Date.now() + timeoutMs;
    let status = first;
    while (PENDING.has(status.status)) {
      if (Date.now() >= deadline) {
        throw new Error(`RunPod job ${jobId} still ${status.status} after ${timeoutMs} ms`);
      }
      await sleep(pollIntervalMs);
      status = await call(`${base}/status/${jobId}`);
    }
    if (status.status !== "COMPLETED") {
      throw new Error(
        `RunPod job ${jobId} ended ${status.status}: ${String(status.error ?? "").slice(0, 400)}`,
      );
    }
    if (!status.output) {
      throw new Error(`RunPod job ${jobId} completed with no output`);
    }
    return status.output;
  }
}
