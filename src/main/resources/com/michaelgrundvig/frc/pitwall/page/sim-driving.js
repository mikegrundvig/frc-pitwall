// Driving the simulated robot from this page: the keyboard's switch and its keys, a gamepad's
// switch and which joystick it drives, and each joystick as the page drives it. A section of the
// simulator's controls, mounted in the Sim view and, compact, in the Sim panel; sim-drive.js does
// the driving, on whatever view is showing, and the state bar says so on every view (bar.js).

import { robot, h, replace } from "./core.js";
import { sim, canAct } from "./sim-state.js";
import * as drive from "./sim-drive.js";
import { segmented, toggle, setToggle, block } from "./sim-parts.js";

/**
 * The driving section.
 *
 * @param options {compact}: the keys' list and the joysticks' axes folded away
 * @return {element, render(what)}
 */
export function makeDriving({ compact = false } = {}) {
  const ui = { drawn: "" };
  ui.keys = toggle("Drive with the keyboard",
    () => drive.setKeyboard(!drive.status().keyboard.on));
  ui.keyState = h("p", { class: "state" });
  ui.pad = toggle("Drive with a gamepad", () => drive.setGamepad(!drive.status().gamepad.on));
  ui.padState = h("div", { class: "state" });
  ui.slot = segmented("The first gamepad drives", [[0, "Joystick 0 · driver"],
    [1, "Joystick 1 · operator"]], (slot) => drive.setSlot(slot));
  ui.hiddenNote = h("div", { class: "banner warn", hidden: true },
    h("span", { class: "grow" }, "While this tab was hidden, it didn't drive: its joysticks were "
        + "centered. To drive while you watch AdvantageScope, put the two windows side by side, "
        + "not one over the other."),
    h("button", { type: "button", class: "small", onclick: drive.sawMissed }, "OK"));
  ui.message = h("p", { class: "message bad", hidden: true });
  ui.joysticks = [makeJoystick(0, compact), makeJoystick(1, compact)];
  const keymap = [
    h("table", { class: "keymap" }, h("tbody", null, drive.LAYOUT.map(([keys, does]) =>
      h("tr", null, h("td", null, keys.map((key, i) => [i > 0 ? " " : "", h("kbd", null, key)])),
        h("td", null, does))))),
    h("p", { class: "meta" }, "Keys type as usual in a field, and drive again outside it."),
  ];
  const joysticks = ui.joysticks.map((joystick) => joystick.el);
  const element = block("Drive", compact,
    ui.hiddenNote,
    h("div", { class: "simrow" }, ui.keys.el), ui.keyState,
    compact ? h("details", { class: "simmore" }, h("summary", null, "The keys"), keymap) : keymap,
    h("div", { class: "simrow" }, ui.pad.el), ui.padState,
    h("div", { class: "simrow" }, h("span", { class: "label" }, "The first gamepad drives"),
      ui.slot.el),
    h("p", { class: "meta" }, "A second gamepad drives the other joystick."),
    ui.message,
    compact ? h("details", { class: "simmore" }, h("summary", null, "The joysticks, as driven"),
      joysticks) : joysticks);
  return {
    element,
    render(what) {
      if (what.drive) render(ui);
    },
  };
}

function makeJoystick(port, compact) {
  const parts = { axes: [], buttonsShown: "" };
  parts.source = h("span", { class: "meta" });
  parts.flag = h("p", { class: "meta" });
  parts.pressed = h("p", { class: "pressed" });
  parts.axesEl = h("div", { class: "axes" }, drive.AXIS_NAMES.map((name) => {
    const fill = h("span", { class: "fill" });
    const value = h("span", { class: "num v" }, "0.00");
    parts.axes.push({ fill, value, last: null });
    return h("div", { class: "axis" }, h("span", { class: "an" }, name),
      h("span", { class: "track", "aria-hidden": "true" }, fill), value);
  }));
  parts.el = h("div", { class: "joy", role: "group", "aria-label": "Joystick " + port },
    h(compact ? "h4" : "h3", null, "Joystick " + port + (port === 0 ? " · the driver's" : " · the operator's"),
      " ", parts.source),
    parts.flag, parts.axesEl, parts.pressed);
  return parts;
}

/**
 * The driving section. A gamepad changes it every frame, but only its joysticks' axes and buttons:
 * the rest is drawn only when it changed.
 */
