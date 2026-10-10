import assert from "node:assert/strict";
import test from "node:test";
import { applyActivityEvent as apply, finishActivities as finish } from "../src/agentActivity.ts";
const start = (id, name = "search_history") => ({ type: "tool_start", id, name, message: "正在搜索历史记忆…" });

test("repeated calls stay distinct and outcomes correlate by invocation ID", () => {
  let steps = apply([], start("first"), 1000);
  steps = apply(steps, { type: "tool_end", id: "first", ok: false, elapsedMs: 200 }, 1200);
  steps = apply(steps, start("retry"), 1300);
  steps = apply(steps, { type: "tool_end", id: "retry", ok: true, elapsedMs: 400 }, 1700);
  assert.deepEqual(steps.map(step => [step.id, step.state, step.elapsedMs]), [["first", "failed", 200], ["retry", "completed", 400]]);
});

test("out-of-order outcomes only complete the matching invocation", () => {
  let steps = apply(apply([], start("a"), 0), start("b"), 20);
  steps = apply(steps, { type: "tool_end", id: "b", ok: true, elapsedMs: 30 }, 50);
  assert.deepEqual(steps.map(step => step.state), ["running", "completed"]);
  assert.equal(apply(steps, { type: "tool_end", id: "missing", ok: true, elapsedMs: 0 }, 50).length, 2);
});

test("streamed answer and compaction status update the current phase", () => {
  let steps = apply([], { type: "activity_start", id: "model", message: "正在分析请求…" }, 0);
  steps = apply(steps, { type: "activity_update", id: "model", message: "正在生成回答…" }, 100);
  assert.equal(steps[0].message, "正在生成回答…");
  assert.equal(steps[0].state, "running");
  assert.equal(apply(steps, { type: "activity_start", id: "model", message: "duplicate" }, 100).length, 1);
});

test("failure closes running phases without overwriting successful or failed tools", () => {
  let steps = apply(apply([], start("complete"), 0), start("failed"), 0);
  steps = apply(steps, { type: "tool_end", id: "complete", ok: true, elapsedMs: 1 }, 1);
  steps = apply(steps, { type: "tool_end", id: "failed", ok: false, elapsedMs: 2 }, 2);
  steps = apply(steps, { type: "activity_start", id: "model", message: "分析结果" }, 10);
  steps = finish(steps, "failed", 30);
  assert.deepEqual(steps.map(step => step.state), ["completed", "failed", "failed"]);
  assert.equal(steps[2].elapsedMs, 20);
});

test("cancellation stops running tools and late events cannot mark them successful", () => {
  let steps = finish(apply([], start("a"), 10), "cancelled", 50);
  steps = apply(steps, { type: "tool_end", id: "a", ok: true, elapsedMs: 100 }, 100);
  assert.equal(steps[0].state, "cancelled");
  assert.equal(steps[0].elapsedMs, 40);
});
