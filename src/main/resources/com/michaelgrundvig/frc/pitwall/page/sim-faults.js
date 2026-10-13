// What fails in the simulated robot: each part that can fail, each of its faults with what it does
// and what undoing it does, as the robot's simulation declares them; and the scenarios, named sets
// of those faults. The robot's code is never told: it finds out from its sensors, as on a robot.
//
// A lasting fault (a camera unplugged) shows as on until it's undone; an event (a hit) happens
// once, so it's only a button. One row draws a fault wherever it is listed, and there are three
// such places: the Sim view, a card for each part; the Sim panel, one short list with the faults
// on now and those used lately first; and the World view, the faults of the part that was clicked.
// Rows are built once, and again only when the parts change, so a value being typed is kept.

import { send, shown, h, replace, typing } from "./core.js";
import { state, isPaused, canAct, uid } from "./sim-state.js";
import { note, outcome } from "./sim-outcome.js";

/** Where this browser keeps the faults used lately, the latest first, as "part\nfault". */
const RECENT_KEY = "pitwall.sim.recentFaults";
const RECENT_KEPT = 12;

/** How many faults the short list shows before it is asked for the rest. */
const SHORT_LIST = 8;

function recent() {
  try {
    const kept = JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
    return Array.isArray(kept) ? kept.filter((each) => typeof each === "string") : [];
  } catch (error) {
    return [];
  }
}

function used(key) {
  const kept = [key, ...recent().filter((each) => each !== key)].slice(0, RECENT_KEPT);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(kept));
  } catch (error) {
    // Kept for this page only: storage is off in this browser.
  }
}

/** What says when a list of faults must be built again: the parts and what each fault takes. */
function catalogKey(components) {
  return JSON.stringify(Object.entries(components).map(([name, part]) => [name,
    part.kind, Object.entries(part.faults).map(([fault, { does, lasting, undo, parameters,
      point }]) => [fault, does, lasting, undo, parameters, point])]));
}

// ---- One fault's row ----

/**
 * A fault's row: its name, what it does, its numbers, Apply and Undo, and what became of the last
 * press.
 *
 * @param named how the row is titled: the fault's name alone under its part's heading, or with
 *     its part's name where faults of many parts are listed together
 * @param compact without the line saying what undoing does, which the Undo button's tip has
 * @param pick for a fault two of whose numbers are a place on the field ({@code fault.point}):
 *     called with the row when its "where I click" button is pressed, by a view that can take
 *     the place from a click on its drawing of the field
 */
function makeFault(component, kind, name, fault,
  { named = name, compact = false, pick = null } = {}) {
  const parts = { component, name, lasting: fault.lasting, params: [], busy: false, on: false,
                  point: Array.isArray(fault.point) ? fault.point : null,
                  key: component + "\n" + name,
                  words: (component + " " + kind + " " + name + " " + fault.does).toLowerCase() };
  const label = component + ", " + name;
  for (const parameter of fault.parameters) {
    const range = shown(parameter.min) + " to " + shown(parameter.max);
    const input = h("input", { type: "text", inputmode: "decimal", autocomplete: "off",
      spellcheck: "false", class: "pval", value: shown(parameter.initial),
      "aria-label": label + ": " + parameter.name + (parameter.unit ? " in " + parameter.unit : "")
          + ", " + range });
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        applyFault(parts);
      }
    });
    parts.params.push({ ...parameter, input, range });
  }
  parts.tag = h("span", { class: "tag on", hidden: true }, "● on");
  parts.values = h("p", { class: "fvalues", hidden: true });
  parts.apply = h("button", { type: "button", "aria-label": "Apply " + label,
                              onclick: () => applyFault(parts) }, "Apply");
  parts.undo = h("button", { type: "button", hidden: true, "aria-label": "Undo " + label,
                             title: "Undo: " + fault.undo,
                             onclick: () => undoFault(parts) }, "Undo");
  parts.message = h("p", { class: "message", hidden: true });
  parts.pick = pick && parts.point ? h("button", { type: "button", "aria-pressed": "false",
    "aria-label": "Apply " + label + " where I click on the field",
    onclick: () => pick(parts) }, "Apply where I click") : null;
  parts.el = h("div", { class: "fault", role: "group", "aria-label": label },
    h("div", { class: "fhead" }, h("b", null, named), parts.tag,
      !fault.lasting && h("span", { class: "tag plain" }, "↯ happens once")),
    h("p", { class: "does" }, fault.does),
    parts.values,
    parts.params.length > 0 && h("div", { class: "params" }, parts.params.map((parameter) =>
      h("label", { class: "param" }, h("span", null, parameter.name), parameter.input,
        parameter.unit && h("span", { class: "unit" }, parameter.unit),
        h("span", { class: "lim" }, parameter.range)))),
    h("div", { class: "acts" }, parts.apply, parts.pick, parts.undo,
      !compact && h("span", { class: "meta" },
        (fault.lasting ? "Undo: " : "Afterwards: ") + fault.undo)),
    parts.message);
  return parts;
}

