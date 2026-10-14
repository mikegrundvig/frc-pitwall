// The list of events: what happened in the session, in order, as sentences with their times, so
// the order things happened in can be read: "feeder told feeding" above "shooter at goal holds"
// is a shot that fed before its shooter was up. It shows the events around the page's cursor
// (cursor.js), with those of the cursor's own loop marked, and a click on one moves the cursor to
// its loop. It can show the events about one joint only, and the problems only (the page's filter,
// session-events.js), and it folds away under its title: every list on the page folds and opens
// together, and this browser keeps which.
//
// It is the right-hand column of a view's dock, beside the time strip and as tall as it, and its
// rows scroll in the height the strip gives it (pitwall.css: .dock).
//
// The sentences are the robot's own (GET /api/robot/events): nothing here words anything about the
// robot.

import { h } from "./core.js";
import { cursor, onCursor, seek, pause, span } from "./cursor.js";
import { shownEvents, eventFilter, setEventFilter, onEvents } from "./session-events.js";

/** How many events it lists each side of the cursor. */
const EACH_SIDE = 40;

/** Where this browser keeps whether the lists are open. */
const OPEN_KEY = "pitwall.events.open";

const lists = new Set(); // every list made: each a function that shows it open or folded
let opened = readOpen();
let ids = 0;

function readOpen() {
  try {
    return localStorage.getItem(OPEN_KEY) !== "false";
  } catch (error) {
    return true; // storage is off in this browser: open, so nothing goes missing
  }
}

/** Opens or folds every list, as someone asked, and keeps it. */
function setOpen(now) {
  opened = now;
  try {
    localStorage.setItem(OPEN_KEY, String(opened));
  } catch (error) {
    // Kept for this page only: storage is off in this browser.
  }
  for (const show of lists) show();
}

/** Makes a list of events: {element}. */
export function makeEventList() {
  const ui = {};
  let listed = ""; // what the list was last built from
  let frame = null;

  ui.count = h("span", { class: "meta el-count" });
  ui.about = h("button", { type: "button", class: "chip el-about", hidden: true,
                           onclick: () => setEventFilter({ about: "" }) });
  ui.problems = h("button", { type: "button", class: "chip", "aria-pressed": "false",
    onclick: () => setEventFilter({ problemsOnly: !eventFilter().problemsOnly }) }, "Problems only");
  const bodyId = "events-" + ++ids;
  ui.list = h("ol", { class: "el-list" });
  ui.empty = h("p", { class: "meta el-empty" });
  ui.toggle = h("button", { type: "button", class: "link el-toggle", "aria-controls": bodyId,
                            onclick: () => setOpen(!opened) },
    h("span", { class: "el-title" }, "Events"), ui.count);
  ui.filter = h("div", { class: "el-filter" }, ui.about, ui.problems);
  ui.body = h("div", { class: "el-body", id: bodyId },
    h("p", { class: "meta el-hint" }, "A joint's name shows its events only."),
    ui.empty, ui.list);
  const element = h("section", { class: "el", "aria-label": "Events" },
    h("div", { class: "el-head" }, ui.toggle, ui.filter), ui.body);

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
    if (!held || !opened) return;
    const filter = eventFilter();
    const at = cursor();
    const events = shownEvents();
    // The first event of the cursor's loop, or of a later one: the cursor is just before it.
    let here = events.findIndex((event) => event.loop >= at.index);
    if (here < 0) here = events.length;
    const from = Math.max(0, here - EACH_SIDE);
    const near = events.slice(from, here + EACH_SIDE);
    ui.about.hidden = !filter.about;
    ui.about.textContent = "About " + filter.about + " ✕";
    ui.about.setAttribute("aria-label", "About " + filter.about + ": show every event");
    ui.problems.setAttribute("aria-pressed", String(filter.problemsOnly));
    ui.count.textContent = events.length === 1 ? "1 event" : events.length + " events";
    ui.empty.hidden = events.length > 0;
    ui.empty.textContent = filter.about || filter.problemsOnly
      ? "No such event in the session's memory." : "Nothing has happened yet.";
    // Built again only when it would read differently: live, the cursor's loop changes 20 times a
    // second, and the list doesn't.
    const ofThisLoop = near.filter((event) => event.loop === at.index).length;
    const built = [near.map((event) => event.number).join(","), here, ofThisLoop,
                   at.live ? "live" : at.index].join("|");
    if (built === listed) return;
    listed = built;
    let marked = false;
    const rows = [];
    for (const event of near) {
      if (!marked && event.loop > at.index) {
        rows.push(nowRow(at));
        marked = true;
      }
      if (event.loop === at.index) marked = true;
      rows.push(row(event, at, held));
    }
    if (!marked && near.length) rows.push(nowRow(at));
    ui.list.replaceChildren(...rows);
    const current = ui.list.querySelector("[aria-current], .el-now");
    if (current) ui.list.scrollTop = current.offsetTop - ui.list.clientHeight / 2;
  }

  /** One event: its time from the session's start, and its sentence; a click goes to its loop. */
  function row(event, at, held) {
    const problem = event.severity === "PROBLEM";
    const time = ((event.timestampNanos - held.startedNanos) / 1e9).toFixed(2) + " s";
    return h("li", { class: problem ? "problem" : null },
      h("button", { type: "button", class: "link el-row",
                    "aria-current": event.loop === at.index ? "true" : null,
                    title: "Show loop " + event.loop,
                    onclick: () => {
                      pause();
                      seek(event.loop);
                    } },
        h("span", { class: "el-time num" }, time),
        h("span", { class: "el-mark", "aria-hidden": "true" }, problem ? "✗" : "•"),
        h("span", { class: "el-said" }, (problem ? "Problem: " : "") + event.sentence)));
  }

  /** Where the cursor is, between the events before it and those after. */
  function nowRow(at) {
    return h("li", { class: "el-now" }, at.live ? "now" : "the moment shown, loop " + at.index);
  }

  const show = () => {
    element.classList.toggle("folded", !opened);
    ui.toggle.setAttribute("aria-expanded", String(opened));
    ui.filter.hidden = !opened;
    ui.body.hidden = !opened;
    listed = "";
    draw();
  };
  lists.add(show);
  show();
  onCursor(draw);
  onEvents(draw);
  draw();
  return { element };
}
