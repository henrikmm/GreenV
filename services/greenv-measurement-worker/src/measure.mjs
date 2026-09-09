// Calling Verge Studio.
//
// The whole integration is this: spawn a process, write JSON to its stdin, read JSON back. No
// import crosses into `measurement/`, which is what lets that subtree keep being developed as its
// own repository and pushed back with `git subtree push` (AGENTS.md). It also means the two sides
// can be written in different languages, which they are.
//
// `assess-grass.mjs` writes newline-delimited progress to stderr, a compact result to stdout, and
// the three artifacts of the packet to `--out`. Its exit code is the only success signal worth
// trusting: a non-zero exit with a parseable stdout is still a failure.

import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { join } from "node:path";

export const PACKET_FILES = ["assessment.json", "report.html", "SHA256SUMS"];

export function measurementRunner({ cliPath, runsRoot, timeoutMs }, onProgress = () => {}) {
  return {
    async assess(request, outputDirectory) {
      const child = spawn(process.execPath, [cliPath, "--stdin", "--out", outputDirectory], {
        stdio: ["pipe", "pipe", "pipe"],
        env: {
          ...process.env,
          // The inspector resolves a run id against this root. Without it the process would look
          // in the operator's home directory and report the run as missing.
          VERGE_RUNS_ROOT: runsRoot,
        },
      });

      let stdout = "";
      let stderrTail = "";
      let pending = "";
      let reported = null;

      child.stdout.on("data", (chunk) => { stdout += chunk; });
      child.stderr.on("data", (chunk) => {
        pending += chunk;
        stderrTail = `${stderrTail}${chunk}`.slice(-4000);
        let end;
        while ((end = pending.indexOf("\n")) >= 0) {
          const line = pending.slice(0, end);
          pending = pending.slice(end + 1);
          try {
            const parsed = JSON.parse(line);
            if (parsed.error) reported = parsed.error;
            else onProgress(parsed);
          } catch {
            // Runtime notices are not progress, and a warning on stderr is not a failure.
          }
        }
        if (pending.length > 10_000) pending = pending.slice(-10_000);
      });

      const timer = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
      timer.unref();

      const code = await new Promise((resolve, reject) => {
        child.on("error", reject);
        child.on("close", resolve);
        child.stdin.on("error", () => {});
        child.stdin.end(JSON.stringify(request));
      }).finally(() => clearTimeout(timer));

      if (code !== 0) {
        throw new Error(reported ?? `assess-grass.mjs exited ${code}: ${stderrTail.trim().slice(-400)}`);
      }

      const summary = JSON.parse(stdout.trim().split("\n").at(-1));
      const artifacts = {};
      for (const name of PACKET_FILES) artifacts[name] = await readFile(join(outputDirectory, name));
      return { summary, artifacts };
    },
  };
}