function render(ui) {
  const status = drive.status();
  const key = JSON.stringify([status.keyboard, status.gamepad, status.hidden, status.problem,
    status.missed]);
  if (key !== ui.drawn) {
    ui.drawn = key;
    setToggle(ui.keys, status.keyboard.on);
    ui.keyState.className = "state"
        + (status.keyboard.on && status.keyboard.paused ? " paused" : "");
    ui.keyState.textContent = keyText(status);
    setToggle(ui.pad, status.gamepad.on);
    replace(ui.padState, padText(status));
    for (const [slot, button] of ui.slot.buttons) {
      button.setAttribute("aria-pressed", String(status.gamepad.slot === slot));
    }
    ui.hiddenNote.hidden = status.hidden || !status.missed
        || !(status.keyboard.on || status.gamepad.on);
    ui.message.hidden = !status.problem;
    ui.message.textContent = status.problem ? "✗ " + status.problem : "";
  }
  renderJoysticks(ui, status);
}

function keyText(status) {
  if (!status.keyboard.on) return "Off: keys type as usual.";
  switch (status.keyboard.paused) {
    case "hidden": return "Not driving: this tab is hidden.";
    case "window": return "Paused: this window doesn't have the keyboard. Click the page to drive.";
    case "field": return "Paused: the focus is in a field, so keys type there. Click outside it "
        + "to drive.";
    default: return "Driving joystick 0 from any view: hold the keys. Esc disables the robot.";
  }
}

function padText(status) {
  if (!status.gamepad.on) return "Off.";
  if (status.hidden) {
    return "Not driving: this tab is hidden, and a hidden tab can't read a gamepad. Put Pitwall "
        + "beside AdvantageScope, not behind it.";
  }
  if (status.gamepad.pads.length === 0) {
    return "No gamepad yet: press a button on it. A browser shows a page its gamepads only after "
        + "a button press.";
  }
  return h("ul", { class: "pads" }, status.gamepad.pads.map((pad) => h("li", null,
    h("span", { class: "mono" }, pad.id.length > 48 ? pad.id.slice(0, 47) + "…" : pad.id), ": ",
    pad.drives !== null ? "drives joystick " + pad.drives
      : pad.standard ? "not driving: two gamepads drive at most"
      : h("span", { class: "bad" }, "✗ this browser doesn't know its layout (no standard "
          + "mapping), so it can't drive. Chrome knows most gamepads."))));
}

/**
 * Each joystick as this page drives it: its axes and buttons, and whether the robot centered it.
 */
function renderJoysticks(ui, status) {
  const now = sim();
  for (const [port, parts] of ui.joysticks.entries()) {
    const input = status.inputs[port];
    const fromServer = now && now.joysticks ? now.joysticks[String(port)] : null;
    // Another page's input was the latest the robot took: two pages drive this joystick.
    const another = Boolean(canAct() && fromServer && fromServer.fromPage && fromServer.stream
        && fromServer.stream !== robot.stream);
    const sources = [port === 0 && status.keyboard.on && "the keyboard",
      status.gamepad.on && status.gamepad.pads.some((pad) => pad.drives === port)
        && "a gamepad"].filter(Boolean);
    const source = input ? "· from " + sources.join(" and ") : "";
    if (parts.source.textContent !== source) parts.source.textContent = source;
    parts.axesEl.hidden = !input;
    parts.pressed.hidden = !input;
    parts.el.classList.toggle("idle", !input);
    let flag;
    let bad = false;
    if (input && another) {
      bad = true;
      flag = "✗ Another Pitwall page drives this joystick too: the robot takes whichever input "
          + "came last. Switch driving off in one of them.";
    } else if (input && fromServer && fromServer.centeredByDeadman) {
      bad = true;
      flag = "✗ Centered by the robot: it heard nothing from this page for half a second (the "
          + "deadman). It drives again with the next input.";
    } else if (input) {
      flag = "";
    } else if (another) {
      flag = "Driven from another Pitwall page.";
    } else {
      flag = "Not driven from this page: a Driver Station or WPILib's simulator window may drive "
          + "it.";
    }
    if (parts.flag.textContent !== flag) {
      parts.flag.className = bad ? "message bad" : "meta";
      parts.flag.textContent = flag;
    }
    if (!input) continue;
    input.axes.forEach((value, axis) => {
      const shownAxis = parts.axes[axis];
      if (!shownAxis || shownAxis.last === value) return;
      shownAxis.last = value;
      // Drawn from the middle: left for negative, right for positive.
      shownAxis.fill.style.left = (50 + Math.min(0, value) * 50) + "%";
      shownAxis.fill.style.width = (Math.abs(value) * 50) + "%";
      shownAxis.value.textContent = value.toFixed(2);
    });
    const pressed = input.buttons.map((down, button) => (down ? button : -1))
      .filter((button) => button >= 0)
      .map((button) => (drive.BUTTON_NAMES[button] || "Button") + " (" + button + ")");
    const text = pressed.length === 0 ? "No buttons pressed." : "Pressed: " + pressed.join(", ");
    if (text !== parts.buttonsShown) {
      parts.buttonsShown = text;
      parts.pressed.textContent = text;
    }
  }
}
