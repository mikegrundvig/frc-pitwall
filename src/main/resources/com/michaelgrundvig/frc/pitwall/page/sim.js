// Sim: the simulator's Driver Station, its joysticks, its clock, and what fails, in place of
// WPILib's simulator GUI. Shown only in the simulator: a robot program that isn't simulated never
// offers it, and has none of the routes it uses (docs/simulator.md).
//
// A view is a module whose default export is {id, title, mount(element), show(), hide(), route(),
// offered()}: mount builds it once, into its own section, and it then follows the robot through
// core.js's onChange; show and hide (optional) say when it's on screen; route (optional) takes the
// rest of the address, after "#sim/"; offered (optional) says whether the view is offered now,
// where simulatorOnly isn't enough to tell.
//
// This is the whole of the simulator's controls. Each of its sections is a module of its own
// (sim-enable.js, sim-station.js, sim-time.js, sim-driving.js, sim-faults.js), mounted here in
// full and, compact, in the Sim panel beside any other view (sim-panel.js): one of each, never a
// copy. What they read and ask is sim-state.js.

import { h, typing } from "./core.js";
import { isSimulator, isEnabled, disable, keepDrawn } from "./sim-state.js";
import { status as driving } from "./sim-drive.js";
import { makeEnable } from "./sim-enable.js";
import { makeStation } from "./sim-station.js";
import { makeTime } from "./sim-time.js";
import { makeDriving } from "./sim-driving.js";
import { makeFaults } from "./sim-faults.js";

let drawn = null;
let visible = false;

function mount(view) {
  const enabler = makeEnable();
  const station = makeStation();
  const time = makeTime();
  const drive = makeDriving();
  const faults = makeFaults();
  view.append(
    h("h1", { class: "view-title" }, "Sim"),
    h("p", { class: "lede" }, "Drive the simulator, change its Driver Station and its clock, and "
        + "fail its parts. WPILib's simulator window isn't needed: ./gradlew run -Pgui opens it, "
        + "and while it's open it overwrites these joysticks every frame."),
    enabler.element,
    enabler.notice,
    h("div", { class: "sim" },
      h("div", null, station.element, time.element),
      h("div", null, drive.element)),
    faults.element);
  drawn = keepDrawn([enabler, station, time, drive, faults]);
  document.addEventListener("keydown", escape);
}

function show() {
  visible = true;
  drawn.show(true);
}

function hide() {
  visible = false;
  drawn.show(false);
}

/** Esc disables: in Sim always, and anywhere while this page drives, outside a field. */
function escape(event) {
  if (event.key !== "Escape" || event.defaultPrevented || document.querySelector("dialog[open]")) {
    return;
  }
  if (!isEnabled()) return; // nothing to disable: Esc keeps its other meanings
  const drives = driving().inputs.some(Boolean) && !typing(event.target);
  if (!visible && !drives) return;
  event.preventDefault();
  disable();
}

export default { id: "sim", title: "Sim", simulatorOnly: true, mount, show, hide,
                 offered: isSimulator };
