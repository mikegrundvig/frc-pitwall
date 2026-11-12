// The time strip under a view of the session: the memory it spans, its times and its marks, where
// the cursor is, and the keys that move it. A strip 1000 pixels wide over a session of ten seconds
// draws a second every 100 pixels.

import { test, before } from "node:test";
import assert from "node:assert/strict";
import { Element, Event } from "./dom.mjs";
import { makeTimeline } from "../page/timeline.js";
import { cursor, goLive } from "../page/cursor.js";
import { connectTo, serve, settle, drawFrames, robotView, documentWith } from "./stream.mjs";

const STARTED = 5e12;
const NEWEST = 500; // ten seconds of 20 ms loops

function event(number, loop, about, severity = "NOTE") {
  return { number, loop, timestampNanos: STARTED + loop * 20e6, about, severity,
           sentence: about + " changed" };
}

const EVENTS = [
  event(0, 100, "pivot"),
  event(1, 102, "claw beam"), // 40 ms later: 4 pixels from the first, so drawn with it
  event(2, 300, "shooter", "PROBLEM"),
];

let strip;

before(async () => {
  Element.width = 1000;
  serve(() => ({ body: { events: EVENTS, found: EVENTS.length } }));
  connectTo(documentWith({
    robotView: robotView({ index: NEWEST, startedNanos: STARTED, events: EVENTS.length }),
  }));
  await settle();
  strip = makeTimeline().element;
  drawFrames();
});

function drawn(test) {
  return strip.findAll(test);
}

function text(className) {
  return strip.find((element) => element.className.split(" ").includes(className)).textContent;
}

function press(key, more = {}) {
  document.dispatchEvent(new Event("keydown", { key, ...more }));
  drawFrames();
}

test("the strip marks every second of the memory, its times kept inside it at the ends", () => {
  const labels = drawn((element) => element.getAttribute("class") === "ts-tick-label");

  assert.deepEqual(labels.map((label) => label.textContent),
    ["0 s", "1 s", "2 s", "3 s", "4 s", "5 s", "6 s", "7 s", "8 s", "9 s", "10 s"]);
  const anchors = labels.map((label) => label.getAttribute("text-anchor"));
  assert.equal(anchors[0], "start", "at the left end");
  assert.equal(anchors[5], "middle");
  assert.equal(anchors[10], "end", "at the right end");
});

test("events that would touch are one mark with their count, and a problem is marked so", () => {
  const marks = drawn((element) => (element.getAttribute("class") || "").startsWith("ts-mark"));

  assert.equal(marks.length, 2);
  assert.equal(marks[0].getAttribute("class"), "ts-mark");
  assert.equal(marks[0].find((element) => element.localName === "text").textContent, "2");
  assert.equal(marks[0].find((element) => element.localName === "title").textContent,
    "pivot changed\nclaw beam changed");
  assert.equal(marks[1].getAttribute("class"), "ts-mark problem");
  assert.equal(marks[1].find((element) => element.localName === "text").textContent, "✗");
});

test("the keys move the cursor through the session, and back to live", () => {
  const rows = [
    { name: "live", keys: [], index: NEWEST, live: true, status: "● Live", time: "10.00 s" },
    { name: "the event before", keys: [["ArrowLeft", { shiftKey: true }]], index: 300,
      live: false, status: "❚❚ 4.0 s behind live", time: "6.00 s · −4.0 s · loop 300" },
    { name: "a loop earlier", keys: [["ArrowLeft"]], index: 299, live: false,
      status: "❚❚ 4.0 s behind live", time: "5.98 s · −4.0 s · loop 299" },
    { name: "the event before that", keys: [["ArrowLeft", { shiftKey: true }]], index: 102,
      live: false, status: "❚❚ 8.0 s behind live", time: "2.04 s · −8.0 s · loop 102" },
    { name: "a loop later", keys: [["ArrowRight"]], index: 103, live: false,
      status: "❚❚ 7.9 s behind live", time: "2.06 s · −7.9 s · loop 103" },
    { name: "L, back to live", keys: [["l"]], index: NEWEST, live: true, status: "● Live",
      time: "10.00 s" },
    { name: "Esc, back to live", keys: [["ArrowLeft"], ["Escape"]], index: NEWEST, live: true,
      status: "● Live", time: "10.00 s" },
  ];
  for (const row of rows) {
    for (const [key, more] of row.keys) press(key, more);

    assert.equal(cursor().index, row.index, row.name);
    assert.equal(cursor().live, row.live, row.name);
    assert.equal(text("ts-status"), row.status, row.name);
    assert.equal(text("ts-time"), row.time, row.name);
  }
  goLive();
});

test("a key the strip doesn't use, or one with a modifier, is left alone", () => {
  const ignored = [["a"], ["ArrowLeft", { ctrlKey: true }], ["ArrowLeft", { altKey: true }]];
  for (const [key, more] of ignored) {
    press(key, more);
    assert.equal(cursor().live, true, key + " " + JSON.stringify(more || {}));
  }
});
