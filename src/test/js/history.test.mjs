// The session's history: a value moved by its tuning's start moving (a value kept on the robot,
// not in the code) is said as that, with nothing to restore, and never blamed on a dashboard or
// on the code; a value tuned elsewhere still is another's change.

import { test, before } from "node:test";
import assert from "node:assert/strict";
import { entries } from "../page/history.js";
import { connectTo, documentWith, patch } from "./stream.mjs";

const PATH = "/Vision/front/config/gain";

/** A camera's gain as the robot describes it: where its tuning starts, and the code's. */
function gain(committed, written) {
  return { config: "front camera", field: "gain", unit: "", limits: "16 to 255", min: 16, max: 255,
           committed, written, record: "CameraTuning", group: "", meaning: "" };
}

/** What a step added to the history, as a source and its items' before and after. */
function added(before) {
  return entries.slice(before).map((entry) => [entry.kind, entry.source,
    entry.items.map((item) => item.from + "→" + item.to).join(", "),
    (entry.code || []).map((item) => item.from + "→" + item.to).join(", ")]);
}

before(() => {
  connectTo(documentWith({ tunables: { [PATH]: gain(160, 160) }, values: { [PATH]: 160 } }));
});

test("each move is said by what moved it", () => {
  const steps = [
    {
      what: "a venue's kept gain taken: the start and the value move together",
      send: () => patch({ tunables: { [PATH]: gain(180, 160) }, values: { [PATH]: 180 } }),
      added: [["start", "where its tuning starts moved, to a value kept on the robot", "160→180",
               ""]],
    },
    {
      what: "a dashboard tunes it: the start stays",
      send: () => patch({ values: { [PATH]: 190 } }),
      added: [["elsewhere", "a dashboard or another page", "180→190", ""]],
    },
    {
      what: "the robot restarts, its code unchanged: only the tuned value is undone",
      send: () => connectTo(documentWith({ robot: { program: "again", name: "test robot",
                                                   mode: "Disabled" },
                                          tunables: { [PATH]: gain(160, 160) },
                                          values: { [PATH]: 160 } })),
      added: [["restart", "the robot restarted", "190→160", ""]],
    },
    {
      what: "the venue's gain applied again after the restart",
      send: () => patch({ tunables: { [PATH]: gain(180, 160) }, values: { [PATH]: 180 } }),
      added: [["start", "where its tuning starts moved, to a value kept on the robot", "160→180",
               ""]],
    },
  ];
  for (const step of steps) {
    const before = entries.length;
    step.send();
    assert.deepEqual(added(before), step.added, step.what);
  }
});
