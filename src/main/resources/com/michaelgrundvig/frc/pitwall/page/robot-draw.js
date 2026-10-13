// One joint, drawn in SVG by its kind, from what the robot says of it (robotView.live.joints, see
// RobotWire): an arc for a joint that turns between stops, a full circle for one that turns right
// round, a speedometer for one run at speeds, and a straight bar for one that slides.
//
// On every drawing: the track is the joint's travel between its hard stops (or its speeds, up to
// its free speed); each named place or output is a tick with its name and value; a keep-out's
// region is a band on the track; the condition of another joint's interlock is a mark beside the
// track, where the condition holds, with that joint's name; the solid needle is where the joint
// is, the dashed one where it was told to go, and the small ring where its profile has it.
//
// Nothing here knows the page: it makes elements and sets attributes, and imports nothing, so the
// same module draws a saved moment outside the page. Colors come from the stylesheet, by class.
// A drawing is 250 by 156 units, and its card is never narrower than that, so its 11-unit text is
// never smaller than 11 px.

const SVG = "http://www.w3.org/2000/svg";

/** A drawing's size, in its own units. */
export const WIDTH = 250;
export const HEIGHT = 156;

const LINE = 13; // a line of text, in units
const CHAR = 6.1; // the width of an average character of 11-unit text
const GAP = 2; // the least space between two labels
const RADIUS = 46; // an arc's, a circle's and a speedometer's track

/** An SVG element with its attributes and children. Text is always set as text. */
function svg(tag, attributes, ...children) {
  const made = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attributes || {})) {
    if (value !== null && value !== undefined && value !== false) {
      made.setAttribute(name, String(value));
    }
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    made.append(child);
  }
  return made;
}

function text(x, y, className, words) {
  const made = svg("text", { x: fixed(x), y: fixed(y), class: className });
  made.textContent = words;
  return made;
}

function line(from, to, className) {
  return svg("line", { x1: fixed(from[0]), y1: fixed(from[1]), x2: fixed(to[0]),
                       y2: fixed(to[1]), class: className });
}

function fixed(number) {
  return Number(number.toFixed(1));
}

function clamp(value, low, high) {
  return Math.min(high, Math.max(low, value));
}

function finite(value) {
  return typeof value === "number" && Number.isFinite(value);
}

// ---- Labels: placed beside what they name, then moved apart until none overlaps ----

/**
 * A label's words as lines: a name and its value on one line while that is short, else the value
 * under the name, so a long name ("intaking 2000 rpm") stays inside the drawing.
 */
export function lines(name, value) {
  const together = value ? name + " " + value : name;
  return together.length <= 11 || !value ? [together] : [name, value];
}

/**
 * Places labels so that none overlaps another, and each stays inside the drawing. Each label says
 * where it wants to be ({x, y}: the point it names, pushed out from the track), how it sits
 * against that point ({anchor}: "start", "middle" or "end"), and its {rank}: where two overlap,
 * the one with the higher rank moves ({@link moveAway}). A place's name (rank 0) stays put before
 * another joint's mark (1), and a stop's value (2) gives way to both.
 *
 * <p>The common case is a place beside a stop: "in 1.5°" at the 0° stop. Both want the same spot;
 * the stop's value moves off it, and a thin line joins it to its tick.
 *
 * @return the labels, each with its box: {left, top, width, height}
 */
export function placeLabels(labels) {
  for (const label of labels) {
    label.width = Math.max(...label.lines.map((words) => words.length)) * CHAR + 4;
    label.height = label.lines.length * LINE;
    let left = label.x;
    if (label.anchor === "middle") left -= label.width / 2;
    if (label.anchor === "end") left -= label.width;
    label.left = clamp(left, 1, WIDTH - label.width - 1);
    label.top = clamp(label.y - label.height / 2, 1, HEIGHT - label.height - 1);
  }
  const overlaps = (a, b) => a.left < b.left + b.width + GAP && b.left < a.left + a.width + GAP
      && a.top < b.top + b.height + GAP && b.top < a.top + a.height + GAP;
  for (let pass = 0; pass < 60; pass++) {
    let moved = false;
    for (let i = 0; i < labels.length; i++) {
      for (let j = i + 1; j < labels.length; j++) {
        const [a, b] = [labels[i], labels[j]];
        if (!overlaps(a, b)) continue;
        const mover = b.rank >= a.rank ? b : a;
        const other = mover === a ? b : a;
        moveAway(mover, other);
        moved = true;
      }
    }
    if (!moved) break;
  }
  return labels;
}

