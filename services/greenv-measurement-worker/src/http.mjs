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

export function createHttpTrigger({ measure, log = () => {} }) {
  const jobs = new Map();

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
      if (raw.length > 64 * 1024) reject(new Error("request body is too large"));
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
        const id = randomUUID();
        jobs.set(id, { id, status: "running", startedAt: new Date().toISOString(), result: null, error: null });
        // Deliberately not awaited: the response is the receipt, the work outlives it.
        measure(body).then(
          (result) => jobs.set(id, { ...jobs.get(id), status: "done", result }),
          (error) => jobs.set(id, { ...jobs.get(id), status: "failed", error: { code: error.code ?? "unknown", message: error.message } }),
        );
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
