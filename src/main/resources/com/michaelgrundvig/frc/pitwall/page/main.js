// The page: one view at a time, chosen by the address's hash (#pit, #tune, ...), so a reload comes
// back to the same place and a link can be sent. Each view is its own module; this starts them
// and follows the robot.

import { robot, connect, onChange, byId, h } from "./core.js";
import { startKeys } from "./keys.js";
import { mountDock, offer } from "./side-dock.js";
import { addSimPanel } from "./sim-panel.js";
import { mountBar } from "./bar.js";
import { mountApplyBar, showIn } from "./applybar.js";
import pit from "./pit.js";
import tune from "./tune.js";
import experiment from "./experiment.js";
import changes from "./changes.js";
import sim from "./sim.js";
import world from "./world.js";
import robotView from "./robot.js";

const views = [pit, tune, experiment, world, robotView, changes, sim];
mountBar();
mountApplyBar();
mountDock();
addSimPanel();
const sections = new Map(); // view id -> its section
const tabs = new Map(); // view id -> its tab
let current = null;

for (const view of views) {
  const section = h("section", { id: "view-" + view.id, class: "view", hidden: true,
                                 "aria-label": view.title });
  byId("views").append(section);
  sections.set(view.id, section);
  const tab = h("a", { href: "#" + view.id, class: "tab", hidden: Boolean(view.simulatorOnly) },
    view.title);
  byId("tabs").append(tab);
  tabs.set(view.id, tab);
  view.mount(section);
}

/**
 * The view the address names, and the rest of the address after it (#tune/drivetrain/...): Pit for
 * an address that names no view offered here, or can't be read.
 */
function address() {
  let decoded;
  try {
    decoded = decodeURIComponent(location.hash.slice(1));
  } catch (malformed) {
    decoded = ""; // a stray "%" in a typed or pasted address
  }
  const [id, ...rest] = decoded.split("/");
  const known = views.find((view) => view.id === id && offered(view));
  return { view: known || pit, rest: known ? rest.join("/") : "" };
}

/**
 * Goes where the address says, as someone asked (a link, a tab, Back, a reload): the view shows,
 * and may focus what the rest of the address names. A snapshot never does this, so reconnecting
 * never moves anyone's focus.
 */
function route() {
  const { view, rest } = address();
  show(view);
  if (view.route) view.route(rest);
}

/**
 * Whether a view is offered: the simulator's only in the simulator. A view may say for itself
 * ({@code offered()}): Sim knows it's the simulator before any snapshot, when the page opened while
 * the simulator was paused.
 */
function offered(view) {
  if (view.offered) return view.offered();
  return !view.simulatorOnly || Boolean(robot.doc && robot.doc.robot.simulated);
}

/**
 * Offers the views offered now, and shows the one the address names if it's offered only now (or
 * no longer): after each snapshot, and when a view says what it offers changed. Nothing is focused.
 */
function reoffer() {
  for (const view of views) tabs.get(view.id).hidden = !offered(view);
  const { view } = address();
  if (view !== current) show(view);
}

function show(view) {
  if (current !== view) {
    if (current && current.hide) current.hide();
    for (const [id, section] of sections) section.hidden = id !== view.id;
    for (const [id, tab] of tabs) {
      if (id === view.id) tab.setAttribute("aria-current", "page");
      else tab.removeAttribute("aria-current");
    }
    current = view;
    if (view.show) view.show();
    showIn(view.id);
  }
}

const count = h("span", { class: "count", hidden: true });
tabs.get("changes").append(count);

onChange((patch, snapshot) => {
  if (snapshot || "changes" in patch) {
    const tuned = Object.keys(robot.doc.changes).length;
    count.hidden = tuned === 0;
    count.textContent = String(tuned);
    count.setAttribute("aria-label", tuned + " to commit");
  }
  // The address may name a view offered only now, such as #sim, or one no longer offered.
  if (snapshot || "robot" in patch) {
    reoffer();
    offer(); // the dock's panels too: the Sim panel is offered only in the simulator
  }
});
document.addEventListener("pitwall-offered", reoffer);

startKeys();

window.addEventListener("hashchange", route);
connect();
route();
