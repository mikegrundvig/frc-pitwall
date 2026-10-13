// The session's events as this page holds them: the loops in which something changed, each with
// what changed in the robot's own words (GET /api/robot/events). The robot finds them; the page
// only keeps them in order, asks for those it does not have yet when the robot says it has found
// more (robotView.live.session.events), and lets go of those whose loops left the session's memory.
//
// Which of them are shown is one choice for the page (the filter): those about one joint, sensor
// or gate, by its name, and the problems only. The timeline's marks, its previous and next, and
// the list of events all follow it.

import { api, onChange } from "./core.js";
import { span, noteTime } from "./cursor.js";

let events = []; // every event held, in order
let next = 0; // the number of the first event not asked for yet
let asking = false;
let sessionOf = null; // when the session they are of began
const filter = { about: "", problemsOnly: false };
const listeners = [];

/** Calls {@code listener()} whenever the events held, or the filter, change. */
export function onEvents(listener) {
  listeners.push(listener);
}

/** The filter: {about} a name, or empty for everything, and {problemsOnly}. */
export function eventFilter() {
  return { ...filter };
}

/** Changes the filter: any of {about, problemsOnly}. */
export function setEventFilter(change) {
  Object.assign(filter, change);
  tell();
}

function shows(event) {
  if (filter.problemsOnly && event.severity !== "PROBLEM") return false;
  return !filter.about || event.about.includes(filter.about);
}

/** The events the filter shows, in order. */
export function shownEvents() {
  return filter.about || filter.problemsOnly ? events.filter(shows) : events;
}

/** The latest shown event in a loop before one given, or null. */
export function eventBefore(loop) {
  const shown = shownEvents();
  for (let each = shown.length - 1; each >= 0; each--) {
    if (shown[each].loop < loop) return shown[each];
  }
  return null;
}

/** The first shown event in a loop after one given, or null. */
export function eventAfter(loop) {
  return shownEvents().find((event) => event.loop > loop) || null;
}

function tell() {
  for (const listener of listeners) {
    try {
      listener();
    } catch (error) {
      console.error(error); // one view's bug doesn't stop the others
    }
  }
}

/** Asks the robot for the events found since the last it holds, and drops those that aged out. */
async function refresh() {
  const held = span();
  if (!held) return;
  if (held.startedNanos !== sessionOf) {
    // Another robot program's session: its events count from 0 again.
    sessionOf = held.startedNanos;
    events = [];
    next = 0;
    tell();
  }
  let changed = false;
  while (events.length && events[0].loop <= held.oldest) {
    events.shift(); // an event is what differs from the loop before, which the memory let go
    changed = true;
  }
  if (changed) tell();
  if (asking || held.events <= next) return;
  asking = true;
  let answered = false;
  try {
    const response = await api("GET", "/api/robot/events?since=" + next);
    if (response.ok && span() && span().startedNanos === sessionOf) {
      answered = true;
      const answer = await response.json();
      const found = answer.events.filter((event) => event.number >= next);
      events.push(...found);
      for (const event of found) noteTime(event.loop, event.timestampNanos);
      // None came although the robot has found more: they aged out before they were asked for.
      next = found.length ? found[found.length - 1].number + 1 : answer.found;
      if (found.length) tell();
    }
  } catch (error) {
    // Not answering: asked again when the robot next says how many it has found.
  }
  asking = false;
  const now = span();
  // More than one answer holds, or more were found meanwhile.
  if (answered && now && now.events > next) refresh();
}

onChange((patch, snapshot) => {
  if (snapshot || "robotView" in patch) refresh();
});
