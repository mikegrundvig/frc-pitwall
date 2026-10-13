// The page's cursor: which moment of the robot's session every view shows. Part of the page's core,
// and no view's own: it is either live, pinned to the newest loop as loops arrive, or it stands on
// one loop of the session, by that loop's index. A view reads it (cursor()), hears when it moves
// (onCursor), and moves it (seek, step, goLive, play); what a view shows of a loop is its own
// business. So two views showing the session always show the same moment of it.
//
// The robot records all the time, into the session's memory of the last five minutes, and the
// cursor moves only what the page shows of that: it never rewinds the robot or the simulator.
// While the cursor stands on an earlier loop the page still follows the robot, the memory keeps
// filling, and the state bar still says how the robot is now.
//
// What the memory holds arrives beside the Robot view's newest loop (robotView.live: its index and
// time, and session: the oldest loop held, when it was, and when the session began). A loop is
// known by its index, and when it was is the robot's to say: loops are not evenly spaced (a slow
// first loop, an overrun), so the time of a loop is worked out between the nearest loops whose
// times the robot has said (noteTime): the memory's two ends, every event, every loop shown.

import { robot, onChange } from "./core.js";

/** How long a robot loop lasts when the session can't say yet, in nanoseconds: 20 ms. */
const LOOP_NANOS = 20e6;

/** The speeds the cursor plays at, as shares of the robot's own. */
export const RATES = [1, 0.5, 0.25, 0.1];

const state = {
  live: true,
  index: -1, // the loop it stands on while not live
  playing: false,
  rate: 1,
  agedOut: false, // whether its loop left the memory, and it moved to the oldest held
};
const listeners = [];
let timer = null;
let playedAt = 0; // when the cursor last moved by playing, in this page's milliseconds
let owed = 0; // the part of a loop played and not yet moved
let sessionOf = null; // when the session the cursor stands in began: another is another robot

/** How many loops' times are kept: every event's, and those of the loops lately shown. */
const TIMES_KEPT = 8000;

let times = []; // [index, nanos] of loops whose time the robot said, in the order of their indices
let timesOf = null; // when the session they are of began

/**
 * What the session's memory holds now: {oldest, newest} loops by index, {oldestNanos, newestNanos}
 * when they were on the robot's clock, {startedNanos} when the session began, {loopNanos} how long
 * a loop lasts, and {events} how many events the session has found. Null before the first loop
 * arrives.
 */
export function span() {
  const live = robot.doc && robot.doc.robotView && robot.doc.robotView.live;
  if (!live || typeof live.index !== "number" || !live.session) return null;
  const session = live.session;
  const loops = live.index - session.oldest;
  const oldestNanos = session.oldestNanos ?? live.timestampNanos;
  return {
    oldest: session.oldest,
    newest: live.index,
    oldestNanos,
    newestNanos: live.timestampNanos,
    startedNanos: session.startedNanos ?? oldestNanos,
    loopNanos: loops > 0 ? (live.timestampNanos - oldestNanos) / loops : LOOP_NANOS,
    events: session.events || 0,
  };
}

/**
 * The cursor: {live} or not, the {index} of the loop shown (the newest while live, -1 before any),
 * {behind} how many loops that is before the newest, {playing} and at what {rate}, and {agedOut},
 * true once its loop left the memory and it moved to the oldest still held.
 */
export function cursor() {
  const held = span();
  const newest = held ? held.newest : -1;
  const index = state.live ? newest : state.index;
  return { live: state.live, index, behind: Math.max(0, newest - index), playing: state.playing,
           rate: state.rate, agedOut: state.agedOut };
}

/** Calls {@code listener(cursor)} whenever the cursor moves, or stops or starts being live. */
export function onCursor(listener) {
  listeners.push(listener);
}

/**
 * Notes when a loop was, as the robot said it (an event's time, a loop's view): the times of the
 * loops between are worked out from these.
 */
export function noteTime(index, nanos) {
  const held = span();
  if (!held) return;
  if (held.startedNanos !== timesOf) {
    timesOf = held.startedNanos;
    times = []; // another robot program's session
  }
  const at = firstAtOrAfter(0, index);
  if (at < times.length && times[at][0] === index) return;
  times.splice(at, 0, [index, nanos]);
  if (times.length > TIMES_KEPT) times.shift();
  // The cursor's own loop is now known exactly: whoever shows its time shows it again.
  if (!state.live && state.index === index) tell();
}

