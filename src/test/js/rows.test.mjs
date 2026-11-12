// A configuration value's row, as Tune, Experiment and a card show it: the value now, an edit
// staged and why it can't be sent, the committed value where it was tuned, the limits as a bar,
// and what the value means. Each scenario is the robot's document and what was typed.

import { test, before, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { makeRow, updateRow } from "../page/rows.js";
import { stage, discard } from "../page/edits.js";
import { robot } from "../page/core.js";
import { connectTo, documentWith } from "./stream.mjs";

const PATH = "/Drive/config/gains/kP";

/** The robot describes the value: its configuration, its limits, and what it means. */
function described(more = {}) {
  return { config: "drivetrain", field: "gains.kP", unit: "V/m", limits: "[0, 100]",
           committed: 16, min: 0, max: 100, meaning: "Volts per meter of error. More is stiffer.",
           ...more };
}

before(() => {
  connectTo(documentWith({ tunables: { [PATH]: described() }, values: { [PATH]: 16 } }));
});

beforeEach(() => {
  discard();
  robot.doc.tunables[PATH] = described();
  robot.doc.values[PATH] = 16;
  robot.doc.changes = {};
  robot.doc.tuning = { enabled: true, fieldConnected: false };
});

function shownRow() {
  const row = makeRow(PATH, "tune");
  updateRow(row);
  return row;
}

/** Each tag's text, the note's lines, and whether the row says it's refused. */
function said(row) {
  return {
    tags: row.tags.children.map((tag) => tag.textContent),
    note: row.note.children.map((line) => line.textContent),
    invalid: row.input.getAttribute("aria-invalid"),
  };
}

test("a row says what's staged, and why it can't be sent", () => {
  const rows = [
    { name: "nothing staged", typed: null, input: "16", tags: [], note: [], invalid: "false" },
    { name: "the value it has", typed: "16.0", input: "16", tags: [], note: [],
      invalid: "false" },
    { name: "an edit", typed: "18", input: "18", tags: ["✎ edit"],
      note: ["✎ 16 V/m → 18 V/m, staged: not applied yet"], invalid: "false" },
    { name: "a big step", typed: "60", input: "60", tags: ["✎ edit"],
      note: ["✎ 16 V/m → 60 V/m, staged: not applied yet", "▲ A big step: 44% of its tuning range"],
      invalid: "false" },
    { name: "not a number", typed: "fast", input: "fast", tags: ["✗ not a number"],
      note: ["✗ Only numbers can be applied."], invalid: "true" },
    { name: "outside its limits", typed: "500", input: "500", tags: ["✗ outside limits"],
      note: ["✎ 16 V/m → 500 V/m, staged: not applied yet",
        "✗ Outside its tuning limits: 500 is above its limit of 100. Checked here, before sending."],
      invalid: "true" },
  ];
  for (const row of rows) {
    discard();
    if (row.typed !== null) stage(PATH, row.typed);
    const shown = shownRow();

    assert.equal(shown.input.value, row.input, row.name);
    assert.deepEqual(said(shown), { tags: row.tags, note: row.note, invalid: row.invalid },
      row.name);
  }
});

test("a tuned value says what it was committed as, and offers it back", () => {
  robot.doc.values[PATH] = 18;
  robot.doc.changes = { [PATH]: { committed: 16 } };

  const row = shownRow();

  assert.deepEqual(said(row).tags, ["● tuned · committed 16"]);
  assert.equal(row.revert.hidden, false);
  assert.equal(row.el.classList.contains("tuned"), true);
});

test("the limits are a bar with the committed value, the value now and an edit on it", () => {
  const rows = [
    { name: "within", tunable: {}, typed: "25", hidden: false,
      committed: "16.00%", now: "16.00%", edit: "25.00%", out: false },
    { name: "an edit beyond", tunable: {}, typed: "150", hidden: false,
      committed: "16.00%", now: "16.00%", edit: "100.00%", out: true },
    { name: "no edit", tunable: {}, typed: null, hidden: false,
      committed: "16.00%", now: "16.00%", edit: null, out: false },
    { name: "limited at one end", tunable: { max: undefined, limits: "≥ 0" }, typed: null,
      hidden: true },
  ];
  for (const row of rows) {
    discard();
    robot.doc.tunables[PATH] = described(row.tunable);
    if (row.typed !== null) stage(PATH, row.typed);

    const range = shownRow().range;

    assert.equal(range.el.hidden, row.hidden, row.name);
    if (row.hidden) continue;
    assert.equal(range.committed.style.left, row.committed, row.name);
    assert.equal(range.now.style.left, row.now, row.name);
    assert.equal(range.edit.hidden, row.edit === null, row.name);
    if (row.edit !== null) {
      assert.equal(range.edit.style.left, row.edit, row.name);
      assert.equal(range.edit.classList.contains("out"), row.out, row.name);
    }
  }
});

test("a row says what its value means, or that nobody has said yet", () => {
  const rows = [
    { meaning: "Volts per meter of error. More is stiffer.", hidden: false,
      says: "Volts per meter of error." },
    { meaning: "", hidden: false, says: "No description yet: add @param kP to its record's Javadoc" },
    { meaning: undefined, hidden: true },
  ];
  for (const row of rows) {
    robot.doc.tunables[PATH] = described({ meaning: row.meaning });
    const shown = shownRow();
    assert.equal(shown.meaning.hidden, row.hidden, String(row.meaning));
    if (!row.hidden) assert.equal(shown.meaning.textContent, row.says);
  }
});

test("with tuning off a value can't be edited, and what's typed is never overwritten", () => {
  robot.doc.tuning = { enabled: false, fieldConnected: false };
  const locked = shownRow();
  assert.equal(locked.input.disabled, true);
  assert.equal(locked.down.disabled, true);

  robot.doc.tuning = { enabled: true, fieldConnected: false };
  const typing = shownRow();
  typing.input.focus();
  typing.input.value = "1";
  robot.doc.values[PATH] = 17;
  updateRow(typing);
  assert.equal(typing.input.value, "1", "the box being typed in keeps what's in it");
  typing.input.blur();
  updateRow(typing);
  assert.equal(typing.input.value, "17");
});

test("Escape in a row drops its edit and shows the value again", () => {
  const row = shownRow();
  row.input.value = "18";
  row.input.dispatchEvent({ type: "input" });
  updateRow(row);
  assert.deepEqual(said(row).tags, ["✎ edit"]);

  row.input.dispatchEvent({ type: "keydown", key: "Escape", preventDefault() {} });
  updateRow(row);

  assert.equal(row.input.value, "16");
  assert.deepEqual(said(row).tags, []);
});
