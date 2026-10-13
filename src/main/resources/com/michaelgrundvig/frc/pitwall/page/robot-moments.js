// The Robot view's earlier loops: each loop's view as the robot answers for it (GET
// /api/robot/view?index=), kept by a loop store (loop-store.js).

import { makeLoopStore } from "./loop-store.js";

const views = makeLoopStore((index) => "/api/robot/view?index=" + index);

/** A loop's view, if it is kept. */
export function keptView(index) {
  return views.kept(index);
}

/**
 * Wants a loop's view: {@code then(view)} is called with it, at once if it is kept, and otherwise
 * when the robot answers, unless another loop was wanted meanwhile. The view is null when the
 * session no longer holds the loop.
 *
 * @param near the loops to fetch after it while nothing else is wanted: where a step goes next
 */
export function wantView(index, then, near = []) {
  views.want(index, then, near);
}

/** Forgets every view: the session is another robot program's now. */
export function forgetViews() {
  views.forget();
}
