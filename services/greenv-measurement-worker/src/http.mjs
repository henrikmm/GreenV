// A trigger that does not need RabbitMQ.
//
// Three callers want this: a backfill over segments captured before the worker existed, an
// operator re-measuring one segment after changing the class policy, and anything that would
// rather call an endpoint than publish a message. It is the same use case behind the queue, so
// the two can never disagree about what measuring a segment means.
//
// Accepted and polled rather than answered in one request: a measurement is a depth run plus a
// CPU pass, minutes rather than seconds, and every proxy between here and a caller would give up
// first. Same shape as the local app's own quality API.

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

// Finished jobs are kept only long enough for a caller to poll for the result. Without this the
// map grows for the process lifetime, and each entry holds a full result including one position
// row per sampled frame — which a backfill over thousands of segments turns into real memory.
const JOB_TTL_MS = 60 * 60 * 1000;
const MAX_JOBS = 32;

export function createHttpTrigger({ measure, gate, log = () => {} }) {
  const jobs = new Map();

  const forget = (id) => {
    const job = jobs.get(id);
    if (job?.timer) clearTimeout(job.timer);
    jobs.delete(id);
  };

  const retire = (id) => {
    const job = jobs.get(id);
    if (!job) return;
    job.timer = setTimeout(() => forget(id), JOB_TTL_MS);
    job.timer.unref();
    // Insertion order is oldest-first, so the first finished entry is the right one to evict.
    while (jobs.size > MAX_JOBS) {
      const stale = [...jobs.entries()].find(([, candidate]) => candidate.status !== "running");
      if (!stale) break;
      forget(stale[0]);
    }
  };

  const json = (response, status, body) => {
    const payload = JSON.stringify(body);
    response.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(payload) });
    response.end(payload);
  };

  const readBody = (request) => new Promise((resolve, reject) => {
    let raw = "";
    request.on("data", (chunk) => {
      raw += chunk;
      // A trigger carries identifiers, never payload. Anything larger is a mistake or an attack.
      // Destroyed, not merely rejected: settling the promise leaves this listener attached, and
      // the body would go on accumulating past the limit it is supposed to enforce.
      if (raw.length > 64 * 1024) {
        request.destroy();
        reject(new Error("request body is too large"));
      }
    });
    request.on("end", () => resolve(raw));
    request.on("error", reject);
  });

  const server = createServer(async (request, response) => {
    const route = `${request.method} ${new URL(request.url, "http://local").pathname}`;
    try {
      if (route === "GET /health") return json(response, 200, { status: "ok", jobs: jobs.size });

      if (route === "POST /measurements") {
        const body = JSON.parse((await readBody(request)) || "{}");
        // One measurement at a time across both entrances. A caller here would rather be told to
        // come back than be queued behind work it cannot see.
        if (gate.busy) {
          return json(response, 409, { error: "a measurement is already running; retry when it finishes" });
        }
        const id = randomUUID();
        jobs.set(id, { id, status: "running", startedAt: new Date().toISOString(), result: null, error: null });
        // Deliberately not awaited: the response is the receipt, the work outlives it.
        gate.tryRun(() => measure(body))?.then(
          (result) => { jobs.set(id, { ...jobs.get(id), status: "done", result }); retire(id); },
          (error) => { jobs.set(id, { ...jobs.get(id), status: "failed", error: { code: error.code ?? "unknown", message: error.message } }); retire(id); },
        ) ?? (() => { forget(id); })();
        return json(response, 202, { id, status: "running" });
      }

      const job = /^GET \/measurements\/([0-9a-f-]{36})$/.exec(route);
      if (job) {
        const found = jobs.get(job[1]);
        return found ? json(response, 200, found) : json(response, 404, { error: "no such measurement job" });
      }

      return json(response, 404, { error: "unknown route" });
    } catch (error) {
      log({ event: "http-error", route, error: error.message });
      return json(response, 400, { error: error.message });
    }
  });

  return {
    server,
    jobs,
    listen: (port, address) => new Promise((resolve) => server.listen(port, address, resolve)),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
