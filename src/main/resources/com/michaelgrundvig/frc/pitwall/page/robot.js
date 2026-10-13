// Robot: the robot's joints as its model says them, live. One card for each joint, drawn by its
// kind (robot-draw.js), with the same facts under it in words: where it is, where it was told to
// go, who owns it, how its last move ended, what holds it, and how its own sensors are. Beside the
// joints: the robot's other sensors, its gates with why each does not hold, the two gamepads, and
// the bindings that just fired.
//
// Cause is shown across cards. An interlock on one joint whose condition is about another (the
// roller runs only when the pivot is at least 110°) is marked on the other joint's card, where its
// condition holds, and stands out while it is what the first joint is waiting for.
//
// Offered only when the robot program hands Pitwall its robot's session (Pitwall.start(name,
// session)). The robot sends the view only to pages that show it (core.js's showViews), at most 20
// times a second, and each time only what changed; hiding the view, or the tab, stops it. What
// arrives is the newest loop of the robot's log (robotView.live).
//
// It draws the moment the page's cursor is on (cursor.js): the newest loop while the cursor is
// live, and otherwise that loop's view, asked of the robot and kept (robot-moments.js). Every card
// is drawn from one loop's view alone, the same way whichever loop it is. The time strip along its
// bottom (timeline.js) moves the cursor, and the list of events over it (event-list.js) says what
// happened around the cursor, in order; a joint's name on its card shows that joint's events only.

import { robot, onChange, showViews, h, replace } from "./core.js";
import { cursor, onCursor, span, noteTime } from "./cursor.js";
import { wantView, forgetViews } from "./robot-moments.js";
import { makeTimeline } from "./timeline.js";
import { makeEventList } from "./event-list.js";
import { eventFilter, setEventFilter, onEvents } from "./session-events.js";
import { drawJoint, drawRange, builtFrom } from "./robot-draw.js";
import { AXIS_NAMES, BUTTON_NAMES } from "./sim-drive.js";

/** How many loops each side of the cursor's are fetched ahead: where a step goes next. */
const BESIDE = 3;

let visible = false;
let ids = 0;
let sessionOf = null; // when the session whose loops are kept began
const ui = {};
const cards = new Map(); // joint label -> its card's parts

function offer() {
  return (robot.doc && robot.doc.robotView) || {};
}

/** Offered once the robot program has handed Pitwall its robot's session. */
function offered() {
  return Boolean(offer().view);
}

function live() {
  const view = offer().live;
  return view && view.joints ? view : null;
}

function mount(view) {
  ui.view = view;
  ui.state = h("p", { class: "rv-state meta", "aria-live": "off" });
  ui.joints = h("div", { class: "rv-joints" });
  ui.waiting = h("p", { class: "rv-waiting" }, "Waiting for the robot's log.");
  ui.sensors = h("ul", { class: "rv-list" });
  ui.gates = h("ul", { class: "rv-list" });
  ui.pads = h("div", { class: "rv-pads" });
  ui.fired = h("ul", { class: "rv-list rv-fired", "aria-live": "polite" });
  view.classList.add("rv");
  view.append(
    h("div", { class: "rv-bar" }, h("h1", { class: "view-title" }, "Robot"), ui.state),
    ui.waiting,
    h("div", { class: "rv-main" },
      ui.joints,
      h("div", { class: "rv-side" },
        h("section", { class: "card", "aria-label": "Sensors" }, h("h2", null, "Sensors"),
          ui.sensors),
        h("section", { class: "card", "aria-label": "Gates" }, h("h2", null, "Gates"), ui.gates),
        h("section", { class: "card", "aria-label": "Gamepads" }, h("h2", null, "Gamepads"),
          ui.pads, h("h3", { class: "rv-sub" }, "Fired"), ui.fired))),
    h("div", { class: "dock" }, makeEventList().element, makeTimeline().element));
  onChange((patch, snapshot) => {
    if (!visible || !(snapshot || "robotView" in patch)) return;
    // A snapshot may be the first to say there is a Robot view: name it then, to be sent it.
    if (snapshot) showViews(offered() ? [offer().view] : []);
    // The newest loop is drawn only while the cursor is live: an earlier loop stays as it is.
    if (snapshot || cursor().live) render();
  });
  onCursor(() => {
    if (visible) render();
  });
  onEvents(showFilter);
}

