#!/usr/bin/env node
/**
 * Compare keydown→next-frame latency with caret=on vs caret=off.
 * Usage: node scripts/caret-typing-perf.mjs
 * Requires: google-chrome/chromium, vite devDependencies, puppeteer-core (dev).
 */
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const root = path.join(fileURLToPath(new URL(".", import.meta.url)), "..");

const CHROME =
  process.env.CHROME_PATH ||
  ["/usr/local/bin/google-chrome", "/usr/bin/google-chrome", "/usr/bin/chromium"].find(
    (p) => p,
  );

async function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.listen(0, "localhost", () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
    s.on("error", reject);
  });
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
}

async function runScenario(browser, baseUrl, caret, fill) {
  const page = await browser.newPage();
  const fillQuery = fill === "2000" ? "&fill=2000" : "";
  await page.goto(`${baseUrl}/tests/caretPerf.browser.html?caret=${caret}${fillQuery}`, {
    waitUntil: "networkidle0",
  });
  await page.waitForSelector("[data-caret-perf-ready='1']");
  const client = await page.createCDPSession();
  await client.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await page.focus("textarea[data-perf-input]");
  const result = await page.evaluate(() => window.runCaretTypingBenchmark(80));
  await page.close();
  return result;
}

async function main() {
  if (!CHROME) throw new Error("Chrome/Chromium not found; set CHROME_PATH");
  const port = await freePort();
  const vite = spawn("pnpm", ["exec", "vite", "--port", String(port), "--strictPort"], {
    cwd: root,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, BROWSER: "none" },
  });
  const baseUrl = `http://localhost:${port}`;
  vite.stderr.on("data", (chunk) => process.stderr.write(chunk));
  vite.stdout.on("data", (chunk) => process.stderr.write(chunk));
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${baseUrl}/`);
      if (res.ok) break;
    } catch {
      await new Promise((r) => setTimeout(r, 150));
    }
  }
  if (Date.now() >= deadline) throw new Error("Vite boot timeout");

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });

  const matrix = [];
  try {
    for (const fill of ["empty", "2000"]) {
      for (const caret of ["off", "on"]) {
        const r = await runScenario(browser, baseUrl, caret, fill === "empty" ? "" : "2000");
        matrix.push({ caret, fill, ...r });
      }
    }
  } finally {
    await browser.close();
    vite.kill("SIGTERM");
  }

  const rows = matrix.map((r) => ({
    ...r,
    fill: r.fill || (r.fill === "" ? "empty" : r.fill),
  }));

  console.log(JSON.stringify({ throttle: "4x", samples: 80, rows: matrix }, null, 2));

  for (const fill of ["empty", "2000"]) {
    const off = matrix.find((r) => r.fill === fill && r.caret === "off");
    const on = matrix.find((r) => r.fill === fill && r.caret === "on");
    if (!off || !on) continue;
    const deltaP50 = on.p50 - off.p50;
    const deltaP95 = on.p95 - off.p95;
    console.log(
      `${fill}: Δp50=${deltaP50.toFixed(2)}ms Δp95=${deltaP95.toFixed(2)}ms (on ${on.p50.toFixed(2)}/${on.p95.toFixed(2)} vs off ${off.p50.toFixed(2)}/${off.p95.toFixed(2)})`,
    );
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
