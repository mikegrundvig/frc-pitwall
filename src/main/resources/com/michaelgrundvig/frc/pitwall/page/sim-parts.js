// Small pieces the simulator's sections share: a row of choices with one pressed, and a switch.

import { h } from "./core.js";

/** Buttons for one choice of several, the chosen one pressed: {el, buttons}. */
export function segmented(label, options, choose) {
  const buttons = new Map();
  const el = h("span", { class: "seg", role: "group", "aria-label": label },
    options.map(([value, text]) => {
      const button = h("button", { type: "button", "aria-pressed": "false",
                                   onclick: () => choose(value) }, text);
      buttons.set(value, button);
      return button;
    }));
  return { el, buttons };
}

/** A switch, on or off: {el, state}, its state written beside its label. */
export function toggle(label, change) {
  const state = h("span");
  const el = h("button", { type: "button", role: "switch", class: "switch", "aria-checked": "false",
                           "aria-label": label, onclick: change },
    h("span", { class: "knob" }), h("span", null, label), state);
  return { el, state };
}

/** Shows a switch as on or off. */
export function setToggle(part, on) {
  part.el.setAttribute("aria-checked", String(on));
  part.state.textContent = on ? " · on" : " · off";
}

/**
 * A section of the simulator's controls under its title: a card in the Sim view, and a plainer,
 * tighter block where the controls are compact (the Sim panel).
 */
export function block(title, compact, ...content) {
  return h("section", { class: compact ? "simsec" : "card", "aria-label": title },
    h(compact ? "h3" : "h2", null, title), ...content);
}