/** Marks the card of the joint the list of events is filtered to, if it is to one. */
function showFilter() {
  const about = eventFilter().about;
  for (const [label, card] of cards) {
    card.name.setAttribute("aria-pressed", String(label === about));
  }
}

function show() {
  visible = true;
  showViews(offered() ? [offer().view] : []);
  render();
}

function hide() {
  visible = false;
  showViews([]);
}

/** Draws the moment the cursor is on: the newest loop, or an earlier one once the robot answers. */
function render() {
  const held = span();
  if (held && held.startedNanos !== sessionOf) {
    sessionOf = held.startedNanos;
    forgetViews(); // another robot program's session: its loops count from 0 again
  }
  const at = cursor();
  if (at.live || !held) {
    draw(live(), at);
    return;
  }
  const near = [];
  for (let by = 1; by <= BESIDE; by++) near.push(at.index + by, at.index - by);
  wantView(at.index, (view) => {
    if (view) noteTime(view.index, view.timestampNanos);
    const now = cursor();
    // Still wanted, and still held by the session: else the cursor has moved, and asks again.
    if (visible && view && !now.live && now.index === at.index) draw(view, now);
  }, near.filter((index) => index >= held.oldest && index <= held.newest));
}

/**
 * Draws one loop's view. A loop the page asked for comes with what changed in it from the loop
 * before (view.changed), and those rows are marked, so going loop by loop shows a cause and then
 * its effect; the newest loop, live, comes without.
 */
function draw(view, at) {
  ui.waiting.hidden = Boolean(view);
  ui.joints.parentElement.hidden = !view;
  if (!view) {
    ui.state.textContent = "";
    return;
  }
  ui.state.textContent = (at.live ? "Live" : "Loop " + view.index) + " · " + modeWords(view);
  ui.state.classList.toggle("changed", Boolean(view.changed && view.changed.robot));
  ui.view.classList.toggle("earlier", !at.live);
  renderJoints(view);
  renderSensors(view);
  renderGates(view);
  renderPads(view);
}

/** The mode and whether the robot may move, as the state bar words them. */
function modeWords(view) {
  const modes = { AUTONOMOUS: "Autonomous", TELEOPERATED: "Teleop", UTILITY: "Utility" };
  return (modes[view.mode] || "No mode") + (view.enabled === "ENABLED" ? ", enabled" : ", disabled");
}

// ---- The joints ----

function renderJoints(view) {
  const order = view.order || [];
  if (order.join("\n") !== [...cards.keys()].join("\n")) {
    cards.clear();
    ui.joints.replaceChildren(...order.map((label) => {
      const card = makeCard(label, view.joints[label]);
      cards.set(label, card);
      return card.el;
    }));
  }
  const changed = (view.changed && view.changed.joints) || {};
  for (const label of order) updateCard(cards.get(label), view.joints[label], changed[label] || []);
}

function makeCard(label, joint) {
  const card = { label, id: "rv-" + ++ids };
  card.tags = h("span", { class: "tags" });
  card.on = h("span", { class: "rv-on meta" });
  card.stage = h("div", { class: "rv-stage" });
  card.facts = h("dl", { class: "rv-facts" });
  card.rules = h("ul", { class: "rv-rules" });
  // Its name shows the events about this joint only, in the list of events, and all of them again.
  card.name = h("button", { type: "button", class: "link rv-name",
    "aria-pressed": String(eventFilter().about === label),
    title: "Show this joint's events only",
    onclick: () => setEventFilter({ about: eventFilter().about === label ? "" : label }) }, label);
  card.el = h("section", { class: "card rv-card", "aria-label": label },
    h("h2", null, card.name, card.on, card.tags),
    card.stage, card.facts, card.rules);
  card.on.textContent = joint.carriedBy ? "on the " + joint.carriedBy : "";
  return card;
}

/**
 * Shows a joint as it is in one loop: its drawing built again only when more than its needles
 * changed.
 *
 * @param changed the rows that changed in this loop, as the robot names them
 */
