#!/usr/bin/env node
/** Capture the Motiva Flutter web preview states used by the design-review workflow. */

import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { extname, join, resolve } from "node:path";

const APP = "http://127.0.0.1:5173";
const EDGE = process.env.BROWSER_BIN
  ?? "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const OUT = resolve(".inspect", "mobile-review");
const WEB = resolve("apps", "mobile", "build", "web");
const PORT = 9334;
const VIEWPORT = { width: 1280, height: 800 };

let nextId = 1;
const pending = new Map();
const wait = (milliseconds) => new Promise((resolveWait) => setTimeout(resolveWait, milliseconds));

function send(socket, method, params = {}) {
  const id = nextId++;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolveCall, rejectCall) => {
    pending.set(id, { resolveCall, rejectCall });
    setTimeout(() => {
      if (pending.delete(id)) rejectCall(new Error(`${method} timed out`));
    }, 30_000);
  });
}

function attach(socket) {
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if (message.error) waiter.rejectCall(new Error(message.error.message));
    else waiter.resolveCall(message.result);
  });
}

async function evaluate(socket, expression) {
  const result = await send(socket, "Runtime.evaluate", {
    expression: `(() => { ${expression} })()`,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text ?? "evaluation failed");
  return result.result.value;
}

async function navigate(socket, url) {
  await send(socket, "Page.navigate", { url });
  for (let attempt = 0; attempt < 40; attempt++) {
    const ready = await evaluate(
      socket,
      `return Boolean(document.querySelector("flt-glass-pane") && document.body.children.length > 1);`,
    );
    if (ready) break;
    await wait(250);
  }
  await wait(500);
  await evaluate(socket, `document.querySelector("flt-semantics-placeholder")?.click(); return true;`);
  await wait(300);
}

async function findLabel(socket, label) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const box = await evaluate(
      socket,
      `const label = ${JSON.stringify(label)};
       const elements = [...document.querySelectorAll("*")];
       const hit = elements.find((element) => element.getClientRects().length > 0
         && (element.getAttribute("aria-label") === label || element.textContent?.trim() === label));
       if (!hit) return null;
       const rect = hit.getBoundingClientRect();
       return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };`,
    );
    if (box) return box;
    await wait(200);
  }
  throw new Error(`nothing labelled ${label}`);
}

async function clickLabel(socket, label) {
  const box = await findLabel(socket, label);
  await wait(200);
  for (const type of ["mousePressed", "mouseReleased"]) {
    await send(socket, "Input.dispatchMouseEvent", {
      type,
      x: box.x,
      y: box.y,
      button: "left",
      buttons: type === "mousePressed" ? 1 : 0,
      clickCount: 1,
    });
  }
  await wait(500);
}

async function scrollCaptureToTop(socket) {
  await send(socket, "Input.dispatchMouseEvent", {
    type: "mouseWheel",
    x: VIEWPORT.width / 2,
    y: VIEWPORT.height / 2,
    deltaX: 0,
    deltaY: -2000,
  });
  await wait(300);
}

async function screenshot(socket, name) {
  const result = await send(socket, "Page.captureScreenshot", { format: "png" });
  const bytes = Buffer.from(result.data, "base64");
  await writeFile(join(OUT, `${name}.png`), bytes);
  console.log(`${name}.png ${(bytes.byteLength / 1024).toFixed(0)} KiB`);
}

