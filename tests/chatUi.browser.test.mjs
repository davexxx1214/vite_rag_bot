import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { after, before, test } from "node:test";
import { createServer } from "vite";
import puppeteer from "puppeteer-core";

const executablePath = [process.env.CHROME_PATH,
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/local/bin/google-chrome",
].find(candidate => candidate && existsSync(candidate));
const messages = Array.from({ length: 14 }, (_, index) => ({
  id: `history-${index}`, role: index % 2 ? "assistant" : "user",
  content: index % 2
    ? `### 历史回答 ${index}\n\n这是用于检查阅读位置的历史内容。`.repeat(4)
    : `查询投资组合 ${index}`,
}));
messages.at(-1).content += "\n\n| 组合 | 名称 | 币种 | 总值 | 现金占比 | 持仓数 |\n| --- | --- | --- | --- | --- | --- |\n| pf-001 | US Growth | USD | 5,000 | 37% | 2 |\n\n```js\n" + "const portfolio = ".repeat(40) + "\n```";
let server;
let browser;
let baseUrl;
let requests = 0;

before(async () => {
  if (!executablePath) return;
  server = await createServer({ cacheDir: "target/vite-chat-ui-test", server: { port: 0, strictPort: false }, plugins: [{
    name: "chat-ui-test-api",
    configureServer(instance) {
      instance.middlewares.use((request, response, next) => {
        const url = request.url.split("?")[0];
        if (!url.startsWith("/api/")) return next();
        response.setHeader("Content-Type", "application/json");
        const json = value => response.end(JSON.stringify(value));
        if (url === "/api/docs") return json([{ slug: "welcome", title: "Welcome", summary: "Test help center", source: "#", content: "## Help\nTest content." }]);
        if (url === "/api/agent/users") return json([{ id: "user-001", username: "Alice", displayName: "Alice" }]);
        if (url === "/api/agent/sessions") return json([{ id: "test-session", title: "Test conversation", updatedAt: new Date().toISOString() }]);
        if (url === "/api/agent/storage") return json({ bytes: 100, quotaBytes: 10000, overQuota: false });
        if (url === "/api/agent/sessions/test-session") {
          const earlier = request.url.includes("offset=");
          return json({ messages: earlier ? [{ id: "earlier", role: "assistant", content: "更早的记录。\n\n".repeat(40) }] : messages,
            offset: earlier ? 0 : 50, total: 64, nextOffset: null });
        }
        if (url === "/api/chat") {
          requests++;
          response.setHeader("Content-Type", "application/x-ndjson");
          let count = 0;
          const timer = setInterval(() => {
            if (count++ < 80) response.write(JSON.stringify({ type: "delta", text: `流式内容 ${count} 👋。\n\n` }) + "\n");
            else {
              clearInterval(timer);
              response.end(JSON.stringify({ type: "done" }) + "\n");
            }
          }, 25);
          response.on("close", () => clearInterval(timer));
          return;
        }
        response.statusCode = 404;
        json({ error: "Unexpected test endpoint" });
      });
    },
  }] });
  await server.listen();
  baseUrl = `http://localhost:${server.httpServer.address().port}`;
  browser = await puppeteer.launch({ executablePath, headless: true, args: ["--no-sandbox"] });
});

after(async () => {
  await browser?.close();
  await server?.close();
});

async function openChat(width = 1280) {
  const page = await browser.newPage();
  await page.setViewport({ width, height: 800 });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  await page.waitForSelector('[aria-label="Open AI help bot"]');
  await page.click('[aria-label="Open AI help bot"]');
  await page.waitForSelector('.chat-panel textarea:not([aria-hidden="true"])');
  await page.waitForFunction(() => document.activeElement?.tagName === "TEXTAREA");
  await page.waitForFunction(() => document.querySelector(".chat-messages")?.textContent.includes("历史回答"));
  return { page, errors };
}

const options = { skip: !executablePath && "Chrome/Edge unavailable; set CHROME_PATH" };

