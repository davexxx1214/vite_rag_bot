import assert from "node:assert/strict";
import test from "node:test";
import { createFrameBatch } from "../src/frameBatch.ts";

function fixture() {
  let nextFrame = 0;
  let updates = 0;
  const callbacks = new Map();
  const batch = createFrameBatch(() => updates++, callback => {
    callbacks.set(++nextFrame, callback);
    return nextFrame;
  }, frame => callbacks.delete(frame));
  return { batch, callbacks, updates: () => updates };
}

test("stream deltas share one frame and can schedule another after publishing", () => {
  const { batch, callbacks, updates } = fixture();
  for (let count = 0; count < 100; count++) batch.schedule();
  assert.equal(callbacks.size, 1);
  const callback = [...callbacks.values()][0];
  callbacks.clear();
  callback();
  assert.equal(updates(), 1);
  batch.schedule();
  assert.equal(callbacks.size, 1);
});

test("terminal events flush once and remove queued updates", () => {
  const { batch, callbacks, updates } = fixture();
  batch.schedule();
  batch.flush();
  batch.flush();
  assert.equal(updates(), 1);
  assert.equal(callbacks.size, 0);
});

test("cancellation prevents a stale frame from overwriting the final answer", () => {
  const { batch, callbacks, updates } = fixture();
  batch.schedule();
  batch.cancel();
  batch.flush();
  assert.equal(updates(), 0);
  assert.equal(callbacks.size, 0);
});