/**
 * Moves one label off another: up or down, whichever way leads away from the middle of the
 * drawing, where the track and the needles are; if there is no room that way, sideways, to the
 * side it already leans; and only then the other way, over the drawing.
 */
function moveAway(mover, other) {
  const below = other.top + other.height + GAP;
  const above = other.top - mover.height - GAP;
  const fitsBelow = below <= HEIGHT - mover.height - 1;
  const fitsAbove = above >= 1;
  const outwardIsDown = mover.y >= HEIGHT / 2;
  if (outwardIsDown && fitsBelow) {
    mover.top = below;
    return;
  }
  if (!outwardIsDown && fitsAbove) {
    mover.top = above;
    return;
  }
  const left = other.left - mover.width - GAP;
  const right = other.left + other.width + GAP;
  const leansLeft = mover.left + mover.width / 2 <= other.left + other.width / 2;
  if (leansLeft && left >= 1) {
    mover.left = left;
  } else if (right <= WIDTH - mover.width - 1) {
    mover.left = right;
  } else if (left >= 1) {
    mover.left = left;
  } else {
    mover.top = outwardIsDown ? above : below;
  }
}

/** Draws placed labels, each joined to the point it names when it had to move away from it. */
function drawLabels(labels) {
  const drawn = [];
  for (const label of labels) {
    const nearX = clamp(label.at[0], label.left, label.left + label.width);
    const nearY = clamp(label.at[1], label.top, label.top + label.height);
    if (Math.hypot(nearX - label.at[0], nearY - label.at[1]) > 5) {
      drawn.push(line(label.at, [nearX, nearY], "rv-leader"));
    }
    label.lines.forEach((words, row) => {
      drawn.push(text(label.left + 2, label.top + LINE * (row + 1) - 3, label.className, words));
    });
  }
  return drawn;
}

// ---- A joint drawn in the round: an arc, a full circle, or a speedometer ----

/**
 * How a round drawing turns a value into an angle on the card, in degrees counterclockwise from
 * the right. An arc and a circle turn as the joint really does, from where its zero points; a
 * speedometer sweeps over the top, from its least speed at the left to its greatest at the right.
 */
function turner(joint) {
  const scale = joint.scale;
  if (joint.drawn === "DIAL") {
    const span = scale.upper - scale.lower || 1;
    return { angle: (value) => 180 - 180 * (value - scale.lower) / span, clockwise: true };
  }
  const clockwise = scale.turning === "CLOCKWISE";
  return { angle: (value) => scale.zeroAt + (clockwise ? -value : value), clockwise };
}

function unit(angle) {
  const radians = angle * Math.PI / 180;
  return [Math.cos(radians), -Math.sin(radians)];
}

/**
 * Where a round drawing's center goes, so that what it draws sits in the middle of the card: an
 * arc that opens to one side is moved over, to leave its labels room on the side it bulges to.
 */
function centerOf(joint, turn) {
  if (joint.drawn === "CIRCLE") return [WIDTH / 2, HEIGHT / 2];
  const xs = [0];
  const ys = [0];
  const { lower, upper } = joint.scale;
  for (let step = 0; step <= 36; step++) {
    const [x, y] = unit(turn.angle(lower + (upper - lower) * step / 36));
    xs.push(x);
    ys.push(y);
  }
  const middle = (values) => (Math.min(...values) + Math.max(...values)) / 2;
  return [WIDTH / 2 - middle(xs) * RADIUS, HEIGHT / 2 - middle(ys) * RADIUS];
}