function updateCard(card, joint, changed) {
  const from = builtFrom(joint);
  if (card.builtFrom !== from) {
    card.builtFrom = from;
    card.drawing = drawJoint(card.label, joint, card.id + "-title");
    card.stage.replaceChildren(card.drawing.element);
  } else {
    card.drawing.update(joint);
  }
  const facts = joint.facts;
  const held = facts.hold === "HELD";
  const recorded = joint.recorded === "IN_THE_RECORD";
  const ready = !recorded || facts.standing === "READY";
  card.el.classList.toggle("held", held);
  replace(card.tags,
    held && h("span", { class: "tag refused" }, "✗ held"),
    !ready && h("span", { class: "tag tuned" }, "● " + facts.standing.toLowerCase()),
    !recorded && h("span", { class: "tag plain" }, "not in the record"));
  const did = (row) => changed.includes(row);
  replace(card.facts,
    fact("At", facts.at, "", did("AT")),
    recorded && fact("Told", facts.told, "", did("TOLD")),
    recorded && fact("Owned by", facts.ownedBy, "", did("OWNED_BY")),
    recorded && fact("Last move", facts.lastMove, "", did("LAST_MOVE")),
    facts.heldBy.map((rule) => held ? fact("Held by", rule, "bad", did("HELD_BY"))
      : fact("Restricted by", rule + ", while nothing asks it to run", "", did("HELD_BY"))),
    // What held it and holds it no longer has no row of its own left to mark.
    did("HELD_BY") && !facts.heldBy.length && fact("Held by", "nothing", "", true),
    (!ready || did("STATE")) && fact("State", facts.state, ready ? "" : "warn", did("STATE")),
    joint.sensors.map((sensor) => fact(capital(sensor.label), sensor.reading,
      sensor.health === "FINE" ? "" : "bad", did(SENSOR_ROWS[sensor.label] || "LIMIT_SWITCHES"))));
  replace(card.rules,
    joint.bands.map((band) => rule(band.emphasis, "▮ Keep-out " + band.shown + ", "
        + standing(band.emphasis, "KEEPS_OUT") + ": " + band.rule)),
    joint.marks.map((mark) => rule(mark.emphasis, "◆ " + mark.label + ", "
        + standing(mark.emphasis, mark.rule) + reading(mark.reading))));
}

/** The rows of a joint's own sensors, as the robot names them: any other is a limit switch. */
const SENSOR_ROWS = { "motor controller": "MOTOR_CONTROLLER",
                      "absolute encoder": "ABSOLUTE_ENCODER" };

/** One row of a card: a name and what it says, marked if it changed in this loop. */
function fact(name, value, state, changed) {
  const classes = [state, changed && "changed"].filter(Boolean).join(" ") || null;
  return [h("dt", { class: changed ? "changed" : null }, name), h("dd", { class: classes }, value)];
}

function rule(emphasis, words) {
  return h("li", { class: String(emphasis).toLowerCase().replace("_", "-") }, words);
}

/**
 * How an interlock stands, in words: a keep-out is open, in force, or holding its joint; a
 * run-only-when is met, not met, or what its joint is waiting for.
 */
function standing(emphasis, kind) {
  if (kind === "KEEPS_OUT") {
    return { QUIET: "open", IN_FORCE: "in force", WAITED_FOR: "holding the joint" }[emphasis];
  }
  return { QUIET: "met", IN_FORCE: "not met", WAITED_FOR: "waited for" }[emphasis];
}

/** A condition that could not be read says so: it restricts the joint, to be safe. */
function reading(read) {
  return read === "UNKNOWN" ? " (can't be read)" : "";
}

