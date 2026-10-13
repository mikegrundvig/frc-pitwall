// The auto to run, in the simulator: the robot program's own card for it (the one on Pit, with the
// same chooser: devices.js), and under it the one press that runs it.
//
// Running an auto by hand is three things: choose it, set the Driver Station to autonomous, and
// enable. The button does the last two together and says so on its face; choosing stays its own
// press, since choosing an auto must never enable anything. The robot program says which of its
// cards is the auto's (Devices.Device.autonomous()); a program that says none has no such section.

import { robot, h, replace } from "./core.js";
import { makeChoice } from "./devices.js";
import { sim, canAct, enableInAutonomous } from "./sim-state.js";
import { block } from "./sim-parts.js";

/** The robot program's devices that say what autonomous runs, by name. */
function autos() {
  const devices = (robot.doc && robot.doc.devices) || {};
  return Object.entries(devices).filter(([, device]) => device.autonomous === true);
}

/**
 * The auto's section.
 *
 * @return {element, render(what)}
 */
export function makeAuto() {
  const ui = { shownChoices: "" };
  ui.lines = h("ul", { class: "slist" });
  ui.choices = h("div", { class: "dchoices" });
  ui.message = h("p", { class: "message", hidden: true });
  ui.run = h("button", { type: "button", class: "primary",
                         onclick: () => enableInAutonomous(ui.message) },
    "▶ Set autonomous and enable");
  ui.why = h("p", { class: "meta" });
  const element = block("Autonomous", true, ui.lines, ui.choices,
    h("div", { class: "acts" }, ui.run), ui.why, ui.message);
  element.hidden = true;
  return {
    element,
    render(what) {
      if (what.devices || what.station) render(ui, element);
    },
  };
}

function render(ui, element) {
  const found = autos();
  element.hidden = found.length === 0;
  if (found.length === 0) return;
  const [name, device] = found[0];
  replace(ui.lines, (device.lines || []).map((line) => h("li", null,
    h("span", { class: "sev " + (line.level === "problem" ? "bad" : line.level === "ok" ? "ok"
      : "none"), "aria-hidden": "true" },
    line.level === "problem" ? "✗" : line.level === "ok" ? "✓" : "•"),
    h("span", { class: "k" }, line.label), h("span", null, line.value))));
  // The options are rebuilt only when they or what's chosen change, so a pick being made isn't
  // swept away by the next update.
  const choices = device.choices || [];
  const shownNow = JSON.stringify(choices);
  if (shownNow !== ui.shownChoices) {
    ui.shownChoices = shownNow;
    ui.choices.replaceChildren(...choices.map((choice) => makeChoice(name, choice)));
  }
  const now = sim();
  const act = canAct();
  const enabled = Boolean(now && now.enabled);
  ui.run.disabled = !act || enabled;
  // What the press does, before it is pressed: the auto it runs is the robot's, as the card says.
  ui.why.textContent = enabled
    ? "Disable first: an auto starts as the robot is enabled in autonomous."
    : "Sets the mode to autonomous, then enables the robot, which runs " + device.state
      + ". Esc disables.";
}
