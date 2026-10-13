// The simulated Driver Station: the mode and the alliance station, which are changed most while
// watching, then whether a Driver Station and the field system are attached, and the match time.
// A section of the simulator's controls, mounted in the Sim view and, compact, in the Sim panel.

import { robot, h, confirm, shown } from "./core.js";
import { sim, canAct, patch, MODES, STATIONS, clock } from "./sim-state.js";
import { note } from "./sim-outcome.js";
import { segmented, toggle, setToggle, block } from "./sim-parts.js";

/**
 * The longest match time the simulator takes, in seconds: room for any match period. The server's
 * is PitwallServer's MAX_MATCH_SECONDS, which decides; this only checks before sending.
 */
const MAX_MATCH_SECONDS = 600;

/**
 * The Driver Station's section.
 *
 * @param options {compact}: the mode and the station first, the rest folded under them
 * @return {element, render(what)}
 */
export function makeStation({ compact = false } = {}) {
  const ui = {};
  ui.message = h("p", { class: "message", hidden: true });
  const ask = (body) => patch(body, ui.message);
  ui.modes = segmented("Mode", MODES, (mode) => ask({ mode }));
  ui.red = segmented("Red alliance", STATIONS.slice(0, 3).map((s) => [s, "Red " + s.slice(-1)]),
    (station) => ask({ allianceStation: station }));
  ui.blue = segmented("Blue alliance", STATIONS.slice(3).map((s) => [s, "Blue " + s.slice(-1)]),
    (station) => ask({ allianceStation: station }));
  ui.ds = toggle("Driver Station attached", () => ask({ dsAttached: !sim().dsAttached }));
  ui.fms = toggle("FMS attached", () => setField(!sim().fmsAttached, ask));
  ui.matchNow = h("b", { class: "num" });
  ui.matchHow = h("span", { class: "meta" });
  ui.matchInput = h("input", { type: "text", inputmode: "numeric", autocomplete: "off",
    class: "pval", placeholder: "seconds", "aria-label": "Match time to set, in seconds, 0 to "
        + MAX_MATCH_SECONDS });
  const setMatchTime = async () => {
    const text = ui.matchInput.value.trim();
    const seconds = Number(text);
    if (text === "" || !Number.isFinite(seconds) || seconds < 0 || seconds > MAX_MATCH_SECONDS) {
      note(ui.message, "✗ A match time is seconds from 0 to " + MAX_MATCH_SECONDS
          + ". Checked here, before sending.", true);
      return;
    }
    const result = await ask({ matchTime: seconds });
    if (result.ok) ui.matchInput.value = "";
  };
  ui.matchInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      setMatchTime();
    }
  });
  ui.matchSet = h("button", { type: "button", onclick: setMatchTime }, "Set");
  ui.matchClear = h("button", { type: "button", onclick: () => ask({ matchTime: null }) },
    "Clear");
  const first = [
    h("div", { class: "simrow" }, h("span", { class: "label" }, "Mode"), ui.modes.el),
    h("div", { class: "simrow" }, h("span", { class: "label" }, "Alliance station"),
      h("span", { class: "stations" }, ui.red.el, ui.blue.el)),
  ];
  const rest = [
    h("div", { class: "simrow" }, ui.ds.el),
    h("div", { class: "simrow" }, ui.fms.el,
      h("span", { class: "meta" }, "On the field, as at an event: tuning, actions, and experiments "
          + "lock, and tuned values go back to committed. Off here unlocks them.")),
    h("div", { class: "simrow" }, h("span", { class: "label" }, "Match time"), ui.matchNow,
      ui.matchHow),
    h("div", { class: "simrow" }, h("span", { class: "setter" }, ui.matchInput, ui.matchSet),
      ui.matchClear),
  ];
  ui.restSaid = h("span", { class: "meta" });
  const element = block("Driver Station", compact, first,
    compact ? h("details", { class: "simmore" },
      h("summary", null, "Attached, and match time ", ui.restSaid), rest) : rest,
    ui.message);
  return {
    element,
    render(what) {
      if (what.station) render(ui);
    },
  };
}

/** Attaching the field returns every tuned value to committed: asked first, listing them. */
async function setField(on, ask) {
  const doc = robot.doc;
  const tuned = on && doc ? Object.keys(doc.changes || {}) : [];
  if (tuned.length > 0) {
    const yes = await confirm("Attach the field system?", [
      h("p", null, "As at an event, tuning, actions, and experiments lock, and "
          + (tuned.length === 1 ? "this tuned value goes" : "these " + tuned.length
            + " tuned values go") + " back to committed:"),
      tunedList(doc, tuned),
      h("p", { class: "meta" }, "What was tuned is printed to the log, and this session's history "
          + "keeps it: Restore, in Changes, stages it again."),
    ], "Attach the field");
    if (!yes) return;
  }
  await ask({ fmsAttached: on });
}

/** Tuned values as a list: each by its configuration and path, now and committed. */
export function tunedList(doc, paths) {
  return h("ul", null, paths.map((path) => {
    const value = doc.tunables[path];
    return h("li", null, value ? value.config + " " + value.field + ": " + shown(doc.values[path])
        + " → " + shown(value.committed) : path);
  }));
}

function render(ui) {
  const now = sim();
  if (!now) return;
  const act = canAct();
  for (const [mode, button] of ui.modes.buttons) {
    button.setAttribute("aria-pressed", String(now.mode === mode));
    button.disabled = !act;
  }
  for (const seg of [ui.red, ui.blue]) {
    for (const [station, button] of seg.buttons) {
      button.setAttribute("aria-pressed", String(now.allianceStation === station));
      button.disabled = !act;
    }
  }
  for (const [part, on] of [[ui.ds, now.dsAttached], [ui.fms, now.fmsAttached]]) {
    setToggle(part, on);
    part.el.disabled = !act;
  }
  ui.matchNow.textContent = now.matchTime >= 0 ? clock(now.matchTime) : "none";
  ui.matchHow.textContent = now.matchTime < 0 ? "set one to count it down while enabled"
    : now.matchTime === 0 ? "run out" : now.enabled ? "counting down"
    : "counts down while enabled";
  ui.matchSet.disabled = !act;
  ui.matchClear.disabled = !act || now.matchTime < 0;
  // Folded away, what isn't as a robot on a bench has it still shows beside the fold.
  ui.restSaid.textContent = [now.fmsAttached ? "· FMS attached" : null,
    !now.dsAttached ? "· no Driver Station" : null,
    now.matchTime >= 0 ? "· " + clock(now.matchTime) + " left" : null].filter(Boolean).join(" ");
}
