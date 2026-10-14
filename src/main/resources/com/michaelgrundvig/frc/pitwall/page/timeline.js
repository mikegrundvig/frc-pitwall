// The time strip: the session's memory as a strip of time, with a mark for each event, the page's
// cursor on it (cursor.js), and the controls that move the cursor. Any view that shows a moment of
// the session can have one along its bottom: makeTimeline() makes it, and it shows itself only
// while its view does.
//
// Moving the cursor moves what the page shows of what was recorded. It never rewinds the robot or
// the simulator, which is why nothing here says "step": the Sim view's Step runs a new loop of a
// paused simulator, and these go to an earlier or a later loop of what already happened.
//
// The strip spans the memory, or the last 10 s, 30 s or 2 min before now. While the cursor stands
// on an earlier loop the strip holds still under it, and moves only to keep the cursor in sight.
// Marks that would touch are drawn as one with their count, and a problem among them always shows.
//
// Keys, while the strip is on screen, no field has the focus, and keyboard driving is off (it
// drives with the arrows and L): Left and Right go one loop, with Shift one event; Space plays and
// pauses; Esc or L goes back to live. None of them reaches the robot. Esc is the robot's first:
// while a simulated robot is enabled it disables it, and while something runs it stops it
// (keys.js), and only otherwise is it the strip's.

import { robot, onChange, running, typing, h } from "./core.js";
import { cursor, onCursor, span, timeOf, loopAt, seek, step, goLive, play, pause, RATES }
  from "./cursor.js";
import { shownEvents, eventBefore, eventAfter, onEvents } from "./session-events.js";
import { status as driving, onDrive } from "./sim-drive.js";

const SVG = "http://www.w3.org/2000/svg";

/** The strip's spans: the seconds before now it shows, or all the memory holds. */
const ZOOMS = [[10, "10 s"], [30, "30 s"], [120, "2 min"], [0, "All"]];

/** The least the strip spans, in seconds, so a session's first seconds aren't stretched across. */
const LEAST_SECONDS = 10;

/** How near two marks may be before they are drawn as one, in pixels. */
const MARK_APART = 14;

/** How near a click must be to a mark to mean it, in pixels. */
const MARK_REACH = 7;

const TRACK_HEIGHT = 46;

/** How wide a character of a tick's time is drawn, at most, in pixels (11 px type). */
const TICK_LABEL_PIXELS = 7;

function svg(tag, attributes, ...children) {
  const made = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes || {})) made.setAttribute(key, value);
  made.append(...children);
  return made;
}

/** Seconds as the strip writes them: "16.44 s", and from a minute on "1:16.4". */
function seconds(nanos, places = 2) {
  const all = nanos / 1e9;
  if (Math.abs(all) < 60) return all.toFixed(places) + " s";
  const minutes = Math.trunc(all / 60);
  return minutes + ":" + Math.abs(all - minutes * 60).toFixed(1).padStart(4, "0");
}

