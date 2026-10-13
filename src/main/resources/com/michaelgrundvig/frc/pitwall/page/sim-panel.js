// The Sim panel: the simulator's controls in the side dock (side-dock.js), beside whatever view is
// showing, so driving the robot and failing its parts never means leaving the World or the Robot
// view. In the simulator only.
//
// It mounts the very sections the Sim view does, compact, in the order they are used while
// watching: the mode and the alliance station, driving, the auto to run, the simulator's clock,
// and what fails. Enable and Disable are not here: they are in the state bar, above every view.
// The Sim view stays the whole of it (Stop, the scenarios side by side, a card for each part).

import { h } from "./core.js";
import { addPanel } from "./side-dock.js";
import { isSimulator, keepDrawn } from "./sim-state.js";
import { makeStation } from "./sim-station.js";
import { makeDriving } from "./sim-driving.js";
import { makeAuto } from "./sim-auto.js";
import { makeTime } from "./sim-time.js";
import { makeFaults } from "./sim-faults.js";

let drawn = null;

function mount(element) {
  const options = { compact: true };
  const parts = [makeStation(options), makeDriving(options), makeAuto(), makeTime(options),
    makeFaults(options)];
  element.append(
    ...parts.map((part) => part.element),
    h("p", { class: "meta simall" }, h("a", { href: "#sim" }, "All of Sim"),
      ": Stop, the joysticks in full, and a card for each part."));
  drawn = keepDrawn(parts);
}

/** Adds the Sim panel to the page's side dock. */
export function addSimPanel() {
  addPanel({
    id: "sim",
    title: "Sim controls",
    label: "Sim controls",
    offered: isSimulator,
    mount,
    show: () => drawn.show(true),
    hide: () => drawn.show(false),
  });
}