function updateFault(parts, fault, locked) {
  parts.on = Boolean(fault.lasting && fault.on);
  parts.el.classList.toggle("on", parts.on);
  parts.tag.hidden = !parts.on;
  const values = parts.on ? parts.params.filter((parameter) => parameter.name in fault.values) : [];
  parts.values.hidden = values.length === 0;
  parts.values.textContent = "On with " + values.map((parameter) => parameter.name + " "
      + shown(fault.values[parameter.name]) + (parameter.unit ? " " + parameter.unit : ""))
    .join(", ");
  parts.locked = locked;
  updateButtons(parts);
}

function updateButtons(parts) {
  parts.undo.hidden = !parts.on;
  // On, a fault with nothing to change has nothing more to apply.
  parts.apply.hidden = parts.on && parts.params.length === 0;
  parts.apply.disabled = parts.undo.disabled = Boolean(parts.locked) || parts.busy;
  if (parts.pick) parts.pick.disabled = parts.apply.disabled;
}

/**
 * Applies a fault at a place on the field: its two numbers that are the place are set to it, and
 * the fault is applied with those and the rest as typed.
 *
 * @param at {x, y} in meters, as the fault's numbers are
 */
export function applyAt(parts, at) {
  if (!parts.point) return Promise.resolve(false);
  for (const [name, value] of [[parts.point[0], at.x], [parts.point[1], at.y]]) {
    const parameter = parts.params.find((each) => each.name === name);
    if (parameter) parameter.input.value = String(Math.round(value * 100) / 100);
  }
  return applyFault(parts);
}

/** Applies a fault with the values typed, each checked against its limits here first. */
function applyFault(parts) {
  const values = {};
  for (const parameter of parts.params) {
    const text = parameter.input.value.trim();
    const number = Number(text);
    if (text === "" || !Number.isFinite(number)) {
      note(parts.message, "✗ " + parameter.name + " takes a number.", true);
      parameter.input.focus();
      return Promise.resolve(false);
    }
    if (number < parameter.min || number > parameter.max) {
      note(parts.message, "✗ " + parameter.name + " is from " + parameter.range
          + (parameter.unit ? " " + parameter.unit : "") + ". Checked here, before sending.", true);
      parameter.input.focus();
      return Promise.resolve(false);
    }
    values[parameter.name] = number;
  }
  return act(parts, { component: parts.component, fault: parts.name, values },
    parts.component + ": " + parts.name + (parts.lasting ? ", on." : ", done."));
}

function undoFault(parts) {
  return act(parts, { component: parts.component, fault: parts.name, undo: true },
    parts.component + ": " + parts.name + ", undone.");
}

/**
 * Applies or undoes a fault, saying what became of it under its row. An event says, for a while,
 * that it happened, since nothing else on the page changes.
 */
async function act(parts, body, done) {
  if (parts.locked || parts.busy) return false;
  parts.busy = true;
  updateButtons(parts);
  const result = await send("POST", "/api/simulator/faults", body);
  parts.busy = false;
  updateButtons(parts);
  if (result.ok && !body.undo) used(parts.key);
  return outcome(parts.message, result,
    { done, shown: !parts.lasting && !body.undo, paused: isPaused() });
}

/** A box that narrows a list of faults to those whose part, name or description has its words. */
function makeFilter(changed) {
  const input = h("input", { type: "search", class: "ffilter", autocomplete: "off",
                             spellcheck: "false", placeholder: "Filter: camera, slip, gyro…",
                             "aria-label": "Filter the faults by part or by name" });
  input.addEventListener("input", changed);
  return {
    el: input,
    words: () => input.value.toLowerCase().split(/\s+/).filter(Boolean),
  };
}

function matches(parts, words) {
  return words.every((word) => parts.words.includes(word));
}

// ---- The whole of what fails: the Sim view's cards, or the Sim panel's short list ----

