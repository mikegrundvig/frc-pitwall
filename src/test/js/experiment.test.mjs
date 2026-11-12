// A run's scores as Experiment compares them, and the words Tune and Experiment share for edits
// that can't be sent.

import { test } from "node:test";
import assert from "node:assert/strict";
import { summary } from "../page/experiment.js";
import { outsideLimits } from "../page/edits.js";
import { plural } from "../page/core.js";

/** A run of two measurements, each with its jumps' scores as the robot sends them. */
function run(steps, peaks = {}) {
  return {
    scores: { steps, peaks },
    signals: [{ name: "Front left voltage", unit: "V" }, { name: "Front right voltage", unit: "V" }],
  };
}

function jump(rise, overshoot, settling, error) {
  return { rise, overshoot, settling, error };
}

test("a run's score in each column is the worst of its jumps, and never is worst of all", () => {
  const rows = [
    { name: "every jump scored",
      steps: { "Front left": [jump(0.1, 0.05, 0.3, -0.02)],
               "Front right": [jump(0.2, 0.01, 0.2, 0.01)] },
      worst: { rise: 0.2, overshoot: 0.05, settling: 0.3, error: 0.02 } },
    { name: "a jump that never settled, sent as text",
      steps: { "Front left": [jump(0.1, 0.05, "NaN", 0.01)],
               "Front right": [jump(0.2, 0.01, 0.2, 0.01)] },
      worst: { rise: 0.2, overshoot: 0.05, settling: Infinity, error: 0.01 } },
    { name: "a score that isn't a finite number counts as never, not as no score",
      steps: { "Front left": [jump(NaN, 0.05, 0.3, 0.01)],
               "Front right": [jump(0.2, Infinity, 0.2, -Infinity)] },
      worst: { rise: Infinity, overshoot: Infinity, settling: 0.3, error: Infinity } },
    { name: "a missing score",
      steps: { "Front left": [{ rise: 0.1 }] },
      worst: { rise: 0.1, overshoot: Infinity, settling: Infinity, error: Infinity } },
  ];
  for (const row of rows) {
    const scored = summary(run(row.steps));
    for (const [column, worst] of Object.entries(row.worst)) {
      assert.equal(scored[column], worst, row.name + ": " + column);
    }
  }
});

test("a measurement's own scores are its jumps and its signals' peaks", () => {
  const scored = summary(run(
    { "Front left": [jump(0.1, 0.05, 0.3, 0.02)], "Front right": [jump(0.2, 0.01, 0.2, 0.01)] },
    { "Front left voltage": 9.5, "Front right voltage": 11 }), "Front left");

  assert.equal(scored.rise, 0.1);
  assert.deepEqual(scored.peaks, { V: 9.5 });
  assert.equal(summary({ signals: [] }), null, "a run not scored yet");
  assert.ok(Number.isNaN(summary(run({})).rise), "a run with no jumps has no score");
});

test("edits that can't be sent are said the same way wherever they wait", () => {
  const rows = [
    [["kP", 1, "run"], "kP is outside its limits or not a number: fix it to run."],
    [["kP, kD", 2, "run"], "kP, kD are outside their limits or not a number: fix them to run."],
    [["1", 1, "apply"], "1 is outside its limits or not a number: fix it to apply."],
    [["3", 3, "apply"], "3 are outside their limits or not a number: fix them to apply."],
  ];
  for (const [args, said] of rows) assert.equal(outsideLimits(...args), said);
  assert.deepEqual([0, 1, 2].map((count) => plural(count, "edit", "edits")),
    ["edits", "edit", "edits"]);
});