/** The place of the first noted time whose index (0) or time (1) is one given or more. */
function firstAtOrAfter(which, value) {
  let low = 0;
  let high = times.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (times[middle][which] < value) low = middle + 1;
    else high = middle;
  }
  return low;
}

/**
 * The loops with known times on each side of an index (which 0) or a time (which 1): [before,
 * after], each [index, nanos], from the noted times and the memory's two ends.
 */
function around(held, which, value) {
  let before = [held.oldest, held.oldestNanos];
  let after = [held.newest, held.newestNanos];
  if (held.startedNanos !== timesOf) return [before, after]; // none noted of this session yet
  const at = firstAtOrAfter(which, value);
  const earlier = times[at - 1];
  const later = times[at];
  if (earlier && earlier[0] > before[0] && earlier[0] < after[0]) before = earlier;
  if (later && later[0] < after[0] && later[0] > before[0]) after = later;
  return [before, after];
}

/** When a loop was, on the robot's clock in nanoseconds: exactly where the robot said, else between. */
export function timeOf(index) {
  const held = span();
  if (!held) return 0;
  const [before, after] = around(held, 0, index);
  if (after[0] === before[0]) return before[1];
  return before[1] + (index - before[0]) / (after[0] - before[0]) * (after[1] - before[1]);
}

/** The loop nearest a time on the robot's clock, among those the memory holds. */
export function loopAt(nanos) {
  const held = span();
  if (!held) return -1;
  const [before, after] = around(held, 1, nanos);
  const index = after[1] === before[1] ? before[0]
    : before[0] + Math.round((nanos - before[1]) / (after[1] - before[1]) * (after[0] - before[0]));
  return Math.min(held.newest, Math.max(held.oldest, index));
}

/** Stands the cursor on a loop, the nearest the memory holds: it is no longer live. */
export function seek(index) {
  const held = span();
  if (!held) return;
  const to = Math.min(held.newest, Math.max(held.oldest, Math.round(index)));
  if (!state.live && state.index === to && !state.agedOut) return;
  state.live = false;
  state.index = to;
  state.agedOut = false;
  sessionOf = held.startedNanos;
  tell();
}

/** Moves the cursor a number of loops, earlier (negative) or later, from where it is. */
export function step(loops) {
  pause();
  seek(cursor().index + loops);
}

/** Pins the cursor to the newest loop again. */
export function goLive() {
  if (state.live) return;
  stopTimer();
  state.live = true;
  state.playing = false;
  state.agedOut = false;
  tell();
}

/** Plays from the cursor at a share of the robot's speed. Live, there is nothing ahead to play. */
export function play(rate = state.rate) {
  state.rate = rate;
  if (state.live) {
    tell();
    return;
  }
  state.playing = true;
  playedAt = performance.now();
  owed = 0;
  if (timer === null) timer = setInterval(advance, 20);
  tell();
}

/** Stops playing: the cursor stays on its loop. */
export function pause() {
  if (!state.playing) return;
  stopTimer();
  state.playing = false;
  tell();
}

function stopTimer() {
  clearInterval(timer);
  timer = null;
}

/** Moves a playing cursor as far as the time since it last moved says, in whole loops. */
function advance() {
  const held = span();
  if (!held || state.live) return;
  const now = performance.now();
  owed += (now - playedAt) * 1e6 * state.rate / held.loopNanos;
  playedAt = now;
  const loops = Math.floor(owed);
  if (loops === 0) return;
  owed -= loops;
  // Played up to the newest loop, it is live: there is nothing more to play.
  if (state.index + loops >= held.newest) goLive();
  else seek(state.index + loops);
}

function tell() {
  const now = cursor();
  for (const listener of listeners) {
    try {
      listener(now);
    } catch (error) {
      console.error(error); // one view's bug doesn't stop the others
    }
  }
}

// The memory moves on under a cursor that stands still. Once its loop is the oldest held no longer,
// the cursor moves to the oldest that is, and says so.
onChange((patch, snapshot) => {
  if (state.live || !(snapshot || "robotView" in patch)) return;
  const held = span();
  // Another robot program answers now, with a session of its own: its loops count from 0 again.
  if (!held || held.startedNanos !== sessionOf) {
    goLive();
    return;
  }
  if (state.index < held.oldest || state.index > held.newest) {
    state.agedOut = state.index < held.oldest;
    state.index = Math.min(held.newest, Math.max(held.oldest, state.index));
    tell();
  }
});