/**
 * What fails: what's on now with Undo all, a filter, the faults, and the scenarios.
 *
 * @param options {compact}: one short list, the faults on now and those used lately first, for
 *     the Sim panel; otherwise a card for each part, for the Sim view
 * @return {element, render(what)}
 */
export function makeFaults({ compact = false } = {}) {
  const rows = new Map(); // "part\nfault" -> its row's parts
  const cardParts = new Map(); // part -> its card's parts
  const scenarioRows = new Map(); // scenario -> its row's parts
  let built = "";
  let builtScenarios = "";
  let undoing = false; // Undo all is waiting for its answer
  let all = false; // the short list shows every fault
  const headingId = uid("sim-faults");
  const onNow = h("p", { class: "onnow" });
  const undoAllButton = h("button", { type: "button", onclick: undoAll }, "Undo all");
  const undoAllMessage = h("p", { class: "message", hidden: true });
  const filter = makeFilter(() => arrange());
  const list = h("div", { class: compact ? "flist" : "fgrid" });
  const none = h("p", { class: "muted", hidden: true }, "No fault matches.");
  const more = h("button", { type: "button", class: "small", hidden: true, onclick: () => {
    all = !all;
    arrange();
  } });
  const scenarioList = h("div", { class: "scens" });
  const scenarios = h("details", {
    class: compact ? "simmore" : "card fcard",
    // Open on a laptop, where they sit side by side; in the panel or on a phone, a long list.
    open: !compact && !matchMedia("(max-width: 719px)").matches,
  },
    h("summary", null, h("span", { class: "fcname" }, "Scenarios")),
    h("p", { class: "meta" }, "Named sets of faults, as ./gradlew run -Pscenario starts in, "
        + "applied here at once to the running robot. What lasts shows as on, and is undone one "
        + "at a time or all together."),
    scenarioList);
  const element = h("section", { class: "faults" + (compact ? " simsec" : ""),
                                 "aria-labelledby": headingId },
    h(compact ? "h3" : "h2", { class: compact ? null : "mech-h", id: headingId }, "What fails"),
    !compact && h("p", { class: "lede" }, "Fail the simulated robot's parts while it runs. Its "
        + "code isn't told: it finds out from its sensors, as on a robot. Pit's status shows what "
        + "it makes of it."),
    h("div", { class: compact ? "onbar" : "card onbar" },
      h("div", { class: "acts" }, onNow, undoAllButton), undoAllMessage),
    compact ? null : scenarios,
    h("div", { class: "ffind" }, filter.el, more),
    list, none,
    compact ? scenarios : null);

  function components() {
    const now = state();
    return (now && now.components) || {};
  }

  function build(parts) {
    rows.clear();
    cardParts.clear();
    if (compact) {
      for (const [name, part] of Object.entries(parts)) {
        for (const [fault, described] of Object.entries(part.faults)) {
          const row = makeFault(name, part.kind, fault, described,
            { named: name + ": " + fault, compact });
          rows.set(row.key, row);
        }
      }
      return;
    }
    list.replaceChildren(...Object.entries(parts).map(([name, part]) => {
      const count = h("span", { class: "tag on", hidden: true });
      const anyOn = Object.values(part.faults).some((fault) => fault.lasting && fault.on);
      // The robot's own card is open: shoving, hitting, and placing it are the most used.
      const card = h("details", { class: "card fcard", open: anyOn || part.kind === "robot" },
        h("summary", null, h("span", { class: "fcname" }, name),
          h("span", { class: "meta" }, part.kind), count),
        Object.entries(part.faults).map(([fault, described]) => {
          const row = makeFault(name, part.kind, fault, described);
          rows.set(row.key, row);
          return row.el;
        }));
      cardParts.set(name, { count, card, wasOpen: card.open });
      return card;
    }));
  }

  /**
   * Puts the faults in order and hides those the filter leaves out. The short list has those on
   * now first, then those used lately, then the rest as declared, and shows a few until asked for
   * all or filtered. It is put in order when it is built, shown, or filtered, never while it is
   * being watched: a row doesn't move from under the pointer.
   */
  function arrange() {
    const words = filter.words();
    let showing = 0;
    if (compact) {
      const lately = recent();
      const rank = (row) => {
        const at = lately.indexOf(row.key);
        return row.on ? -1 : at === -1 ? lately.length : at;
      };
      const ordered = [...rows.values()].map((row, index) => ({ row, index }))
        .sort((a, b) => rank(a.row) - rank(b.row) || a.index - b.index).map(({ row }) => row);
      const matching = ordered.filter((row) => matches(row, words));
      const short = words.length === 0 && !all;
      const few = matching.filter((row, index) => index < SHORT_LIST || row.on);
      const shownRows = short ? few : matching;
      // Only the rows shown are in the list, so the keyboard walks no hidden ones.
      const now = [...list.children];
      if (now.length !== shownRows.length || shownRows.some((row, i) => now[i] !== row.el)) {
        list.replaceChildren(...shownRows.map((row) => row.el));
      }
      showing = shownRows.length;
      more.hidden = words.length > 0 || matching.length <= few.length;
      more.textContent = all ? "Show fewer" : "Show all " + matching.length;
    } else {
      for (const row of rows.values()) row.el.hidden = !matches(row, words);
      for (const [name, card] of cardParts) {
        const any = [...rows.values()].some((row) => row.component === name && !row.el.hidden);
        card.card.hidden = !any;
        // Filtered, a card with a match is held open; unfiltered, it is as it was left.
        if (words.length > 0) card.card.open = true;
        else if (card.filtered) card.card.open = card.wasOpen;
        else card.wasOpen = card.card.open;
        card.filtered = words.length > 0;
        if (any) showing++;
      }
    }
    none.hidden = showing > 0 || rows.size === 0;
  }

  function renderFaults(opened) {
    const parts = components();
    const locked = !canAct();
    const key = catalogKey(parts);
    const rebuilt = key !== built;
    if (rebuilt) {
      built = key;
      build(parts);
    }
    const on = [];
    for (const [name, part] of Object.entries(parts)) {
      let count = 0;
      for (const [fault, described] of Object.entries(part.faults)) {
        const row = rows.get(name + "\n" + fault);
        if (row) updateFault(row, described, locked);
        if (described.lasting && described.on) {
          count++;
          on.push(name + ": " + fault);
        }
      }
      const card = cardParts.get(name);
      if (card) {
        card.count.hidden = count === 0;
        card.count.textContent = "● " + count + " on";
      }
    }
    replace(onNow, on.length === 0
      ? h("span", { class: "muted" }, "Nothing on: the robot as built.")
      : [h("b", { class: "onword" }, "● " + on.length + " on: "), on.join(" · ")]);
    undoAllButton.disabled = locked || undoing || on.length === 0;
    undoAllButton.textContent = on.length > 0 ? "Undo all " + on.length : "Undo all";
    // While a value is being typed in a row, the list stays as it is.
    if (rebuilt || (opened && !(typing(document.activeElement)
        && list.contains(document.activeElement)))) {
      arrange();
    }
  }

  async function undoAll() {
    if (!canAct() || undoing) return;
    undoing = true;
    undoAllButton.disabled = true;
    try {
      const result = await send("DELETE", "/api/simulator/faults");
      outcome(undoAllMessage, result,
        { done: "Every fault undone: the robot as built.", paused: isPaused() });
    } finally {
      undoing = false;
      renderFaults(false); // Undo all's count, and whether there is any
    }
  }

  // ---- Scenarios ----

  function renderScenarios() {
    const now = state();
    const all = (now && now.scenarios) || {};
    const parts = components();
    const locked = !canAct();
    const key = JSON.stringify(all);
    if (key !== builtScenarios) {
      builtScenarios = key;
      scenarioRows.clear();
      const names = Object.keys(all);
      scenarioList.replaceChildren(...names.map((name) => makeScenario(name, all[name])));
      if (names.length === 0) {
        scenarioList.append(h("p", { class: "muted" }, "This robot's simulation declares none."));
      }
    }
    for (const [name, row] of scenarioRows) {
      // Its faults, those it put on marked as on while they are.
      replace(row.list, all[name].faults.map((setting, index) => {
        const on = isOn(setting, parts);
        return [index > 0 ? " · " : "", h("span", { class: on ? "son" : null },
          on ? "● " : "", settingText(setting, parts), on ? ": on" : "")];
      }));
      row.el.classList.toggle("on", all[name].faults.some((s) => isOn(s, parts)));
      row.locked = locked;
      row.apply.disabled = locked || row.busy;
    }
  }

  function makeScenario(name, scenario) {
    const row = { busy: false, locked: false, list: h("p", { class: "sfaults" }),
                  message: h("p", { class: "message", hidden: true }) };
    row.apply = h("button", { type: "button", "aria-label": "Apply " + name,
                              onclick: () => applyScenario(name, row) }, "Apply");
    row.el = h("div", { class: "scen", role: "group", "aria-label": name },
      h("div", { class: "fhead" }, h("b", { class: "mono grow" }, name), row.apply),
      h("p", { class: "does" }, scenario.description), row.list, row.message);
    scenarioRows.set(name, row);
    return row.el;
  }

  async function applyScenario(name, row) {
    if (row.locked || row.busy) return;
    row.busy = true;
    row.apply.disabled = true;
    const result = await send("POST", "/api/simulator/scenarios", { apply: name });
    row.busy = false;
    row.apply.disabled = row.locked;
    // What it did, as the answer says: which faults it put on, which happened, and which it passed
    // over, the part being that way as built. Kept under it, beside the faults it marks as on.
    const now = state();
    const scenario = now && now.scenarios ? now.scenarios[name] : null;
    const said = result.ok && result.status === 200 && scenario ? didWhat(scenario, result.body)
      : "";
    const done = name + " applied" + (said ? ": " + said : ".");
    if (outcome(row.message, result, { done, paused: isPaused() })) {
      note(row.message, "✓ " + done);
    }
  }

  return {
    element,
    render(what) {
      if (!what.faults) return;
      renderFaults(Boolean(what.opened));
      renderScenarios();
    },
  };
}