function drawRound(joint) {
  const scale = joint.scale;
  const turn = turner(joint);
  const center = centerOf(joint, turn);
  const circle = joint.drawn === "CIRCLE";
  const at = (value, radius) => {
    const [x, y] = unit(turn.angle(value));
    return [center[0] + x * radius, center[1] + y * radius];
  };
  /** An arc along the track from one value to another, at a radius. */
  const arc = (from, to, radius, className) => {
    const [x1, y1] = at(from, radius);
    const [x2, y2] = at(to, radius);
    const sweep = Math.abs(turn.angle(to) - turn.angle(from));
    return svg("path", { class: className, d: "M" + fixed(x1) + " " + fixed(y1) + " A" + radius
        + " " + radius + " 0 " + (sweep > 180 ? 1 : 0) + " " + (turn.clockwise ? 1 : 0) + " "
        + fixed(x2) + " " + fixed(y2) });
  };
  /** A label pushed out from the track at a value, sitting away from the center. */
  const label = (value, radius, words, className, rank) => {
    const [x, y] = unit(turn.angle(value));
    const anchor = x > 0.25 ? "start" : x < -0.25 ? "end" : "middle";
    return { x: center[0] + x * (radius + 3), y: center[1] + y * (radius + 8), anchor,
             at: at(value, radius - 2), lines: words, className, rank };
  };
  const parts = [];
  const labels = [];
  const within = (value) => clamp(value, scale.lower, scale.upper);

  parts.push(circle
    ? svg("circle", { class: "rv-track", cx: fixed(center[0]), cy: fixed(center[1]), r: RADIUS })
    : arc(scale.lower, scale.upper, RADIUS, "rv-track"));
  if (finite(scale.softLower) || finite(scale.softUpper)) {
    const from = finite(scale.softLower) ? scale.softLower : scale.lower;
    const to = finite(scale.softUpper) ? scale.softUpper : scale.upper;
    parts.push(arc(from, to, RADIUS - 7, "rv-soft"));
  }
  for (const band of joint.bands) {
    parts.push(arc(within(band.lower), within(band.upper), RADIUS, "rv-band " + cls(band.emphasis)));
  }
  for (const mark of joint.marks) {
    if (!finite(mark.from) && !finite(mark.to)) continue;
    const from = finite(mark.from) ? within(mark.from) : scale.lower;
    const to = finite(mark.to) ? within(mark.to) : scale.upper;
    const state = cls(mark.emphasis);
    if (to > from) parts.push(arc(from, to, RADIUS + 9, "rv-mark " + state));
    for (const edge of [mark.from, mark.to]) {
      if (finite(edge)) {
        parts.push(line(at(within(edge), RADIUS + 3), at(within(edge), RADIUS + 15),
          "rv-mark-edge " + state));
      }
    }
    const named = finite(mark.from) ? within(mark.from) : within(mark.to);
    labels.push(label(named, RADIUS + 18, ["◆ " + mark.forJoint], "rv-mark-label " + state, 1));
  }
  if (!circle) {
    for (const [value, shown] of [[scale.lower, scale.lowerShown], [scale.upper, scale.upperShown]]) {
      parts.push(line(at(value, RADIUS - 6), at(value, RADIUS + 6), "rv-stop"));
      labels.push(label(value, RADIUS + 9, [shown], "rv-stop-label", 2));
    }
    if (joint.drawn === "DIAL" && scale.lower < 0) {
      parts.push(line(at(0, RADIUS - 5), at(0, RADIUS + 5), "rv-stop"));
      labels.push(label(0, RADIUS + 9, ["0"], "rv-stop-label", 2));
    }
  }
  for (const tick of joint.ticks) {
    const asked = tick.asked === "ASKED_FOR" ? " asked" : "";
    parts.push(line(at(tick.value, RADIUS - 7), at(tick.value, RADIUS + 7), "rv-tick" + asked));
    labels.push(label(tick.value, RADIUS + 9, lines(tick.name, tick.shown),
      "rv-tick-label" + asked, 0));
  }

  const goal = line(center, center, "rv-goal");
  const setpoint = svg("circle", { class: "rv-setpoint", r: 3.5 });
  const now = line(center, center, "rv-needle");
  const hub = svg("circle", { class: "rv-hub", cx: fixed(center[0]), cy: fixed(center[1]), r: 3 });
  /** Points a needle at a value, or hides it when there is none. */
  const point = (needle, value, radius) => {
    needle.setAttribute("visibility", finite(value) ? "visible" : "hidden");
    if (!finite(value)) return;
    const [x, y] = at(circle ? value : within(value), radius);
    needle.setAttribute("x2", fixed(x));
    needle.setAttribute("y2", fixed(y));
  };
  const update = (needles) => {
    point(goal, needles.goal, RADIUS - 2);
    point(now, needles.now, RADIUS - 2);
    setpoint.setAttribute("visibility", finite(needles.setpoint) ? "visible" : "hidden");
    if (finite(needles.setpoint)) {
      const [x, y] = at(circle ? needles.setpoint : within(needles.setpoint), RADIUS);
      setpoint.setAttribute("cx", fixed(x));
      setpoint.setAttribute("cy", fixed(y));
    }
  };
  return { parts: [...parts, ...drawLabels(placeLabels(labels)), goal, setpoint, now, hub], update };
}

