import assert from "node:assert/strict";
import test from "node:test";
import { lerp, resolveAnimatedCaretMode } from "../src/animatedCaret.ts";

test("lerp interpolates linearly", () => {
  assert.equal(lerp(0, 10, 0.5), 5);
  assert.equal(lerp(2, 2, 0.25), 2);
});

test("resolveAnimatedCaretMode respects forced flags and disabled", () => {
  const saved = globalThis.window;
  // @ts-expect-error test shim
  globalThis.window = { matchMedia: () => ({ matches: false }) };
  assert.equal(resolveAnimatedCaretMode({ forceAnimated: true }), "animated");
  assert.equal(resolveAnimatedCaretMode({ forceNative: true }), "native");
  assert.equal(resolveAnimatedCaretMode({ disabled: true }), "native");
  globalThis.window = saved;
});
