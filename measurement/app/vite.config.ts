import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
// @ts-expect-error - plain ESM plugin, no type declarations
import { localApi } from "./vite-plugins/local-api.mjs";
// @ts-expect-error - plain ESM plugin, no type declarations
import { phoneIntake } from "./vite-plugins/phone-intake.mjs";

/**
 * Hosted mode (`src/lib/hosted.ts`): the studio built as the public, read-only demo site. It has
 * no dev server behind it, so a build that forgot where the demo API is would publish a page that
 * can only ever say it has no captures. Refuse instead.
 *
 * Read through `loadEnv`, the way the bundle itself reads them, so a flag set in a `.env` file
 * cannot make the page hosted while this file still copies the fixtures in as a studio would.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, fileURLToPath(new URL(".", import.meta.url)), "VITE_");
  const hosted = env.VITE_VERGE_HOSTED === "1";
  if (hosted && !env.VITE_DEMO_API_BASE) {
    throw new Error("VITE_VERGE_HOSTED=1 needs VITE_DEMO_API_BASE (e.g. https://greenvapi.example/v1/demo/)");
  }

  // Fixtures live at repo root; expose them to the dev server as /fixtures/*.
  return {
    plugins: [react(), localApi(), phoneIntake()],
    // Not on the demo site: its runs come from the server, and the recorded fixtures are a studio's
    // evidence, not something a public page should carry.
    publicDir: hosted ? false : fileURLToPath(new URL("../fixtures", import.meta.url)),
    /**
     * 5173 by default, overridable with PORT.
     *
     * `strictPort` stays on: a dev server that silently drifts to another port is worse than one
     * that refuses, because the cloud proxy, the artifact URLs and the frame routes are all
     * same-origin and a half-loaded app on an unexpected port fails in confusing ways. What the
     * override adds is a way to run this beside another Vite project that has already taken 5173
     * — which can happen on any development computer — instead of being unable to start at all.
     */
    server: {
      host: "127.0.0.1",
      origin: `http://127.0.0.1:${Number(process.env.PORT) || 5173}`,
      port: Number(process.env.PORT) || 5173,
      strictPort: true,
      allowedHosts: [],
      cors: false,
    },
    // geometry/ lives outside app/ (it is the one piece with no browser dependency), but
    // the app imports it and it must be typechecked and tested by the same loop. scripts/ joins
    // it for the same reason: the inspector projects points onto frames using the project's own
    // camera convention, and a drift between the two would draw a plausible, wrong picture.
    test: {
      environment: "node",
      // `vite-plugins` joined on 2026-08-13, when deleting became archiving. The local API is the
      // only thing standing between a delete and evidence nobody can repaint, and it was the one
      // directory here with no assertions in it at all.
      include: [
        "src/**/*.test.ts",
        "vite-plugins/**/*.test.mjs",
        "../geometry/**/*.test.ts",
        "../scripts/**/*.test.mjs",
      ],
      // Several real-data fixture suites each reconstruct the same million-point cloud.
      // Running them together makes their wall-clock time depend on worker contention and
      // can trip their correctness timeouts even when every assertion passes.
      fileParallelism: false,
    },
  };
});
