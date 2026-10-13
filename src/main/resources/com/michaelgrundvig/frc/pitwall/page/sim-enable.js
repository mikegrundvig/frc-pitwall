// Enable and Disable, with the simulated robot's state beside them: the bar at the top of the Sim
// view, and, small, in the state bar on every view. One of them, mounted twice.
//
// Enable is only ever this button, pressed: no key enables, and nothing else on the page does it
// as a side effect. Pressed, it hands the focus to Disable. Disable is this button, and Esc from anywhere on the page (keys.js). Neither
// exists on a real robot: its Driver Station enables it, and the page only shows its mode.

import { robot, h, replace } from "./core.js";
import {
  sim, canAct, withoutSnapshot, enable, disable, modeName, stationName, capital, clock, uid,
} from "./sim-state.js";

/**
 * The simulated robot's state with Enable and Disable.
 *
 * @param options {small}: for the state bar, where the mode beside them already says the state:
 *     the two buttons only
 * @return {element, render(what)}, and for the bar {notice}: a banner to put under it, shown
 *     while the page has only a paused simulator's own answer
 */
export function makeEnable({ small = false } = {}) {
  const ui = {};
  const stateId = uid("sim-state");
  const whyId = uid("sim-enable-why");
  ui.message = small ? null : h("p", { class: "message", hidden: true });
  ui.enable = h("button", {
    type: "button", class: (small ? "" : "big ") + "primary enable",
    onclick: () => {
      // Enable never keeps the focus once it's pressed: a Space or an Enter meant for something
      // else must not enable the robot again after it's disabled. Disable takes it, where a stray
      // press is harmless.
      ui.disable.focus({ preventScroll: true });
      enable(ui.message);
    },
    "aria-describedby": small ? null : stateId + " " + whyId,
    title: small ? "Enable the simulated robot, in the Driver Station's mode" : null,
  }, "▶ Enable");
  ui.disable = h("button", {
    type: "button", class: (small ? "" : "big ") + "danger disable",
    onclick: () => disable(ui.message),
    "aria-describedby": small ? null : stateId, "aria-keyshortcuts": "Escape",
    title: small ? "Disable the simulated robot (Esc, from anywhere on the page)" : null,
  }, "■ Disable", h("kbd", null, "Esc"));
  if (small) {
    const element = h("span", { class: "enabler", role: "group",
                                "aria-label": "Enable or disable the simulated robot" },
      ui.enable, ui.disable);
    return { element, render: (what) => what.station && renderButtons(ui) };
  }
  ui.word = h("span", { class: "word" });
  ui.sub = h("span", { class: "sub" });
  ui.paused = h("span", { class: "tag paused", hidden: true }, "❚❚ PAUSED");
  ui.state = h("div", { class: "dsstate", id: stateId },
    h("span", { class: "line" }, ui.word, ui.paused), ui.sub);
  ui.enableWhy = h("p", { class: "gate locked", id: whyId, hidden: true });
  ui.notice = h("div", { class: "banner lock", hidden: true });
  const element = h("div", { class: "dsbar" }, ui.state,
    h("div", { class: "dsbuttons" }, ui.enable, ui.disable), ui.enableWhy, ui.message);
  return {
    element,
    notice: ui.notice,
    render(what) {
      if (!what.station) return;
      renderButtons(ui);
      renderState(ui);
    },
  };
}

/**
 * Enable needs a mode, as a Driver Station always has one; Disable is never locked but by a lost
 * link, and pressing it again changes nothing.
 */
function renderButtons(ui) {
  const now = sim();
  const act = canAct();
  const noMode = Boolean(now) && now.mode === "UNKNOWN";
  ui.enable.disabled = !act || !now || now.enabled || noMode;
  ui.disable.disabled = !act;
  ui.disable.classList.toggle("armed", Boolean(now && now.enabled));
  if (ui.enableWhy) {
    ui.enableWhy.hidden = !noMode || !act;
    ui.enableWhy.textContent = "🔒 Choose a mode to enable it in.";
  } else {
    ui.enable.title = noMode ? "Choose a mode to enable it in: in Sim controls"
      : "Enable the simulated robot, in the Driver Station's mode";
  }
}

function renderState(ui) {
  const now = sim();
  const stopped = robot.stopped && robot.connected !== true;
  ui.state.className = "dsstate" + (stopped ? " stopped" : now && now.enabled ? " enabled" : "");
  if (stopped) {
    ui.word.textContent = "■ STOPPED";
    ui.sub.textContent = "This page stopped the simulator. Start it again (./gradlew run), and "
        + "this page reconnects by itself.";
  } else if (!now) {
    ui.word.textContent = "…";
    ui.sub.textContent = "Waiting for the simulator.";
  } else {
    ui.word.textContent = now.enabled ? "● ENABLED" : "○ DISABLED";
    ui.sub.textContent = [capital(modeName(now.mode)), capital(stationName(now.allianceStation)),
      now.fmsAttached ? "on the field (FMS)" : null,
      !now.dsAttached ? "no Driver Station" : null,
      now.matchTime >= 0 ? clock(now.matchTime) + " left" : null].filter(Boolean).join(" · ");
  }
  ui.paused.hidden = !now || !now.paused || stopped;
  ui.notice.hidden = !withoutSnapshot();
  replace(ui.notice, h("span", { class: "grow" }, "❚❚ ",
    h("b", null, "The simulator is paused, "),
    h("span", { class: "why" }, "and this page opened while it was: the rest of the page fills in "
        + "when a loop runs. Step or Resume, under Simulator clock.")));
}
