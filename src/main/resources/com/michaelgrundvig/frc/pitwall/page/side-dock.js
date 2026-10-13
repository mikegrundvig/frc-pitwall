// The side dock: a place beside every view where a panel stays open while the views change. On a
// wide screen it is a column, and the view reflows beside it; on a narrow one it lies over the
// bottom of the page, and closes with one press. It belongs to the page, not to any view.
//
// A panel registers itself (addPanel) and the dock does the rest: a button for it in the state
// bar, opening and closing, the width, and remembering in this browser which panel was open and
// how wide. One panel shows at a time. The Sim panel (sim-panel.js) is the first; a second, such
// as a compact Robot view, would register the same way and get its own button.
//
// P opens and closes it, from any view, unless a field has the focus.

import { h, byId, typing } from "./core.js";

/** Where this browser keeps the dock: {open: a panel's id or null, last, width}. */
const KEPT = "pitwall.side";

const MIN_WIDTH = 300;
const MAX_WIDTH = 720;
const DEFAULT_WIDTH = 380;

/** How far an arrow key moves the dock's edge, in pixels. */
const NUDGE = 24;

const panels = new Map(); // id -> {panel, button, body, mounted}
const kept = read();
let shown = null; // the id of the panel on screen
let ui = null;

function read() {
  const state = { open: null, last: null, width: DEFAULT_WIDTH };
  try {
    const was = JSON.parse(localStorage.getItem(KEPT) || "{}");
    if (typeof was.open === "string") state.open = was.open;
    if (typeof was.last === "string") state.last = was.last;
    if (Number.isFinite(was.width)) state.width = was.width;
  } catch (error) {
    // Nothing kept, or storage is off in this browser: closed, at its usual width.
  }
  return state;
}

function keep() {
  try {
    localStorage.setItem(KEPT, JSON.stringify(kept));
  } catch (error) {
    // Kept for this page only: storage is off in this browser.
  }
}

/** Builds the dock into the page: its head, its edge to drag, and the place for a panel. */
export function mountDock() {
  ui = { side: byId("side"), buttons: byId("bar-panels") };
  ui.title = h("h2", { class: "side-title", tabindex: "-1" });
  ui.close = h("button", { type: "button", class: "link side-close", onclick: () => close(true),
                           "aria-label": "Close the panel", "aria-keyshortcuts": "P",
                           title: "Close (P)" }, "✕");
  ui.grip = h("div", { class: "side-grip", role: "separator", tabindex: "0",
                       "aria-orientation": "vertical", "aria-label": "The panel's width",
                       "aria-valuemin": MIN_WIDTH, "aria-valuemax": MAX_WIDTH,
                       title: "Drag to resize; with the keyboard, the left and right arrows" });
  ui.body = h("div", { class: "side-body" });
  ui.side.append(ui.grip, h("div", { class: "side-head" }, ui.title, ui.close), ui.body);
  setWidth(kept.width);

  let dragging = false;
  ui.grip.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    dragging = true;
    ui.grip.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  ui.grip.addEventListener("pointermove", (event) => {
    if (dragging) setWidth(window.innerWidth - event.clientX);
  });
  const dropped = () => {
    if (!dragging) return;
    dragging = false;
    keep();
  };
  ui.grip.addEventListener("pointerup", dropped);
  ui.grip.addEventListener("pointercancel", dropped);
  ui.grip.addEventListener("keydown", (event) => {
    const by = { ArrowLeft: NUDGE, ArrowRight: -NUDGE }[event.key];
    if (!by) return;
    // The edge's own arrows: they neither drive the robot nor move the time strip.
    event.preventDefault();
    event.stopPropagation();
    setWidth(kept.width + by);
    keep();
  });

  document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.key !== "p" && event.key !== "P") return;
    if (typing(event.target) || document.querySelector("dialog[open]")) return;
    const id = shown || wanted();
    if (!id) return;
    event.preventDefault();
    toggle(id);
  });
  // A panel offered only now (the simulator answered) takes its button, and its place if it was
  // open when the page was last here.
  document.addEventListener("pitwall-offered", offer);
}

/**
 * Adds a panel to the dock: {id, title, label, offered(), mount(element), show(), hide()}. {@code
 * label} is its button's words in the state bar; {@code offered()} says whether there is such a
 * panel now (the Sim panel only in the simulator); {@code mount} builds it once, the first time it
 * is opened; {@code show} and {@code hide} say when it is on screen.
 */
export function addPanel(panel) {
  const button = h("button", { type: "button", class: "side-button", hidden: true,
                               "aria-expanded": "false", "aria-controls": "side",
                               "aria-keyshortcuts": "P", title: panel.title + " (P)",
                               onclick: () => toggle(panel.id) }, panel.label);
  panels.set(panel.id, { panel, button, body: null });
  ui.buttons.append(button);
  offer();
}

/** Says a panel's being offered may have changed: its button follows, and so does the dock. */
export function offer() {
  for (const [id, entry] of panels) {
    const offered = Boolean(entry.panel.offered());
    entry.button.hidden = !offered;
    if (!offered && shown === id) hide();
  }
  // What was open when the page was last here opens again, without taking the focus.
  if (!shown && kept.open && isOffered(kept.open)) show(kept.open, false);
}

function isOffered(id) {
  const entry = panels.get(id);
  return Boolean(entry && entry.panel.offered());
}

/** The panel P opens: the one last open, else the first offered. */
function wanted() {
  if (kept.last && isOffered(kept.last)) return kept.last;
  for (const id of panels.keys()) if (isOffered(id)) return id;
  return null;
}

/** Opens a panel by its id, as someone asked: the focus goes to its title. */
export function openPanel(id) {
  if (isOffered(id) && shown !== id) show(id, true);
}

/** Whether a panel is on screen. */
export function isOpen(id) {
  return shown === id;
}

function toggle(id) {
  if (shown === id) close(true);
  else if (isOffered(id)) show(id, true);
}

function show(id, focus) {
  if (shown && shown !== id) hide();
  const entry = panels.get(id);
  if (!entry.body) {
    entry.body = h("div", { class: "side-panel" });
    entry.panel.mount(entry.body);
  }
  shown = id;
  kept.open = kept.last = id;
  keep();
  ui.title.textContent = entry.panel.title;
  ui.side.setAttribute("aria-label", entry.panel.title);
  ui.body.replaceChildren(entry.body);
  ui.side.hidden = false;
  document.body.classList.add("with-side");
  entry.button.setAttribute("aria-expanded", "true");
  entry.panel.show();
  if (focus) ui.title.focus({ preventScroll: true });
}

/** Takes the panel off screen, keeping what is remembered: it is no longer offered. */
function hide() {
  const entry = panels.get(shown);
  if (!entry) return;
  entry.panel.hide();
  entry.button.setAttribute("aria-expanded", "false");
  shown = null;
  ui.side.hidden = true;
  document.body.classList.remove("with-side");
}

/** Closes the dock, as someone asked. The focus, if it was inside, goes back to the button. */
function close(asked) {
  const entry = panels.get(shown);
  if (!entry) return;
  const within = ui.side.contains(document.activeElement);
  hide();
  if (asked) {
    kept.open = null;
    keep();
  }
  if (within) entry.button.focus();
}

function setWidth(width) {
  const most = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, window.innerWidth - 360));
  kept.width = Math.round(Math.max(MIN_WIDTH, Math.min(most, width)));
  ui.side.style.setProperty("--side-width", kept.width + "px");
  ui.grip.setAttribute("aria-valuenow", kept.width);
}