// ---- A joint that slides: a straight bar, upright for one that slides up ----

function drawBar(joint) {
  const scale = joint.scale;
  const span = scale.upper - scale.lower || 1;
  const upright = scale.zeroAt === 90 || scale.zeroAt === 270;
  const reversed = scale.zeroAt === 270 || scale.zeroAt === 180;
  const share = (value) => {
    const along = (clamp(value, scale.lower, scale.upper) - scale.lower) / span;
    return reversed ? 1 - along : along;
  };
  // Upright, the bar stands left of the middle and its labels sit to its right; level, it lies
  // across the card with its names above and everything else below.
  const along = upright ? 124 : 200;
  const origin = upright ? [70, 140] : [25, 84];
  /** A point at a value, a number of units off the bar: to the right when upright, else below. */
  const at = (value, off) => upright
    ? [origin[0] + off, origin[1] - share(value) * along]
    : [origin[0] + share(value) * along, origin[1] + off];
  const label = (value, off, words, className, rank) => {
    const [x, y] = at(value, off);
    return upright
      ? { x: x + 3, y, anchor: off < 0 ? "end" : "start", at: at(value, off < 0 ? -8 : 8),
          lines: words, className, rank }
      : { x, y: y + (off < 0 ? -LINE / 2 : LINE / 2), anchor: "middle",
          at: at(value, off < 0 ? -8 : 8), lines: words, className, rank };
  };
  const parts = [line(at(scale.lower, 0), at(scale.upper, 0), "rv-track")];
  const labels = [];
  if (finite(scale.softLower) || finite(scale.softUpper)) {
    const from = finite(scale.softLower) ? scale.softLower : scale.lower;
    const to = finite(scale.softUpper) ? scale.softUpper : scale.upper;
    parts.push(line(at(from, -7), at(to, -7), "rv-soft"));
  }
  for (const band of joint.bands) {
    parts.push(line(at(band.lower, 0), at(band.upper, 0), "rv-band " + cls(band.emphasis)));
  }
  for (const mark of joint.marks) {
    if (!finite(mark.from) && !finite(mark.to)) continue;
    const from = finite(mark.from) ? mark.from : scale.lower;
    const to = finite(mark.to) ? mark.to : scale.upper;
    const state = cls(mark.emphasis);
    parts.push(line(at(from, -9), at(to, -9), "rv-mark " + state));
    for (const edge of [mark.from, mark.to]) {
      if (finite(edge)) parts.push(line(at(edge, -3), at(edge, -15), "rv-mark-edge " + state));
    }
    const named = finite(mark.from) ? mark.from : mark.to;
    labels.push(label(named, -18, ["◆ " + mark.forJoint], "rv-mark-label " + state, 1));
  }
  for (const [value, shown] of [[scale.lower, scale.lowerShown], [scale.upper, scale.upperShown]]) {
    parts.push(line(at(value, -6), at(value, 6), "rv-stop"));
    labels.push(label(value, 12, [shown], "rv-stop-label", 2));
  }
  for (const tick of joint.ticks) {
    const asked = tick.asked === "ASKED_FOR" ? " asked" : "";
    parts.push(line(at(tick.value, -7), at(tick.value, 7), "rv-tick" + asked));
    labels.push(label(tick.value, upright ? 12 : -12, lines(tick.name, tick.shown),
      "rv-tick-label" + asked, 0));
  }
  const goal = line(origin, origin, "rv-goal");
  const now = line(origin, origin, "rv-needle");
  const setpoint = svg("circle", { class: "rv-setpoint", r: 3.5 });
  /** Lays a needle across the bar at a value, or hides it when there is none. */
  const cross = (needle, value, reach) => {
    needle.setAttribute("visibility", finite(value) ? "visible" : "hidden");
    if (!finite(value)) return;
    const [x1, y1] = at(value, -reach);
    const [x2, y2] = at(value, reach);
    needle.setAttribute("x1", fixed(x1));
    needle.setAttribute("y1", fixed(y1));
    needle.setAttribute("x2", fixed(x2));
    needle.setAttribute("y2", fixed(y2));
  };
  const update = (needles) => {
    cross(goal, needles.goal, 13);
    cross(now, needles.now, 10);
    setpoint.setAttribute("visibility", finite(needles.setpoint) ? "visible" : "hidden");
    if (finite(needles.setpoint)) {
      const [x, y] = at(needles.setpoint, 0);
      setpoint.setAttribute("cx", fixed(x));
      setpoint.setAttribute("cy", fixed(y));
    }
  };
  return { parts: [...parts, ...drawLabels(placeLabels(labels)), goal, setpoint, now], update };
}