/** Makes a time strip: {element}, to put along the bottom of a view. */
export function makeTimeline() {
  const ui = {};
  let zoom = 0; // the seconds the strip spans, or 0 for all the memory holds
  let heldEnd = null; // where the strip ends while the cursor is not live, on the robot's clock
  let width = 0;
  let marks = []; // the marks as drawn: {x, events}
  let shown = { from: 0, to: 1 }; // the times at the strip's two ends
  let frame = null;
  let dragFrom = null; // where a drag began, in pixels from the track's left

  ui.status = h("span", { class: "tag ts-status" });
  ui.time = h("span", { class: "ts-time num" });
  ui.note = h("span", { class: "ts-note meta", role: "status" });
  ui.previous = button("« Event", "Previous event (Shift+Left)", () => toEvent(-1));
  ui.earlier = button("‹ Loop", "Earlier loop, 20 ms (Left)", () => step(-1));
  ui.play = button("▶ Play", "Play from here (Space)", togglePlay);
  ui.later = button("Loop ›", "Later loop, 20 ms (Right)", () => step(1));
  ui.next = button("Event »", "Next event (Shift+Right)", () => toEvent(1));
  ui.rates = segmented("Playing speed", RATES.map((rate) => [rate, rate + "×"]), (rate) => {
    if (cursor().playing) play(rate);
    else setRate(rate);
  });
  ui.zooms = segmented("The strip shows", ZOOMS, (chosen) => {
    zoom = chosen;
    heldEnd = null;
    draw();
  });
  ui.live = h("button", { type: "button", class: "small ts-live", title: "Back to live (Esc or L)",
                          onclick: goLive }, "Back to live");
  ui.drawing = svg("svg", { class: "ts-drawing", "aria-hidden": "true", height: TRACK_HEIGHT });
  ui.track = h("div", { class: "ts-track", role: "slider", tabindex: "0",
                        "aria-label": "The moment shown, in the session's memory" }, ui.drawing);
  // One bar that wraps: where the cursor is, then the controls in the order they are used, and a
  // note, when there is one, on a line of its own under them.
  const element = h("section", { class: "ts", "aria-label": "Timeline" },
    h("div", { class: "ts-bar" },
      h("div", { class: "ts-where" }, ui.status, ui.time, ui.live),
      h("div", { class: "ts-group", role: "group", "aria-label": "Move through what happened" },
        ui.previous, ui.earlier, ui.play, ui.later, ui.next),
      ui.rates.element, ui.zooms.element, ui.note),
    ui.track);

  let rate = 1;
  function setRate(chosen) {
    rate = chosen;
    draw();
  }

  function button(text, title, onclick) {
    return h("button", { type: "button", class: "small", title, onclick }, text);
  }

  /** A row of choices, one pressed: {element, show(value)}. */
  function segmented(label, choices, choose) {
    const buttons = choices.map(([value, text]) => h("button", { type: "button", class: "small",
      "aria-pressed": "false", onclick: () => choose(value) }, text));
    return {
      element: h("div", { class: "seg ts-seg", role: "group", "aria-label": label }, buttons),
      show(value) {
        buttons.forEach((each, index) => each.setAttribute("aria-pressed",
          String(choices[index][0] === value)));
      },
    };
  }

  function togglePlay() {
    if (cursor().playing) pause();
    else play(rate);
  }

  /** Goes to the event before or after the cursor's loop, among those the filter shows. */
  function toEvent(direction) {
    const at = cursor();
    const event = direction < 0 ? eventBefore(at.index) : eventAfter(at.index);
    if (!event) return;
    pause();
    seek(event.loop);
  }

  // ---- What the strip spans ----

  /** The times at the strip's two ends: all the memory, or so many seconds up to its end. */
  function ends(held, at) {
    if (at.live) heldEnd = null;
    else if (heldEnd === null) heldEnd = held.newestNanos;
    const wide = zoom === 0 ? Infinity : zoom * 1e9;
    if (zoom === 0) {
      const to = held.newestNanos;
      return { from: Math.min(held.oldestNanos, to - LEAST_SECONDS * 1e9), to };
    }
    let to = at.live ? held.newestNanos : Math.min(heldEnd, held.newestNanos);
    if (!at.live) {
      // The strip holds still under a cursor that isn't live, and moves only to keep it in sight.
      const where = timeOf(at.index);
      if (where < to - wide) to = where + wide * 0.75;
      if (where > to) to = where + wide * 0.25;
      to = Math.min(to, held.newestNanos);
      heldEnd = to;
    }
    return { from: to - wide, to };
  }

  function xOf(nanos) {
    return (nanos - shown.from) / (shown.to - shown.from) * width;
  }

  function nanosAt(x) {
    return shown.from + x / width * (shown.to - shown.from);
  }

  // ---- Drawing ----

  function draw() {
    if (frame !== null) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      drawNow();
    });
  }

  function drawNow() {
    const held = span();
    element.hidden = !held;
    if (!held) return;
    const at = cursor();
    width = ui.track.clientWidth;
    if (width <= 0) return; // its view isn't on screen
    shown = ends(held, at);
    drawBar(held, at);
    drawTrack(held, at);
  }

  function drawBar(held, at) {
    const paused = Boolean(robot.doc && robot.doc.simulator && robot.doc.simulator.paused);
    const when = timeOf(at.index);
    const behind = held.newestNanos - when;
    ui.status.className = "tag ts-status " + (at.live ? "live" : "earlier");
    ui.status.textContent = at.live ? (paused ? "❚❚ Live, simulator paused" : "● Live")
      : (at.playing ? "▶ " : "❚❚ ") + seconds(behind, 1) + " behind live";
    ui.time.textContent = seconds(when - held.startedNanos)
      + (at.live ? "" : " · −" + seconds(behind, 1) + " · loop " + at.index);
    ui.note.textContent = at.agedOut
      ? "That moment left the memory. This is the oldest one it holds."
      : paused ? "The simulator is paused, so live stands still. Step one new loop, in Sim "
        + "controls, runs one."
      : driving().keyboard.on ? "The keyboard drives the robot, so the keys here are off."
      : "";
    ui.play.textContent = at.playing ? "❚❚ Pause" : "▶ Play";
    ui.play.disabled = at.live;
    ui.earlier.disabled = at.index <= held.oldest;
    ui.later.disabled = at.live;
    ui.previous.disabled = !eventBefore(at.index);
    ui.next.disabled = !eventAfter(at.index);
    ui.live.disabled = at.live;
    ui.live.classList.toggle("primary", !at.live);
    ui.rates.show(at.playing ? at.rate : rate);
    ui.zooms.show(zoom);
    ui.track.setAttribute("aria-valuemin", held.oldest);
    ui.track.setAttribute("aria-valuemax", held.newest);
    ui.track.setAttribute("aria-valuenow", at.index);
    ui.track.setAttribute("aria-valuetext", seconds(when - held.startedNanos)
      + (at.live ? ", live" : ", " + seconds(behind, 1) + " before now"));
  }

  function drawTrack(held, at) {
    const parts = [];
    const top = 16; // the marks' band, under the times
    const bottom = TRACK_HEIGHT - 4;
    // What the memory holds, of what the strip spans.
    const heldFrom = Math.max(0, xOf(held.oldestNanos));
    const heldTo = Math.min(width, xOf(held.newestNanos));
    parts.push(svg("rect", { class: "ts-held", x: heldFrom, y: top,
                             width: Math.max(0, heldTo - heldFrom), height: bottom - top, rx: 3 }));
    let labeledTo = -Infinity; // where the last time written ends: the next is written clear of it
    for (const [nanos, label] of ticks(held)) {
      const x = xOf(nanos);
      parts.push(svg("line", { class: "ts-tick", x1: x, x2: x, y1: top - 3, y2: bottom }));
      const anchor = x > width - 30 ? "end" : x < 30 ? "start" : "middle";
      const wide = label.length * TICK_LABEL_PIXELS;
      const from = anchor === "end" ? x - wide : anchor === "start" ? x : x - wide / 2;
      if (from < labeledTo + 4) continue; // it would run into the one before: the tick alone
      labeledTo = from + wide;
      parts.push(svg("text", { class: "ts-tick-label", x, y: 10, "text-anchor": anchor }, label));
    }
    marks = grouped(shownEvents().filter((event) => event.timestampNanos >= shown.from
      && event.timestampNanos <= shown.to));
    for (const mark of marks) parts.push(drawMark(mark, top, bottom));
    const x = Math.min(width - 1, Math.max(1, xOf(timeOf(at.index))));
    parts.push(svg("g", { class: "ts-cursor" + (at.live ? " live" : "") },
      svg("line", { x1: x, x2: x, y1: top - 4, y2: bottom + 2 }),
      svg("path", { d: `M ${x - 5} ${top - 9} L ${x + 5} ${top - 9} L ${x} ${top - 2} Z` })));
    ui.drawing.setAttribute("width", width);
    ui.drawing.setAttribute("viewBox", `0 0 ${width} ${TRACK_HEIGHT}`);
    ui.drawing.replaceChildren(...parts);
  }

  /** The times marked along the strip, at most ten, each with seconds from the session's start. */
  function ticks(held) {
    const wide = (shown.to - shown.from) / 1e9;
    const each = [0.5, 1, 2, 5, 10, 30, 60, 120].find((stride) => wide / stride <= 10) || 300;
    const found = [];
    const first = Math.ceil((shown.from - held.startedNanos) / 1e9 / each) * each;
    for (let at = first; held.startedNanos + at * 1e9 <= shown.to; at += each) {
      if (at >= 0) found.push([held.startedNanos + at * 1e9, seconds(at * 1e9, each < 1 ? 1 : 0)]);
    }
    return found;
  }

  /** Marks that would touch, drawn as one: each {x, events}, in order. */
  function grouped(events) {
    const groups = [];
    for (const event of events) {
      const x = xOf(event.timestampNanos);
      const last = groups[groups.length - 1];
      if (last && x - last.from < MARK_APART) last.events.push(event);
      else groups.push({ from: x, events: [event] });
    }
    for (const group of groups) {
      const to = xOf(group.events[group.events.length - 1].timestampNanos);
      group.x = (group.from + to) / 2;
    }
    return groups;
  }

  /**
   * One mark: a line for one event, and a count over it for several. A problem among them is drawn
   * thicker, with a cross over it, in the color of trouble: never by color alone.
   */
  function drawMark(mark, top, bottom) {
    const problems = mark.events.filter((event) => event.severity === "PROBLEM").length;
    const many = mark.events.length > 1;
    const said = mark.events.slice(0, 6).map((event) => event.sentence);
    if (mark.events.length > 6) said.push("and " + (mark.events.length - 6) + " more");
    const labeled = many || problems > 0;
    const made = svg("g", { class: "ts-mark" + (problems ? " problem" : "") },
      svg("title", null, said.join("\n")),
      svg("line", { x1: mark.x, x2: mark.x, y1: labeled ? top + 13 : top + 5, y2: bottom }));
    if (labeled) {
      const text = (problems ? "✗" : "") + (many ? mark.events.length : "");
      made.append(svg("text", { x: mark.x, y: top + 11, "text-anchor": "middle" }, text));
    }
    return made;
  }

  // ---- Dragging and keys ----

  function markNear(x) {
    let nearest = null;
    for (const mark of marks) {
      const away = Math.abs(mark.x - x);
      if (away <= MARK_REACH && (!nearest || away < Math.abs(nearest.x - x))) nearest = mark;
    }
    return nearest;
  }

  function pointerX(event) {
    return event.clientX - ui.track.getBoundingClientRect().left;
  }

  ui.track.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || !span()) return;
    const x = pointerX(event);
    dragFrom = x;
    ui.track.setPointerCapture(event.pointerId);
    pause();
    // A click on a mark goes to its first event's own loop; anywhere else, to the loop under it.
    const mark = markNear(x);
    seek(mark ? mark.events[0].loop : loopAt(nanosAt(x)));
  });
  ui.track.addEventListener("pointermove", (event) => {
    if (dragFrom === null) return;
    const x = Math.min(width, Math.max(0, pointerX(event)));
    if (Math.abs(x - dragFrom) < 3) return; // still the click
    dragFrom = -Infinity;
    seek(loopAt(nanosAt(x)));
  });
  const dropped = () => (dragFrom = null);
  ui.track.addEventListener("pointerup", dropped);
  ui.track.addEventListener("pointercancel", dropped);
  ui.track.addEventListener("keydown", (event) => {
    const held = span();
    if (!held) return;
    const loopsASecond = 1e9 / held.loopNanos;
    const moves = { Home: () => seek(held.oldest), End: goLive,
                    PageUp: () => step(Math.round(loopsASecond)),
                    PageDown: () => step(-Math.round(loopsASecond)) };
    if (!moves[event.key]) return;
    event.preventDefault();
    moves[event.key]();
  });

  /**
   * The strip's keys. They move only what the page shows, and they stand aside for everything that
   * reaches the robot: typing in a field, a dialog, keyboard driving (which drives with the arrows
   * and L), and Esc while a simulated robot is enabled or something runs, which disables or stops
   * it (keys.js: that Esc never arrives here).
   */
  document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (!span() || ui.track.clientWidth <= 0 || typing(event.target)) return;
    if (document.querySelector("dialog[open]") || driving().keyboard.on) return;
    const at = cursor();
    switch (event.key) {
      case "ArrowLeft":
        if (event.shiftKey) toEvent(-1);
        else step(-1);
        break;
      case "ArrowRight":
        if (event.shiftKey) toEvent(1);
        else if (!at.live) step(1);
        break;
      case " ":
        // On a button or a link, Space presses it, as it always does.
        if (event.target.closest && event.target.closest("button, a, summary")) return;
        if (at.live) return;
        togglePlay();
        break;
      case "Escape":
        if (at.live || running().length > 0) return;
        goLive();
        break;
      case "l":
      case "L":
        if (at.live) return;
        goLive();
        break;
      default:
        return;
    }
    event.preventDefault();
  });

  onCursor(draw);
  onEvents(draw);
  onDrive(draw);
  onChange((patch, snapshot) => {
    if (snapshot || "robotView" in patch || "simulator" in patch) draw();
  });
  new ResizeObserver(draw).observe(ui.track);
  draw();
  return { element };
}