// ---- The faults of some parts only: what the World view shows of the part clicked ----

/**
 * The faults of the parts named, applied and undone where they are shown.
 *
 * @param names the simulator's parts, by name; those it doesn't have are left out
 * @param pick what a fault's "Apply where I click" button calls, with its row: see makeFault
 * @return {element, render(), any()}: {@code any()} says whether any of them has a fault to show
 */
export function makeFaultsOf(names, pick = null) {
  const rows = new Map();
  let built = "";
  const element = h("div", { class: "faultsof" });

  function mine() {
    const now = state();
    const parts = (now && now.components) || {};
    return Object.fromEntries(names.filter((name) => parts[name])
      .map((name) => [name, parts[name]]));
  }

  return {
    element,
    any: () => Object.keys(mine()).length > 0,
    render() {
      const parts = mine();
      const locked = !canAct();
      const key = catalogKey(parts);
      if (key !== built) {
        built = key;
        rows.clear();
        const several = Object.keys(parts).length > 1;
        element.replaceChildren(...Object.entries(parts).flatMap(([name, part]) => [
          several && h("h4", { class: "fpart" }, name),
          ...Object.entries(part.faults).map(([fault, described]) => {
            const row = makeFault(name, part.kind, fault, described, { compact: true, pick });
            rows.set(row.key, row);
            return row.el;
          })]).filter(Boolean));
      }
      for (const [name, part] of Object.entries(parts)) {
        for (const [fault, described] of Object.entries(part.faults)) {
          const row = rows.get(name + "\n" + fault);
          if (row) updateFault(row, described, locked);
        }
      }
    },
  };
}