function capital(words) {
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// ---- Beside the joints ----

function renderSensors(view) {
  const changed = (view.changed && view.changed.sensors) || [];
  replace(ui.sensors, (view.sensorOrder || []).map((label) => {
    const sensor = view.sensors[label];
    const fine = sensor.health === "FINE";
    return h("li", { class: changed.includes(label) ? "changed" : null },
      h("div", { class: "rv-row" },
        h("span", { class: "sev " + (fine ? "ok" : "bad"), "aria-hidden": "true" },
          fine ? "●" : "✗"),
        h("span", { class: "k" }, label),
        h("span", { class: "v num" + (fine ? "" : " bad") }, sensor.reading)),
      sensor.kind === "RANGEFINDER" && rangeBar(sensor),
      h("div", { class: "meta" }, "On the " + sensor.on),
      sensor.marks.map((mark) => h("div", { class: "rv-rule "
          + String(mark.emphasis).toLowerCase().replace("_", "-") }, "◆ " + mark.label + ", "
          + standing(mark.emphasis, "RUNS_ONLY_WHEN") + reading(mark.reading))));
  }));
}

/**
 * A rangefinder's reading as a small bar out to its range, with a tick at the distance each rule
 * on it compares with (robot-draw.js), and the same distances in words.
 */
function rangeBar(sensor) {
  const marks = sensor.marks.filter((mark) => typeof mark.meters === "number");
  return h("div", { class: "rv-ranged" }, drawRange(sensor),
    marks.length > 0 && h("span", { class: "meta" }, "rules at "
        + marks.map((mark) => mark.meters + " m").join(", ")));
}

function renderGates(view) {
  const changed = (view.changed && view.changed.gates) || [];
  replace(ui.gates, (view.gateOrder || []).map((name) => {
    const gate = view.gates[name];
    const holds = gate.held === "HOLDS";
    return h("li", { class: changed.includes(name) ? "changed" : null },
      h("div", { class: "rv-row" },
        h("span", { class: "sev " + (holds ? "ok" : "bad"), "aria-hidden": "true" },
          holds ? "✓" : "✗"),
        h("span", { class: "k" }, name),
        h("span", { class: "v" }, holds ? "holds" : "does not hold")),
      !holds && gate.blocking.length > 0 && h("div", { class: "meta" }, gate.why));
  }), !(view.gateOrder || []).length && h("li", { class: "meta" }, "No gates."));
}

function renderPads(view) {
  const fired = view.fired || {};
  replace(ui.pads, Object.entries(view.gamepads || {}).map(([port, pad]) => {
    // The controls on this gamepad that just fired a binding, as the robot says them: a button by
    // its index, a trigger by its axis. Each is marked, and shown even once it is let go.
    const just = (fired.controls || []).filter((control) => String(control.port) === port);
    const firedButtons = just.filter((control) => control.control === "BUTTON")
      .map((control) => control.button);
    const firedAxes = just.map((control) => TRIGGER_AXES[control.control])
      .filter((axis) => axis !== undefined);
    const buttons = [...new Set([...pad.down, ...firedButtons])].sort((a, b) => a - b);
    const axes = pad.axes.map((value, axis) => [axis, value])
      .filter(([axis, value]) => value !== 0 || firedAxes.includes(axis));
    // WPILib's POV is 0 while centered; -1 is a gamepad the record has no POV of.
    return h("div", { class: "rv-pad" },
      h("div", { class: "rv-pad-name" }, "Port " + port),
      h("div", { class: "chips" },
        buttons.map((button) => control(BUTTON_NAMES[button] || "Button " + button, "tag edit",
          firedButtons.includes(button))),
        pad.pov > 0 && h("span", { class: "tag edit" }, "POV " + pad.pov),
        axes.map(([axis, value]) => control((AXIS_NAMES[axis] || "Axis " + axis) + " "
          + value.toFixed(2), "tag plain num", firedAxes.includes(axis))),
        !buttons.length && !axes.length && !(pad.pov > 0)
          && h("span", { class: "meta" }, "Nothing pressed")));
  }));
  const bindings = fired.bindings || [];
  replace(ui.fired, bindings.map((binding) => h("li", { class: "rv-just" }, "▶ " + binding)),
    !bindings.length && h("li", { class: "meta" }, "Nothing in the last second."));
}

/** The axis of each trigger, as the robot names the control that fired a binding. */
const TRIGGER_AXES = { LEFT_TRIGGER: 4, RIGHT_TRIGGER: 5 };

/** One control of a gamepad, marked if it just fired a binding. */
function control(name, classes, fired) {
  return h("span", { class: classes + (fired ? " rv-fired-by" : "") }, (fired ? "▶ " : "") + name);
}

export default { id: "robot", title: "Robot", mount, show, hide, offered };
