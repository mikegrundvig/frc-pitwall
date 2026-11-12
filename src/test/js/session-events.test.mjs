// The session's events as the page holds them: asked for as the robot finds them, kept in order,
// let go as their loops leave the memory, started over for another program's session, and shown
// through one filter.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  shownEvents, eventBefore, eventAfter, eventFilter, setEventFilter, onEvents,
} from "../page/session-events.js";
import { connectTo, patch, serve, settle, robotView, documentWith } from "./stream.mjs";

/** An event as the robot sends one: in a loop, about some names, and how much it matters. */
function event(number, loop, about, severity = "NOTE") {
  return { number, loop, timestampNanos: loop * 20e6, about, severity,
           sentence: about.join(", ") + " changed" };
}

const FOUND = [
  event(0, 10, ["pivot"]),
  event(1, 20, ["claw beam"], "PROBLEM"),
  event(2, 30, ["pivot", "aimed"]),
  event(3, 40, ["shooter"], "PROBLEM"),
];

/** A robot that has found the events given, and answers for those from {@code since} on. */
function robotWith(found) {
  return serve((path) => {
    const since = Number(new URL(path, "http://robot").searchParams.get("since"));
    return { body: { events: found.filter((each) => each.number >= since), found: found.length } };
  });
}

function loops(events) {
  return events.map((each) => each.loop);
}

let session = 0;

/** The page connects to a new session of the robot's, which has found these events. */
async function newSession(found, { index = 100, oldest = 0 } = {}) {
  session++;
  const asked = robotWith(found);
  connectTo(documentWith({
    robotView: robotView({ index, oldest, startedNanos: session * 1e12, events: found.length }),
  }));
  await settle();
  return asked;
}

test("the events the robot has found are asked for and kept in order", async () => {
  let told = 0;
  onEvents(() => told++);
  const asked = await newSession(FOUND);

  assert.deepEqual(loops(shownEvents()), [10, 20, 30, 40]);
  assert.deepEqual(asked.map((request) => request.path), ["/api/robot/events?since=0"]);
  assert.ok(told > 0, "the views were told");
});

test("events found later are asked for from the first one not held", async () => {
  const asked = await newSession(FOUND.slice(0, 2));
  const more = robotWith(FOUND);

  patch({ robotView: robotView({ index: 101, startedNanos: session * 1e12, events: 4 }) });
  await settle();

  assert.deepEqual(loops(shownEvents()), [10, 20, 30, 40]);
  assert.equal(asked.length, 1);
  assert.deepEqual(more.map((request) => request.path), ["/api/robot/events?since=2"]);
});

test("events whose loops left the memory are let go", async () => {
  await newSession(FOUND);

  patch({ robotView: robotView({ index: 120, oldest: 25, startedNanos: session * 1e12,
                                 events: 4 }) });
  await settle();

  assert.deepEqual(loops(shownEvents()), [30, 40]);
});

test("another program's session starts over from its own first event", async () => {
  await newSession(FOUND);

  await newSession([event(0, 5, ["arm"])]);

  assert.deepEqual(loops(shownEvents()), [5]);
});

test("the filter shows events about a name, problems only, or both", async () => {
  await newSession(FOUND);
  const rows = [
    { filter: { about: "", problemsOnly: false }, shown: [10, 20, 30, 40] },
    { filter: { about: "pivot", problemsOnly: false }, shown: [10, 30] },
    { filter: { about: "", problemsOnly: true }, shown: [20, 40] },
    { filter: { about: "shooter", problemsOnly: true }, shown: [40] },
    { filter: { about: "pivot", problemsOnly: true }, shown: [] },
  ];
  for (const row of rows) {
    setEventFilter(row.filter);
    assert.deepEqual(eventFilter(), row.filter);
    assert.deepEqual(loops(shownEvents()), row.shown, JSON.stringify(row.filter));
  }
  setEventFilter({ about: "", problemsOnly: false });
});

test("the event before or after a loop is among those the filter shows", async () => {
  await newSession(FOUND);
  const rows = [
    { filter: {}, loop: 25, before: 20, after: 30 },
    { filter: {}, loop: 10, before: null, after: 20 },
    { filter: {}, loop: 40, before: 30, after: null },
    { filter: { problemsOnly: true }, loop: 25, before: 20, after: 40 },
    { filter: { about: "pivot" }, loop: 30, before: 10, after: null },
  ];
  for (const row of rows) {
    setEventFilter({ about: "", problemsOnly: false, ...row.filter });
    const before = eventBefore(row.loop);
    const after = eventAfter(row.loop);
    const name = JSON.stringify(row.filter) + " at " + row.loop;
    assert.equal(before && before.loop, row.before, name);
    assert.equal(after && after.loop, row.after, name);
  }
  setEventFilter({ about: "", problemsOnly: false });
});

test("a robot not answering leaves the events as they are, and is asked again", async () => {
  await newSession(FOUND.slice(0, 2));
  serve(() => {
    throw new Error("unreachable");
  });

  patch({ robotView: robotView({ index: 101, startedNanos: session * 1e12, events: 4 }) });
  await settle();
  assert.deepEqual(loops(shownEvents()), [10, 20]);

  const again = robotWith(FOUND);
  patch({ robotView: robotView({ index: 102, startedNanos: session * 1e12, events: 4 }) });
  await settle();

  assert.deepEqual(loops(shownEvents()), [10, 20, 30, 40]);
  assert.deepEqual(again.map((request) => request.path), ["/api/robot/events?since=2"]);
});