async function main() {
  const mime = new Map([
    [".css", "text/css"],
    [".html", "text/html"],
    [".js", "text/javascript"],
    [".json", "application/json"],
    [".png", "image/png"],
    [".wasm", "application/wasm"],
  ]);
  const server = createServer(async (request, response) => {
    try {
      const pathname = decodeURIComponent(new URL(request.url ?? "/", APP).pathname);
      const relative = pathname === "/" ? "index.html" : pathname.slice(1);
      const target = resolve(WEB, relative);
      if (!target.startsWith(`${WEB}\\`) && target !== WEB) throw new Error("invalid path");
      const bytes = await readFile(target);
      response.writeHead(200, { "Content-Type": mime.get(extname(target)) ?? "application/octet-stream" });
      response.end(bytes);
    } catch {
      const index = await readFile(join(WEB, "index.html"));
      response.writeHead(200, { "Content-Type": "text/html" });
      response.end(index);
    }
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(5173, "127.0.0.1", resolveListen);
  });
  await mkdir(OUT, { recursive: true });
  const profile = await mkdtemp(join(tmpdir(), "motiva-review-"));
  const browser = spawn(
    EDGE,
    [
      "--headless=new",
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${profile}`,
      `--window-size=${VIEWPORT.width},${VIEWPORT.height}`,
      "--hide-scrollbars",
      "--no-first-run",
      "--force-device-scale-factor=1",
      "about:blank",
    ],
    { stdio: "ignore" },
  );
  const browserExit = new Promise((resolveExit) => browser.once("exit", resolveExit));

  let socket;
  try {
    let page;
    for (let attempt = 0; attempt < 40 && !page; attempt++) {
      await wait(250);
      try {
        const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
        page = targets.find((target) => target.type === "page");
      } catch {
        // The browser has not exposed its debugging port yet.
      }
    }
    if (!page) throw new Error("browser did not expose a page target");
    socket = new WebSocket(page.webSocketDebuggerUrl);
    attach(socket);
    await new Promise((resolveOpen, rejectOpen) => {
      socket.addEventListener("open", resolveOpen, { once: true });
      socket.addEventListener("error", rejectOpen, { once: true });
    });
    await send(socket, "Page.enable");
    await send(socket, "Runtime.enable");
    await send(socket, "Emulation.setDeviceMetricsOverride", {
      ...VIEWPORT,
      deviceScaleFactor: 1,
      mobile: false,
    });

    await navigate(socket, `${APP}/?screen=splash`);
    await screenshot(socket, "splash");
    await navigate(socket, `${APP}/?screen=login`);
    await screenshot(socket, "login");
    await navigate(socket, `${APP}/?screen=forgotEmail`);
    await screenshot(socket, "forgot-email");
    await navigate(socket, `${APP}/?screen=forgotCode`);
    await screenshot(socket, "forgot-code");
    await navigate(socket, `${APP}/?screen=home`);
    await screenshot(socket, "home");
    await navigate(socket, `${APP}/?screen=upload`);
    await screenshot(socket, "upload");
    await clickLabel(socket, "Iniciar coleta");
    await scrollCaptureToTop(socket);
    await screenshot(socket, "recording");

    await navigate(socket, `${APP}/?screen=upload&offline=1`);
    await clickLabel(socket, "Iniciar coleta");
    await clickLabel(socket, "Encerrar coleta");
    await scrollCaptureToTop(socket);
    await screenshot(socket, "offline-queued");

    await navigate(socket, `${APP}/?screen=upload&phase=preparing`);
    await screenshot(socket, "preparing");
    await navigate(socket, `${APP}/?screen=upload&phase=error`);
    await screenshot(socket, "error");
    await findLabel(socket, "Iniciar coleta");
    await wait(300);
    await screenshot(socket, "error-action");

    await navigate(socket, `${APP}/?screen=network`);
    await screenshot(socket, "network");
    await navigate(socket, `${APP}/?screen=map`);
    await screenshot(socket, "map");

    const metrics = await evaluate(
      socket,
      `const body = document.body.getBoundingClientRect();
       const glass = document.querySelector("flt-glass-pane")?.getBoundingClientRect();
       const frame = document.querySelector('[aria-label="mobile-app-frame"]')?.getBoundingClientRect();
       return {
         viewport: [innerWidth, innerHeight],
         body: [body.width, body.height],
         documentScrollWidth: document.documentElement.scrollWidth,
         glass: glass ? [glass.x, glass.y, glass.width, glass.height] : null,
         frame: frame ? [frame.x, frame.y, frame.width, frame.height] : null,
         semantics: document.querySelectorAll("flt-semantics").length,
       };`,
    );
    console.log(JSON.stringify(metrics));

    await send(socket, "Emulation.setDeviceMetricsOverride", {
      width: 390,
      height: 844,
      deviceScaleFactor: 1,
      mobile: true,
    });
    await navigate(socket, `${APP}/?screen=upload`);
    await screenshot(socket, "phone-upload");
    const phoneMetrics = await evaluate(
      socket,
      `const frame = document.querySelector('[aria-label="mobile-app-frame"]')?.getBoundingClientRect();
       return {
         viewport: [innerWidth, innerHeight],
         documentScrollWidth: document.documentElement.scrollWidth,
         frame: frame ? [frame.x, frame.y, frame.width, frame.height] : null,
       };`,
    );
    console.log(JSON.stringify(phoneMetrics));
  } finally {
    socket?.close();
    browser.kill();
    await Promise.race([browserExit, wait(3000)]);
    await new Promise((resolveClose) => server.close(resolveClose));
    await rm(profile, { recursive: true, force: true }).catch(() => {});
  }
}

await main();
