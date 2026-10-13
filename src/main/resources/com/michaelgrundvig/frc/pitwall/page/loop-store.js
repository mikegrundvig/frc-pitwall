// What a view shows of the session's earlier loops, as the robot answers for each (the Robot view's
// GET /api/robot/view?index=, the World view's GET /api/scenes/<id>/state?index=), asked for one at
// a time and kept, so that dragging the cursor over loops already seen draws at once and stepping
// loop by loop never waits.
//
// Only the latest loop asked for is fetched: a cursor dragged across a thousand loops asks for the
// one it is on when the last answer arrives, not for a thousand. When nothing is asked for, the
// loops beside the last one shown are fetched, since a step goes there next. A store keeps a few
// hundred loops, the latest it was asked for, and never the whole session.

import { api } from "./core.js";

/** How many loops a store keeps: each is a few kilobytes. */
const KEPT = 400;

/**
 * Makes a store of one view's loops: {kept, want, forget}.
 *
 * @param addressOf the address the robot answers at for a loop, by the loop's index
 */
export function makeLoopStore(addressOf) {
  const kept = new Map(); // loop index -> what the robot answered, in the order last asked for
  let asked = null; // {index, then}: the loop wanted now, and who wants it
  let beside = []; // loops to fetch when nothing is wanted
  let fetching = false;

  async function pump() {
    if (fetching) return;
    const wanted = asked;
    const index = wanted ? wanted.index : beside.shift();
    if (index === undefined) return;
    fetching = true;
    let answer = null;
    try {
      const response = await api("GET", addressOf(index));
      if (response.ok) answer = await response.json();
    } catch (error) {
      answer = null; // not answering: the cursor asks again when it next moves
    }
    fetching = false;
    if (answer) {
      kept.set(index, answer);
      while (kept.size > KEPT) kept.delete(kept.keys().next().value);
    }
    if (wanted && asked === wanted) {
      asked = null;
      wanted.then(answer);
    }
    pump();
  }

  return {
    /** A loop's answer, if it is kept. */
    kept(index) {
      return kept.get(index) || null;
    },

    /**
     * Wants a loop: {@code then(answer)} is called with it, at once if it is kept, and otherwise
     * when the robot answers, unless another loop was wanted meanwhile. The answer is null when the
     * session no longer holds the loop.
     *
     * @param near the loops to fetch after it while nothing else is wanted: where a step goes next
     */
    want(index, then, near = []) {
      const answer = kept.get(index);
      beside = near.filter((each) => each >= 0 && !kept.has(each));
      if (answer) {
        kept.delete(index);
        kept.set(index, answer); // asked for last, so dropped last
        asked = null;
        then(answer);
      } else {
        asked = { index, then };
      }
      pump();
    },

    /** Forgets every loop: the session is another robot program's now. */
    forget() {
      kept.clear();
      asked = null;
      beside = [];
    },
  };
}
