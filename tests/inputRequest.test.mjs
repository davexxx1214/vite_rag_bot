import assert from "node:assert/strict";
import test from "node:test";
import { canAnswer, makeInputAnswer } from "../src/inputRequest.ts";
const request = () => ({ requestId: "question-1", status: "pending", allowFreeText: true,
  options: [{ id: "one", label: "Choice", value: "Name" }], expiresAt: new Date(Date.now() + 60000).toISOString() });

test("no preselected answer and only issued option IDs are accepted", () => {
  assert.equal(makeInputAnswer(request(), "", ""), null);
  assert.equal(makeInputAnswer(request(), "forged", ""), null);
  assert.deepEqual(makeInputAnswer(request(), "one", ""), { optionId: "one" });
});
test("custom answers respect free-text capability and length", () => {
  assert.deepEqual(makeInputAnswer(request(), "custom", " New Name "), { text: "New Name" });
  assert.equal(makeInputAnswer(request(), "custom", "   "), null);
  assert.equal(makeInputAnswer(request(), "custom", "x".repeat(501)), null);
  assert.equal(makeInputAnswer({ ...request(), allowFreeText: false }, "custom", "Name"), null);
});
test("resolved, cancelled and expired cards cannot submit again", () => {
  for (const status of ["answered", "cancelled", "expired"]) assert.equal(canAnswer({ ...request(), status }), false);
  assert.equal(canAnswer({ ...request(), expiresAt: "2000-01-01T00:00:00Z" }), false);
  assert.equal(canAnswer({ ...request(), expiresAt: "invalid" }), false);
});
