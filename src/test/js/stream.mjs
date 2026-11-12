// The robot, as a test plays it: its event stream (a snapshot of its document, then patches) and
// its API's answers. The page's own core.js follows the stream and asks the API as it does in a
// browser.

import { Event } from "./dom.mjs";
import { connect } from "../page/core.js";

/**
 * Answers the page's requests: {@code answer(path, options)} gives {status, body}, or a promise of
 * one, or throws for a robot that isn't answering. Every request is kept in {@code asked}.
 */
export function serve(answer) {
  const asked = [];
  globalThis.fetch = async (path, options = {}) => {
    asked.push({ path, method: options.method || "GET" });
    const { status = 200, body = null } = await answer(path, options);
    return new Response(JSON.stringify(body), { status });
  };
  return asked;
}

function stream() {
  const opened = globalThis.EventSource.opened;
  return opened[opened.length - 1];
}

function send(type, data) {
  stream().dispatchEvent(new Event(type, { data: JSON.stringify(data) }));
}

/**
 * A robot's document as its snapshot sends it: what every view reads, with a test's own parts.
 */
export function documentWith(parts = {}) {
  return {
    robot: { program: "test", name: "test robot", mode: "Disabled" },
    tuning: { enabled: true, fieldConnected: false },
    values: {},
    tunables: {},
    changes: {},
    actions: {},
    ...parts,
  };
}

/** Connects the page to the robot, which sends its whole document. */
export function connectTo(document) {
  connect();
  send("snapshot", { stream: "test", document });
}

/** The robot sends what changed in its document, as a JSON merge patch. */
export function patch(changed) {
  send("patch", changed);
}

/** Lets every answer on its way arrive, and what waits on them run. */
export async function settle() {
  for (let turn = 0; turn < 20; turn++) await new Promise((resolve) => setImmediate(resolve));
}

/** Draws the frames the page asked for, as the browser does before it paints. */
export function drawFrames() {
  const frames = globalThis.__frames;
  const now = [...frames.values()];
  frames.clear();
  for (const frame of now) frame(0);
}

/**
 * The Robot view's newest loop and what the session's memory holds, as the robot sends them: what
 * the cursor and the session's events follow.
 */
export function robotView({ index, oldest = 0, loopNanos = 20e6, startedNanos = 0, events = 0 }) {
  return {
    live: {
      index,
      timestampNanos: startedNanos + index * loopNanos,
      session: { oldest, oldestNanos: startedNanos + oldest * loopNanos, startedNanos, events },
    },
  };
}