/** An emphasis as a class: "quiet", "in-force" or "waited-for". */
function cls(emphasis) {
  return String(emphasis || "QUIET").toLowerCase().replace("_", "-");
}

/** What a drawing shows, in one sentence: its text alternative. */
export function described(label, joint) {
  const facts = joint.facts;
  const said = [label + ": " + (facts.at || "unknown")];
  if (facts.told && facts.told !== "nothing") said.push("told " + facts.told);
  for (const rule of facts.heldBy || []) {
    said.push((facts.hold === "HELD" ? "held: " : "restricted: ") + rule);
  }
  return said.join("; ");
}

/** What of a joint its drawing is built from, apart from its needles: built again when it changes. */
export function builtFrom(joint) {
  return JSON.stringify([joint.drawn, joint.scale, joint.ticks, joint.bands, joint.marks]);
}

/**
 * Draws a joint.
 *
 * @param label the joint's label
 * @param joint the joint as the robot says it (robotView.live.joints[label])
 * @param titleId an id for the drawing's title, unique on the page
 * @return {element, update(joint)}: the drawing, and what moves its needles and renames it
 */
export function drawJoint(label, joint, titleId) {
  const drawing = joint.drawn === "BAR" ? drawBar(joint) : drawRound(joint);
  const title = svg("title", { id: titleId });
  const element = svg("svg", { class: "rv-drawing", viewBox: "0 0 " + WIDTH + " " + HEIGHT,
                               role: "img", "aria-labelledby": titleId }, title, drawing.parts);
  const update = (now) => {
    title.textContent = described(label, now);
    drawing.update(now.needles || {});
  };
  update(joint);
  return { element, update };
}

/**
 * Draws a rangefinder's reading as a short bar from nothing to the farthest it reads: filled out
 * to what it reads now, with a tick at the distance each rule on it compares with, drawn by how
 * the rule stands. The row beside it says the same in words.
 *
 * @param sensor the sensor as the robot says it (robotView.live.sensors[label])
 */
export function drawRange(sensor) {
  const width = 120;
  const x = (meters) => 2 + clamp(meters / (sensor.range || 1), 0, 1) * (width - 4);
  const parts = [line([2, 9], [width - 2, 9], "rv-range-track")];
  if (finite(sensor.meters)) parts.push(line([2, 9], [x(sensor.meters), 9], "rv-range-now"));
  for (const mark of sensor.marks) {
    if (finite(mark.meters)) {
      parts.push(line([x(mark.meters), 2], [x(mark.meters), 16], "rv-mark-edge " + cls(mark.emphasis)));
    }
  }
  return svg("svg", { class: "rv-range", viewBox: "0 0 " + width + " 18", width, height: 18,
                      "aria-hidden": "true" }, parts);
}
