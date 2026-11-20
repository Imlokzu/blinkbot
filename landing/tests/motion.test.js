import { test } from "node:test";
import assert from "node:assert/strict";
import { flipDelay, wrap } from "../src/effects.js";

test("wrap keeps the loop offset inside its range in both directions", () => {
  assert.equal(wrap(-100, 0, -30), -30);
  assert.equal(wrap(-100, 0, -130), -30);
  assert.equal(wrap(-100, 0, 20), -80);
  assert.equal(wrap(-100, 0, -100), -100);
});

test("flip delays rise along a quarter sine and stay within a quarter of the duration", () => {
  assert.equal(flipDelay(0, 10, 1), 0);
  assert.equal(flipDelay(0, 1, 1), 0);
  const delays = Array.from({ length: 10 }, (_, i) => flipDelay(i, 10, 1));
  for (let i = 1; i < delays.length; i++) assert.ok(delays[i] > delays[i - 1]);
  assert.ok(delays.at(-1) < 0.25);
});
