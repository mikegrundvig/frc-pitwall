// The simulator's clock: pause it, run one new loop of a paused one, and stop the simulator. A
// section of the simulator's controls, mounted in the Sim view and, compact, in the Sim panel.
//
// These make time pass (or not) on the simulated robot. The time strip under the World and Robot
// views is another thing: its Loop buttons look back over loops that already ran, and never reach
// the robot. So this section is named for the simulator, and its button says "new".

import { robot, send, h, confirm, announce, stopping } from "./core.js";
import { sim, canAct, patch, answered, uid } from "./sim-state.js";
import { release } from "./sim-drive.js";
import { tunedList } from "./sim-station.js";
import { block } from "./sim-parts.js";

/**
 * The clock's section.
 *
 * @param options {compact}: without Stop, which ends the program and belongs to the Sim view
 * @return {element, render(what)}
 */
export function makeTime({ compact = false } = {}) {
  const ui = {};
  const whyId = uid("sim-step-why");
  ui.message = h("p", { class: "message", hidden: true });
  ui.state = h("p", { class: "state" });
  ui.pause = h("button", { type: "button", onclick: () => {
    const now = sim();
    if (now) patch({ paused: !now.paused }, ui.message);
  } });
  ui.step = h("button", { type: "button", "aria-describedby": whyId, onclick: async () => {
    answered(await send("POST", "/api/simulator/step"), ui.message);
  } }, "Step one new loop");
  ui.stepWhy = h("p", { class: "meta", id: whyId });
  ui.stop = compact ? null : h("button", { type: "button", class: "danger",
                                           onclick: () => stop(ui.message) },
    "Stop the simulator…");
  const element = block("Simulator clock", compact, ui.state,
    h("div", { class: "acts" }, ui.pause, ui.step), ui.stepWhy, ui.message,
    ui.stop && h("div", { class: "acts stoprow" }, ui.stop,
      h("span", { class: "meta" }, "Ends the robot program, as closing WPILib's window did.")));
  return {
    element,
    render(what) {
      if (what.station) render(ui);
    },
  };
}

function render(ui) {
  const now = sim();
  const act = canAct();
  const paused = Boolean(now && now.paused);
  ui.state.className = "state" + (paused ? " paused" : "");
  ui.state.textContent = !now ? "" : paused
    ? "❚❚ Paused: no loops run, so the robot's clock stands still. What's asked waits for a "
      + "step or Resume."
    : "▶ Running, in step with this computer's clock.";
  ui.pause.textContent = paused ? "▶ Resume the simulator" : "❚❚ Pause the simulator";
  ui.pause.className = paused ? "primary" : "";
  ui.pause.disabled = !act;
  ui.step.disabled = !act || !paused;
  // A step makes a new loop. Looking at the loops that already ran is the time strip's job.
  ui.stepWhy.textContent = paused
    ? "Runs one new loop on the robot. The time strip's Loop buttons only look back over loops "
      + "already run."
    : "Pause first: a step runs one new loop of a paused simulator.";
  if (ui.stop) ui.stop.disabled = !act;
}

/** Stops the simulator, after asking: it ends the robot program, and what wasn't committed. */
async function stop(message) {
  const doc = robot.doc;
  const tuned = doc ? Object.keys(doc.changes || {}) : [];
  const yes = await confirm("Stop the simulator?", [
    h("p", null, "The robot program ends, as closing WPILib's simulator window did. AdvantageKit's "
        + "log keeps its last loops. Start it again with ./gradlew run; this page reconnects by "
        + "itself."),
    tuned.length > 0 && [
      h("p", { class: "message bad" }, "● " + (tuned.length === 1 ? "This tuned value isn't"
        : "These " + tuned.length + " tuned values aren't") + " committed, and "
          + (tuned.length === 1 ? "ends" : "end") + " with the program:"),
      tunedList(doc, tuned),
      h("p", { class: "meta" }, "Changes has the lines that commit "
          + (tuned.length === 1 ? "it." : "them.")),
    ],
  ], "Stop the simulator");
  if (!yes) return;
  stopping(); // its stream ending is the program ending, not a lost link
  release();
  const result = await send("POST", "/api/simulator/stop");
  if (!result.ok) {
    stopping(false);
    answered(result, message);
    return;
  }
  announce("Stopping the simulator.", true);
}
