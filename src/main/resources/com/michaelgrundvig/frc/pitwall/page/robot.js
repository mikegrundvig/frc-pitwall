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
// is drawn from one loop's view alone, the same way whichever loop it is.

import { robot, onChange, showViews, h, replace } from "./core.js";
import { cursor, onCursor, span, noteTime } from "./cursor.js";
import { wantView, forgetViews } from "./robot-moments.js";
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
          ui.pads, h("h3", { class: "rv-sub" }, "Fired"), ui.fired))));
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

function draw(view, at) {
  ui.waiting.hidden = Boolean(view);
  ui.joints.parentElement.hidden = !view;
  if (!view) {
    ui.state.textContent = "";
    return;
  }
  ui.state.textContent = (at.live ? "Live" : "Loop " + view.index) + " · " + modeWords(view);
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
  for (const label of order) updateCard(cards.get(label), view.joints[label]);
}

function makeCard(label, joint) {
  const card = { label, id: "rv-" + ++ids };
  card.tags = h("span", { class: "tags" });
  card.on = h("span", { class: "rv-on meta" });
  card.stage = h("div", { class: "rv-stage" });
  card.facts = h("dl", { class: "rv-facts" });
  card.rules = h("ul", { class: "rv-rules" });
  card.el = h("section", { class: "card rv-card", "aria-label": label },
    h("h2", null, h("span", { class: "rv-name" }, label), card.on, card.tags),
    card.stage, card.facts, card.rules);
  card.on.textContent = joint.carriedBy ? "on the " + joint.carriedBy : "";
  return card;
}

/** Shows a joint as it is now: its drawing built again only when more than its needles changed. */
function updateCard(card, joint) {
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
  replace(card.facts,
    fact("At", facts.at),
    recorded && fact("Told", facts.told),
    recorded && fact("Owned by", facts.ownedBy),
    recorded && fact("Last move", facts.lastMove),
    facts.heldBy.map((rule) => held ? fact("Held by", rule, "bad")
      : fact("Restricted by", rule + ", while nothing asks it to run")),
    !ready && fact("State", facts.state, "warn"),
    joint.sensors.map((sensor) => fact(capital(sensor.label), sensor.reading,
      sensor.health === "FINE" ? "" : "bad")));
  replace(card.rules,
    joint.bands.map((band) => rule(band.emphasis, "▮ Keep-out " + band.shown + ", "
        + standing(band.emphasis, "KEEPS_OUT") + ": " + band.rule)),
    joint.marks.map((mark) => rule(mark.emphasis, "◆ " + mark.label + ", "
        + standing(mark.emphasis, mark.rule) + reading(mark.reading))));
}

function fact(name, value, state) {
  return [h("dt", null, name), h("dd", { class: state || null }, value)];
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
  replace(ui.sensors, (view.sensorOrder || []).map((label) => {
    const sensor = view.sensors[label];
    const fine = sensor.health === "FINE";
    return h("li", null,
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
  replace(ui.gates, (view.gateOrder || []).map((name) => {
    const gate = view.gates[name];
    const holds = gate.held === "HOLDS";
    return h("li", null,
      h("div", { class: "rv-row" },
        h("span", { class: "sev " + (holds ? "ok" : "bad"), "aria-hidden": "true" },
          holds ? "✓" : "✗"),
        h("span", { class: "k" }, name),
        h("span", { class: "v" }, holds ? "holds" : "does not hold")),
      !holds && gate.blocking.length > 0 && h("div", { class: "meta" }, gate.why));
  }), !(view.gateOrder || []).length && h("li", { class: "meta" }, "No gates."));
}

function renderPads(view) {
  replace(ui.pads, Object.entries(view.gamepads || {}).map(([port, pad]) => {
    const down = pad.down.map((button) => BUTTON_NAMES[button] || "Button " + button);
    // WPILib's POV is 0 while centered; -1 is a gamepad the record has no POV of.
    if (pad.pov > 0) down.push("POV " + pad.pov);
    const axes = pad.axes.map((value, axis) => [AXIS_NAMES[axis] || "Axis " + axis, value])
      .filter(([, value]) => value !== 0);
    return h("div", { class: "rv-pad" },
      h("div", { class: "rv-pad-name" }, "Port " + port),
      h("div", { class: "chips" },
        down.map((name) => h("span", { class: "tag edit" }, name)),
        axes.map(([name, value]) => h("span", { class: "tag plain num" },
          name + " " + value.toFixed(2))),
        !down.length && !axes.length && h("span", { class: "meta" }, "Nothing pressed")));
  }));
  const fired = (view.fired && view.fired.bindings) || [];
  replace(ui.fired, fired.map((binding) => h("li", { class: "rv-just" }, "▶ " + binding)),
    !fired.length && h("li", { class: "meta" }, "Nothing in the last second."));
}

export default { id: "robot", title: "Robot", mount, show, hide, offered };