test("container controls, keyboard focus, IME and mobile layout", options, async () => {
  const { page, errors } = await openChat();
  try {
    assert.equal(await page.$eval(".chat-secondary-tools", element => getComputedStyle(element).display), "none");
    await page.click('[aria-label="更多聊天操作"]');
    await page.waitForSelector("#chat-tools-menu");
    await page.keyboard.press("Escape");
    assert.ok(await page.$(".chat-panel"));
    await page.waitForFunction(() => !document.querySelector("#chat-tools-menu"));
    await page.$eval(".chat-panel", element => { element.style.width = "520px"; });
    assert.notEqual(await page.$eval(".chat-secondary-tools", element => getComputedStyle(element).display), "none");
    await page.setViewport({ width: 320, height: 720 });
    await page.$eval(".chat-panel", element => { element.style.width = ""; });
    await page.waitForFunction(() => {
      const panel = document.querySelector(".chat-panel");
      return panel && panel.scrollWidth <= panel.clientWidth;
    }, { timeout: 5000 }).catch(async error => {
      console.log(await page.$eval(".chat-panel", panel => ({ width: panel.clientWidth, scroll: panel.scrollWidth,
        overflowing: Array.from(panel.querySelectorAll("*")).filter(element => element.scrollWidth > element.clientWidth + 1)
          .map(element => ({ tag: element.tagName, className: element.className, width: element.clientWidth, scroll: element.scrollWidth })) })));
      await page.screenshot({ path: "target/chat-ui-mobile.png" });
      throw error;
    });
    const layout = await page.$eval(".chat-panel", element => ({
      width: element.clientWidth, overflow: element.scrollWidth > element.clientWidth,
      inputSize: getComputedStyle(element.querySelector("textarea")).fontSize,
    }));
    assert.ok(layout.width <= 300);
    await page.screenshot({ path: "target/chat-ui-mobile.png" });
    const overflowing = await page.$eval(".chat-panel", panel => Array.from(panel.querySelectorAll("*")).filter(element =>
      element.getBoundingClientRect().right > panel.getBoundingClientRect().right + 1).map(element => ({
      tag: element.tagName, className: element.className, right: element.getBoundingClientRect().right,
    })));
    assert.equal(layout.overflow, false, JSON.stringify(overflowing));
    assert.equal(layout.inputSize, "16px");
    const beforeRequests = requests;
    await page.type('.chat-panel textarea:not([aria-hidden="true"])', "输入法选字");
    await page.$eval('.chat-panel textarea:not([aria-hidden="true"])', element => {
      element.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true }));
      element.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", keyCode: 229, bubbles: true }));
    });
    assert.equal(requests, beforeRequests);
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => document.activeElement?.getAttribute("aria-label") === "Open AI help bot");
    assert.deepEqual(errors, []);
  } finally { await page.close(); }
});

test("streaming respects a small upward scroll and resumes only at the bottom", options, async () => {
  const { page, errors } = await openChat();
  try {
    await page.type('.chat-panel textarea:not([aria-hidden="true"])', "测试流式回答");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => document.querySelector(".chat-messages")?.textContent.includes("流式内容 4"));
    const bounds = await page.$eval(".chat-messages", element => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
    });
    await page.mouse.move(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2);
    await page.mouse.wheel({ deltaY: -10 });
    await page.waitForSelector(".chat-latest");
    const top = await page.$eval(".chat-messages", element => element.scrollTop);
    await page.waitForFunction(() => document.querySelector(".chat-latest")?.textContent.includes("有新内容"));
    assert.ok(Math.abs(await page.$eval(".chat-messages", element => element.scrollTop) - top) <= 1);
    await page.click(".chat-latest");
    await page.waitForFunction(() => !document.querySelector(".chat-latest"));
    await page.waitForFunction(() => !document.querySelector(".markdown-message[data-streaming]"));
    const gap = await page.$eval(".chat-messages", element => element.scrollHeight - element.clientHeight - element.scrollTop);
    assert.ok(gap <= 2);
    assert.ok(await page.$eval(".chat-messages", element => element.textContent.includes("流式内容 80")));
    assert.deepEqual(errors, []);
  } finally { await page.close(); }
});

test("prepending history preserves the visible message position", options, async () => {
  const { page, errors } = await openChat();
  try {
    await page.$eval(".chat-messages", element => { element.scrollTop = 0; });
    await page.waitForSelector(".chat-latest");
    const beforeTop = await page.$eval(".chat-messages button", element => element.nextElementSibling.getBoundingClientRect().top);
    await page.$eval(".chat-messages button", element => element.click());
    await page.waitForFunction(() => document.querySelector(".chat-messages")?.textContent.includes("更早的记录"));
    const afterTop = await page.$eval(".chat-messages > div", element => element.children[1].getBoundingClientRect().top);
    assert.ok(Math.abs(afterTop - beforeTop) <= 2, `history shifted by ${afterTop - beforeTop}px`);
    assert.deepEqual(errors, []);
  } finally { await page.close(); }
});

test("wide tables and code scroll locally instead of expanding the message", options, async () => {
  const { page, errors } = await openChat(320);
  try {
    const widths = await page.$eval(".chat-panel", panel => ({
      panel: panel.clientWidth, panelScroll: panel.scrollWidth,
      messages: panel.querySelector(".chat-messages").clientWidth,
      messagesScroll: panel.querySelector(".chat-messages").scrollWidth,
      table: panel.querySelector(".markdown-table-scroll").clientWidth,
      tableScroll: panel.querySelector(".markdown-table-scroll").scrollWidth,
      code: panel.querySelector("pre").clientWidth, codeScroll: panel.querySelector("pre").scrollWidth,
    }));
    assert.equal(widths.panel, widths.panelScroll);
    assert.equal(widths.messages, widths.messagesScroll);
    assert.ok(widths.tableScroll > widths.table);
    assert.ok(widths.codeScroll > widths.code);
    assert.deepEqual(errors, []);
  } finally { await page.close(); }
});
