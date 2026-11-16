// A card's lines, drawn one way wherever a card is: on Pit, and in the Sim panel's auto card. Each
// level has its own mark, never its color alone.

import { test, before } from "node:test";
import assert from "node:assert/strict";
import { Element } from "./dom.mjs";
import { lineItem, mountDevices } from "../page/devices.js";
import { makeAuto } from "../page/sim-auto.js";
import { connectTo, documentWith, serve, settle } from "./stream.mjs";

const LINES = [
  { label: "Auto", value: "Left: two pieces", level: "ok" },
  { label: "Starts", value: "on the left line", level: "note" },
  { label: "Path", value: "ends 0.3 m short of its last piece", level: "warning" },
  { label: "Checked", value: "never ran in the simulator", level: "problem" },
];

/** Each line as drawn: its mark, the mark's tone, its label and what it says. */
function drawn(list) {
  return list.children.map((item) => {
    const [mark, label, value] = item.children;
    return [mark.textContent, mark.className, label.textContent, value.textContent];
  });
}

const EXPECTED = [
  ["✓", "sev ok", "Auto", "Left: two pieces"],
  ["•", "sev none", "Starts", "on the left line"],
  ["▲", "sev warn", "Path", "ends 0.3 m short of its last piece"],
  ["✗", "sev bad", "Checked", "never ran in the simulator"],
];

const pit = new Element("main");

before(() => {
  mountDevices(pit);
  connectTo(documentWith({
    devices: {
      auto: { kind: "Autonomous", autonomous: true, state: "Left", level: "warning", lines: LINES,
              downloads: [], uploads: [], choices: [] },
      venue: { kind: "Field", state: "Week 1", level: "ok", lines: [], downloads: [], uploads: [],
               choices: [], presses: [{ id: "keep", label: "Keep", asks: "" },
                                      { id: "make", label: "Make venue", asks: "Event name" }] },
    },
  }));
});

test("each level has its own mark and tone", () => {
  const list = new Element("ul");
  list.append(...LINES.map(lineItem));
  assert.deepEqual(drawn(list), EXPECTED);

  const unknown = new Element("ul");
  unknown.append(lineItem({ label: "Odd", value: "a level from a newer robot", level: "nope" }));
  assert.deepEqual(drawn(unknown), [["•", "sev none", "Odd", "a level from a newer robot"]],
    "a level the page doesn't know is a note");
});

test("the Sim panel's auto card draws its lines as Pit does", () => {
  const onPit = pit.find((element) => element.className === "slist");
  const auto = makeAuto({ compact: true });
  auto.render({ devices: true });
  const inSim = auto.element.find((element) => element.className === "slist");

  assert.deepEqual(drawn(onPit), EXPECTED, "on Pit");
  assert.deepEqual(drawn(inSim), EXPECTED, "in the Sim panel");
});

test("a card's buttons are drawn on it, one that asks with its text box before it", () => {
  const card = pit.find((element) => element.getAttribute("aria-label") === "venue");
  const buttons = card.findAll((element) => element.tagName === "BUTTON");
  assert.deepEqual(buttons.map((button) => button.textContent), ["Keep", "Make venue"]);
  const asks = card.findAll((element) => element.getAttribute("placeholder") !== null);
  assert.deepEqual(asks.map((input) => input.getAttribute("placeholder")), ["Event name"],
    "only the press that asks for words has a text box");
});

test("what a press's box held is cleared once the robot takes it, and kept when it refuses", async () => {
  const card = pit.find((element) => element.getAttribute("aria-label") === "venue");
  const box = card.find((element) => element.getAttribute("placeholder") === "Event name");
  const make = card.findAll((element) => element.tagName === "BUTTON")
    .find((button) => button.textContent === "Make venue");
  // Where the page says what happened, as its own page has them.
  for (const id of ["live", "live-urgent", "toast"]) {
    const element = new Element("div");
    element.setAttribute("id", id);
    document.body.append(element);
  }
  let answer = { status: 422, body: { detail: "give the event's name" } };
  serve(() => answer);
  box.value = "  ";
  make.click();
  await settle();
  assert.equal(box.value, "  ", "refused: kept to fix");
  answer = { status: 200, body: { message: "Made Week 1" } };
  box.value = "Week 1";
  make.click();
  await settle();
  assert.equal(box.value, "", "taken: cleared");
});
