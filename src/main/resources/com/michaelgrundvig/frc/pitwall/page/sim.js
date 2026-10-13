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
// (sim-enable.js, sim-station.js, sim-auto.js, sim-time.js, sim-driving.js, sim-faults.js),
// mounted here in full and, compact, in the Sim panel beside any other view (sim-panel.js): one of
// each, never a copy. What they read and ask is sim-state.js.

import { h } from "./core.js";
import { isSimulator, keepDrawn } from "./sim-state.js";
import { makeEnable } from "./sim-enable.js";
import { makeStation } from "./sim-station.js";
import { makeTime } from "./sim-time.js";
import { makeDriving } from "./sim-driving.js";
import { makeFaults } from "./sim-faults.js";
import { makeAuto } from "./sim-auto.js";

let drawn = null;

function mount(view) {
  const enabler = makeEnable();
  const station = makeStation();
  const time = makeTime();
  const drive = makeDriving();
  const auto = makeAuto();
  const faults = makeFaults();
  view.append(
    h("h1", { class: "view-title" }, "Sim"),
    h("p", { class: "lede" }, "Drive the simulator, change its Driver Station and its clock, and "
        + "fail its parts. WPILib's simulator window isn't needed: ./gradlew run -Pgui opens it, "
        + "and while it's open it overwrites these joysticks every frame."),
    enabler.element,
    enabler.notice,
    h("div", { class: "sim" },
      h("div", null, station.element, auto.element, time.element),
      h("div", null, drive.element)),
    faults.element);
  drawn = keepDrawn([enabler, station, auto, time, drive, faults]);
}

function show() {
  drawn.show(true);
}

function hide() {
  drawn.show(false);
}

export default { id: "sim", title: "Sim", simulatorOnly: true, mount, show, hide,
                 offered: isSimulator };