// ---- Scenarios' words ----

function didWhat(scenario, components) {
  const on = [];
  const happened = [];
  const passed = [];
  for (const setting of scenario.faults) {
    const fault = faultOf(setting, components || {});
    if (!fault) continue;
    const name = setting.component + ": " + setting.fault;
    if (!fault.lasting) happened.push(name);
    else if (fault.on) on.push(name);
    else passed.push(name);
  }
  return [on.length > 0 && "on now, " + on.join(", "),
    happened.length > 0 && "happened, " + happened.join(", "),
    passed.length > 0 && "passed over, as built, " + passed.join(", ")]
    .filter(Boolean).join("; ");
}

/**
 * A scenario's fault as people read it, its values with their units, those at zero left out:
 * "Camera front-left: Moved on its mount, Up -4 in".
 */
function settingText(setting, components) {
  const fault = faultOf(setting, components);
  const units = new Map((fault ? fault.parameters : []).map((p) => [p.name, p.unit]));
  const given = Object.entries(setting.values || {});
  const values = given.filter(([, value]) => value !== 0 || given.length === 1)
    .map(([name, value]) => name + " " + shown(value) + (units.get(name) ? " " + units.get(name)
      : ""));
  return setting.component + ": " + setting.fault + (values.length > 0 ? ", " + values.join(", ")
    : "");
}

function faultOf(setting, components) {
  return ((components[setting.component] || {}).faults || {})[setting.fault];
}

function isOn(setting, components) {
  const fault = faultOf(setting, components);
  return Boolean(fault && fault.lasting && fault.on);
}
