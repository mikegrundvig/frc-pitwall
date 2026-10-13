// The page's keyboard rule for Esc, in one place, because Esc is the one key several things want
// and one of them is safety.
//
// Esc, from any view, whatever has the focus:
//
//   1. In the simulator, while the robot is enabled: disables it, and stops whatever is running
//      (an experiment's run, an action). Nothing else hears the key: not a field's edit, not the
//      time strip, not an open dialog, which a second Esc then closes.
//   2. Otherwise, while something is running: stops it. (On a real robot the page never disables:
//      the Driver Station does, with its own keys.)
//   3. Otherwise Esc keeps its small meanings: in a value's field it drops the edit (rows.js), and
//      elsewhere it returns the time strip's cursor to live (timeline.js).
//
// A safety action wins over a view's action, so only a disabled robot's Esc means anything else.
// No key enables: Enable is a button, pressed.
//
// The other keys, none of which clash (each is listed with where it lives):
//
//   P                 opens and closes the side panel (side-dock.js); not while typing
//   W A S D, J L, ← →, R   drive, only while keyboard driving is on (sim-drive.js); not while
//                     typing; the state bar says so on every view
//   ← →, Shift+← →, Space, L   move the time strip's cursor, while a strip is on screen and
//                     keyboard driving is off (timeline.js)
//   Home End PageUp PageDown   on the time strip's track, when it has the focus
//   /                 focuses Tune's filter, in Tune (tune.js)
//   Enter, ?          in a value's field: apply it, show its notes (rows.js)

import { running, send, announce, say } from "./core.js";
import { isEnabled, disable } from "./sim-state.js";

/** Stops whatever is running on the robot: an experiment's run, and any action. */
async function stopRunning() {
  const now = running();
  if (now.length === 0) return;
  announce("Stopping " + now.map((thing) => thing.name).join(", ") + ".", true);
  for (const thing of now) {
    const result = thing.kind === "experiment"
      ? await send("POST", "/api/experiments", { cancel: thing.name, run: thing.run })
      : await send("POST", "/api/actions", { cancel: thing.name });
    if (!result.ok && result.status !== 409) say(thing.name + ": " + result.problem.detail, true);
  }
}

/** Starts the page's rule for Esc. */
export function startKeys() {
  // First of all, before any view's own handler: an enabled simulated robot is disabled.
  window.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || !isEnabled()) return;
    // No view's Esc runs, and nor does the browser's own (a search box clearing, a dialog
    // closing): this one was for the robot.
    event.stopImmediatePropagation();
    event.preventDefault();
    disable();
    stopRunning();
  }, true);
  // Then, with the robot disabled (or a real one): Esc stops what runs.
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || event.defaultPrevented
        || document.querySelector("dialog[open]")) {
      return;
    }
    if (running().length === 0) return;
    event.preventDefault();
    stopRunning();
  });
}
