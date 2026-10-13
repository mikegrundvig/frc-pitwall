// The Robot view's earlier loops: each loop's view as the robot answers for it (GET
// /api/robot/view?index=), asked for one at a time and kept, so that dragging the cursor over loops
// already seen draws at once and stepping loop by loop never waits.
//
// Only the latest loop asked for is fetched: a cursor dragged across a thousand loops asks for the
// one it is on when the last answer arrives, not for a thousand. When nothing is asked for, the
// loops beside the last one shown are fetched, since a step goes there next. It keeps a few hundred
// views, the latest it was asked for, and never the whole session.

import { api } from "./core.js";

/** How many views it keeps: each is a few kilobytes. */
const KEPT = 400;

const kept = new Map(); // loop index -> its view, in the order they were last asked for
let asked = null; // {index, then}: the loop wanted now, and who wants it
let beside = []; // loops to fetch when nothing is wanted
let fetching = false;

/** A loop's view, if it is kept. */
export function keptView(index) {
  return kept.get(index) || null;
}

/**
 * Wants a loop's view: {@code then(view)} is called with it, at once if it is kept, and otherwise
 * when the robot answers, unless another loop was wanted meanwhile. The view is null when the
 * session no longer holds the loop.
 *
 * @param near the loops to fetch after it while nothing else is wanted: where a step goes next
 */
export function wantView(index, then, near = []) {
  const view = kept.get(index);
  beside = near.filter((each) => each >= 0 && !kept.has(each));
  if (view) {
    kept.delete(index);
    kept.set(index, view); // asked for last, so dropped last
    asked = null;
    then(view);
  } else {
    asked = { index, then };
  }
  pump();
}

/** Forgets every view: the session is another robot program's now. */
export function forgetViews() {
  kept.clear();
  asked = null;
  beside = [];
}

async function pump() {
  if (fetching) return;
  const wanted = asked;
  const index = wanted ? wanted.index : beside.shift();
  if (index === undefined) return;
  fetching = true;
  let view = null;
  try {
    const response = await api("GET", "/api/robot/view?index=" + index);
    if (response.ok) view = await response.json();
  } catch (error) {
    view = null; // not answering: the cursor asks again when it next moves
  }
  fetching = false;
  if (view) keep(index, view);
  if (wanted && asked === wanted) {
    asked = null;
    wanted.then(view);
  }
  pump();
}

function keep(index, view) {
  kept.set(index, view);
  while (kept.size > KEPT) kept.delete(kept.keys().next().value);
}
