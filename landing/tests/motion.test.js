import { test } from "node:test";
import assert from "node:assert/strict";
import { weightAtDistance, wordRevealProgress } from "../src/title-motion.js";

test("the title goes from maximum weight at the pointer to base weight at the radius", () => {
  assert.equal(weightAtDistance(0), 720);
  assert.equal(weightAtDistance(140), 555);
  assert.equal(weightAtDistance(280), 390);
  assert.equal(weightAtDistance(900), 390);
});

test("scroll lighting stays in range when a section is entered or skipped", () => {
  assert.equal(wordRevealProgress(720, 480, 800), 0);
  assert.equal(wordRevealProgress(320, 480, 800), 0.5);
  assert.equal(wordRevealProgress(-120, 480, 800), 1);
});
