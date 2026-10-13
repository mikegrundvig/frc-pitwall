// World: the robot program's scenes, drawn in 3D: what the code believes about where things are,
// such as the field, the robot on it, and what its cameras see. Offered only when the robot program
// adds a scene (pitwall.scenes()); Pitwall without one has no such view.
//
// The robot describes a scene once (frames and shapes, in layers) and then sends only what moves,
// and only while this view is on screen: the page names the scene it shows on its event stream
// (core.js's showViews). Hiding the view, or the tab, stops it. three.js and the renderer load the
// first time the view is shown (scene-render.js), so a page that never opens it never loads them.
//
// It draws the moment the page's cursor is on (cursor.js), as the Robot view does: the robot's
// newest loop while the cursor is live, and otherwise that loop's scene whole, asked of the robot
// and kept (GET /api/scenes/<id>/state?index=, loop-store.js). The time strip and the list of
// events along its bottom (timeline.js, event-list.js) move the cursor, with the same keys as
// under the Robot view. A layer the robot can draw only live says so, and is hidden meanwhile.
//
// A click on a shape the robot made inspectable asks the robot what it is, in the loop shown (GET
// /api/scenes/<id>/inspect/<shape>, with ?index= when the cursor is not live), and shows the answer
// beside the drawing, ending in what can be done about it: the page's actions, run as from Pit.
//
// The legend says what each kind of mark means, layer by layer, with a sample drawn from the very
// style the layer draws in, and it is where a layer is switched on and off. What is switched, and
// whether the legend is open, is kept in this browser.

import { robot, onChange, onConnection, showViews, send, h, replace, say } from "./core.js";
import { cursor, onCursor, span, noteTime } from "./cursor.js";
import { makeLoopStore } from "./loop-store.js";
import { makeTimeline } from "./timeline.js";
import { makeEventList } from "./event-list.js";

const SVG = "http://www.w3.org/2000/svg";

/** How many loops each side of the cursor's are fetched ahead: where a step goes next. */
const BESIDE = 3;

/** Where this browser keeps which layers are switched, by scene and layer, and the legend's fold. */
const LAYERS_KEY = "pitwall.world.layers";
const LEGEND_KEY = "pitwall.world.legend";

let visible = false;
let sceneId = null; // the scene shown
let renderer = null; // the renderer, once loaded
let styles = null; // how a style is drawn, from the renderer's module: {TOKENS, tokenOf, isDashed}
let loading = null; // loading it
let unavailable = ""; // why it can't draw, if it can't
let describedAs = ""; // the description the renderer was built from
let following = false;
let inspected = null; // {id, layer} of the shape whose details are shown
let inspection = null; // what the robot said of it
let clickedX = null; // where it was clicked, in CSS pixels from the canvas's left
let wasOffered = false;
let loops = null; // the scene's earlier loops, as the robot answered for them
let loopsOf = ""; // the scene and the session those loops are of
let drawnAt = null; // the loop the drawing is of while the cursor is not live
let liveOnly = []; // the layers that have no earlier loop to show, as the robot last said
let hovering = null; // a frame in which the shape under the pointer is looked for
const chosen = readKept(LAYERS_KEY, {}); // "scene/layer" -> shown, as chosen in this browser
const ui = {};

function scenes() {
  return (robot.doc && robot.doc.scenes) || {};
}

function scene() {
  return sceneId ? scenes()[sceneId] || null : null;
}

/** Offered once the robot program has added a scene. */
function offered() {
  return Object.keys(scenes()).length > 0;
}

function mount(view) {
  ui.title = h("h1", { class: "view-title" }, "World");
  ui.pick = h("select", { class: "world-scene", "aria-label": "Scene", hidden: true,
                          onchange: () => choose(ui.pick.value) });
  ui.top = h("button", { type: "button", class: "chip", "aria-pressed": "true",
                         onclick: () => camera("top") }, "Top");
  ui.around = h("button", { type: "button", class: "chip", "aria-pressed": "false",
                            onclick: () => camera("around") }, "3D");
  ui.follow = h("button", { type: "button", class: "chip", "aria-pressed": "false", hidden: true,
                            onclick: toggleFollow }, "Follow");
  ui.fit = h("button", { type: "button", class: "chip", onclick: () => renderer && renderer.fit() },
    "Fit");
  ui.canvas = h("canvas", { class: "world-gl", tabindex: "0",
                            "aria-label": "The scene, drawn in 3D. Drag to move, scroll to zoom." });
  ui.message = h("p", { class: "world-message", hidden: true });
  ui.liveOnly = h("p", { class: "world-live-only", role: "status", hidden: true });
  ui.legendRows = h("ul", { class: "world-legend-rows" });
  ui.legend = h("details", { class: "world-legend", open: readKept(LEGEND_KEY, true) },
    h("summary", null, "Legend"), ui.legendRows);
  ui.legend.addEventListener("toggle", () => {
    keep(LEGEND_KEY, ui.legend.open);
    // Folded, the legend gives its width back to the drawing, which is fitted to what it has.
    if (renderer && visible) renderer.resize();
  });
  ui.details = h("aside", { class: "world-details card", hidden: true, "aria-live": "polite" });
  ui.stage = h("div", { class: "world-stage" }, ui.canvas, ui.message, ui.liveOnly);
  view.classList.add("world");
  view.append(
    h("div", { class: "world-bar" }, ui.title, ui.pick,
      h("div", { class: "world-cameras", role: "group", "aria-label": "Camera" }, ui.top,
        ui.around),
      ui.follow, ui.fit),
    h("div", { class: "world-main" }, ui.stage,
      h("div", { class: "world-side" }, ui.legend, ui.details)),
    h("div", { class: "dock" }, makeEventList().element, makeTimeline().element));

  let down = null;
  ui.canvas.addEventListener("pointerdown", (event) => {
    down = { x: event.clientX, y: event.clientY };
  });
  ui.canvas.addEventListener("pointerup", (event) => {
    // A click, not a drag: the controls turn and pan on drags.
    if (down && Math.hypot(event.clientX - down.x, event.clientY - down.y) < 6) {
      clicked(event.clientX, event.clientY);
    }
    down = null;
  });
  // The shape under the pointer shows what it reveals (a camera's name and view) while it's there.
  ui.canvas.addEventListener("pointermove", (event) => {
    if (!renderer || down || hovering !== null) return;
    hovering = requestAnimationFrame(() => {
      hovering = null;
      const over = renderer.pick(event.clientX, event.clientY);
      renderer.hover(over ? over.id : null);
    });
  });
  ui.canvas.addEventListener("pointerleave", () => renderer && renderer.hover(null));
  new ResizeObserver(() => renderer && visible && renderer.resize()).observe(ui.stage);
  const recolor = () => renderer && renderer.recolor();
  document.addEventListener("pitwall-theme", recolor);
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", recolor);
  onChange(changed);
  onCursor(() => {
    if (visible) drawTheMoment();
  });
  // Run needs the robot there, as on Pit: the details' button follows the link.
  onConnection(() => inspection && showDetails());
}

/** What this browser keeps under a key, or what to take when it keeps nothing readable. */
function readKept(key, otherwise) {
  try {
    const kept = JSON.parse(localStorage.getItem(key));
    return kept === null || typeof kept !== typeof otherwise ? otherwise : kept;
  } catch (error) {
    return otherwise;
  }
}

function keep(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    // Kept for this page only: storage is off in this browser.
  }
}

/** The views this one is sent: its scene, and the session's newest loop, which the cursor spans. */
function shownViews() {
  const session = robot.doc && robot.doc.robotView && robot.doc.robotView.view;
  return [sceneId, session].filter(Boolean);
}

function show() {
  visible = true;
  chooseScene();
  showViews(shownViews());
  load().then(redraw);
}

function hide() {
  visible = false;
  showViews([]);
}

/** #world/<scene> shows that scene. */
function route(rest) {
  if (rest && scenes()[rest]) choose(rest);
}

/** Keeps the scene shown, or picks the first when it's gone (or none was chosen). */
function chooseScene() {
  const ids = Object.keys(scenes());
  if (!sceneId || !ids.includes(sceneId)) sceneId = ids[0] || null;
  ui.pick.hidden = ids.length < 2;
  if (ids.length >= 2) {
    replace(ui.pick, ids.map((id) =>
      h("option", { value: id, selected: id === sceneId }, scenes()[id].title || id)));
  }
}

function choose(id) {
  if (id === sceneId) return;
  sceneId = id;
  describedAs = "";
  closeDetails();
  if (visible) {
    showViews(shownViews());
    redraw();
  }
}

// ---- Keeping up with the robot program ----

function changed(patch, snapshot, replaced) {
  if (snapshot || "scenes" in patch) {
    const now = offered();
    if (now !== wasOffered) {
      wasOffered = now;
      document.dispatchEvent(new Event("pitwall-offered"));
    }
  }
  if (!visible || !renderer) return;
  if (snapshot) {
    chooseScene();
    // A snapshot may be the first to say there is a session: name it then, to be sent its span.
    showViews(shownViews());
    redraw();
    if (inspection) showDetails();
    return;
  }
  // An action's phase can change in any patch, one carrying the scene's state too (a calibration
  // asks for driving while it runs): the details' button says what it does, so it's kept current.
  if (inspection && "actions" in patch) showDetails();
  const mine = patch.scenes && sceneId ? patch.scenes[sceneId] : null;
  if (!mine) return;
  if ("layers" in mine || "title" in mine || "focus" in mine) {
    redraw();
    return;
  }
  // The newest loop is drawn only while the cursor is live: an earlier loop stays as it is.
  if ("state" in mine && cursor().live) {
    renderer.apply(scene().state || {}, replaced ? null : mine.state);
  }
}

/** Draws the scene afresh: described again if its description changed, then all of its state. */
function redraw() {
  if (!visible) return;
  if (!renderer) {
    showMessage(unavailable || (offered() ? "Loading the 3D view…" : "No scene."));
    // Without the drawing the legend still says what the scene holds, and keeps what is switched.
    if (styles && scene()) drawLegend();
    return;
  }
  const now = scene();
  if (!now) {
    showMessage("The robot program has no scene to show.");
    return;
  }
  showMessage("");
  ui.title.textContent = now.title || "World";
  const description = JSON.stringify({ focus: now.focus, layers: now.layers });
  if (description !== describedAs) {
    describedAs = description;
    renderer.describe(now);
    for (const [id, layer] of Object.entries(now.layers || {})) {
      renderer.showLayer(id, isShown(id, layer));
    }
    drawLegend();
    ui.follow.hidden = !now.focus;
    renderer.follow(following && now.focus ? now.focus : null);
  }
  drawnAt = null;
  drawTheMoment();
  renderer.resize();
}

// ---- The moment the cursor is on ----

/** The scene's earlier loops, kept for as long as the scene and the robot's session are the same. */
function earlierLoops() {
  const held = span();
  const of = sceneId + "@" + (held ? held.startedNanos : "");
  if (!loops || of !== loopsOf) {
    const id = sceneId;
    loops = makeLoopStore((index) =>
      "/api/scenes/" + encodeURIComponent(id) + "/state?index=" + index);
    loopsOf = of;
  }
  return loops;
}

/**
 * Draws the moment the cursor is on: the newest loop as the robot sends it, or an earlier loop
 * whole, once the robot answers for it. The details shown are asked again, of that loop.
 */
function drawTheMoment() {
  if (!visible || !renderer || !scene()) return;
  const at = cursor();
  const held = span();
  if (at.live || !held) {
    const wasEarlier = drawnAt !== null || liveOnly.length > 0;
    drawnAt = null;
    showLiveOnly([]);
    renderer.apply(scene().state || {}, null);
    if (wasEarlier && inspected) ask();
    return;
  }
  const near = [];
  for (let by = 1; by <= BESIDE; by++) near.push(at.index + by, at.index - by);
  earlierLoops().want(at.index, (state) => {
    if (state) noteTime(state.index, state.timestampNanos);
    const now = cursor();
    // Still wanted, and still held by the session: else the cursor has moved, and asks again.
    if (!visible || !state || now.live || now.index !== at.index || !renderer) return;
    drawnAt = at.index;
    showLiveOnly(state.liveOnly || []);
    renderer.apply(state, null);
    if (inspected) ask();
  }, near.filter((index) => index >= held.oldest && index <= held.newest));
}

/**
 * Hides the layers that have no earlier loop to show while the cursor is on one, and says which:
 * a layer drawn from the robot's live objects is the present only.
 */
function showLiveOnly(layers) {
  const now = scene();
  const before = liveOnly;
  liveOnly = layers;
  for (const id of new Set([...before, ...layers])) {
    const layer = now && now.layers ? now.layers[id] : null;
    if (layer) renderer.showLayer(id, !layers.includes(id) && isShown(id, layer));
  }
  const titles = layers.map((id) => (now.layers[id] && now.layers[id].title) || id);
  ui.liveOnly.hidden = titles.length === 0;
  ui.liveOnly.textContent = titles.length === 0 ? ""
    : "Drawn live only, so hidden at an earlier moment: " + titles.join(", ") + ".";
  if (before.join() !== layers.join()) drawLegend();
}

// ---- The legend, which is also where a layer is switched ----

/** Whether a layer is shown: as chosen in this browser, else as the robot program starts it. */
function isShown(id, layer) {
  const kept = chosen[sceneId + "/" + id];
  return typeof kept === "boolean" ? kept : layer.shown !== false;
}

/**
 * The legend: a row for each layer of the scene, pressed while the layer is shown, and under a
 * shown layer what each kind of its marks means. A layer that is hidden explains nothing, so the
 * legend never describes what is not on screen.
 */
function drawLegend() {
  const now = scene();
  const rows = [];
  for (const [id, layer] of Object.entries((now && now.layers) || {})) {
    const hiddenNow = liveOnly.includes(id);
    const shown = isShown(id, layer);
    const toggle = h("button", { type: "button", class: "world-layer", "aria-pressed": String(shown),
                                 "data-layer": id, disabled: hiddenNow,
                                 title: shown ? "Hide this layer" : "Show this layer" },
      h("span", { class: "world-check", "aria-hidden": "true" }, shown ? "✓" : ""),
      h("span", null, layer.title || id),
      hiddenNow ? h("span", { class: "meta" }, " (live only)") : null);
    toggle.addEventListener("click", () => switchLayer(id, layer, !shown));
    const meanings = shown && !hiddenNow ? (layer.legend || []).map((entry) =>
      h("li", { class: "world-meaning" }, sample(entry), h("span", null, entry.text))) : [];
    rows.push(h("li", null, toggle,
      meanings.length ? h("ul", { class: "world-meanings" }, meanings) : null));
  }
  ui.legend.hidden = rows.length === 0;
  ui.legendRows.replaceChildren(...rows);
}

function switchLayer(id, layer, shown) {
  chosen[sceneId + "/" + id] = shown;
  keep(LAYERS_KEY, chosen);
  if (renderer) renderer.showLayer(id, shown);
  if (!shown && inspected && inspected.layer === id) closeDetails();
  drawLegend();
  const pressed = ui.legendRows.querySelector('[data-layer="' + id + '"]');
  if (pressed) pressed.focus(); // the row was drawn again: the keyboard stays on it
}

/**
 * A sample of a mark, drawn from the style the layer draws it in: the style's own color token, and
 * dashed if the style is. So the sample and the drawing cannot differ.
 */
function sample(entry) {
  const color = "var(" + styles.TOKENS[styles.tokenOf(entry.style)] + ")";
  const dash = styles.isDashed(entry.style) ? "4 3" : null;
  const made = document.createElementNS(SVG, "svg");
  made.setAttribute("class", "world-sample");
  made.setAttribute("viewBox", "0 0 30 14");
  made.setAttribute("aria-hidden", "true");
  const add = (tag, attributes, text) => {
    const part = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attributes)) {
      if (value !== null) part.setAttribute(name, value);
    }
    if (text) part.textContent = text;
    made.append(part);
  };
  const stroke = { stroke: color, "stroke-width": 2, fill: "none", "stroke-dasharray": dash };
  switch (entry.mark) {
    case "outline":
      add("rect", { x: 3, y: 2, width: 24, height: 10, rx: 2, ...stroke });
      break;
    case "fill":
      add("rect", { x: 3, y: 2, width: 24, height: 10, rx: 2, ...stroke, fill: color,
                    "fill-opacity": 0.25 });
      break;
    case "arrow":
      add("line", { x1: 2, y1: 7, x2: 22, y2: 7, ...stroke });
      add("path", { d: "M 21 2.5 L 29 7 L 21 11.5 Z", fill: color });
      break;
    case "text":
      add("text", { x: 15, y: 11, "text-anchor": "middle", fill: color, "font-size": 11,
                    "font-weight": 600 }, "7");
      break;
    default:
      add("line", { x1: 2, y1: 7, x2: 28, y2: 7, ...stroke });
  }
  return made;
}

// ---- The renderer ----

/** Loads three.js and the renderer, once: or says why this browser can't draw them. */
function load() {
  if (loading) return loading;
  loading = import("./scene-render.js").then((module) => {
    styles = module;
    if (!module.supported()) {
      unavailable = "This browser can't draw 3D: it has no WebGL 2. Everything else in Pitwall"
          + " works; try another browser, or turn on hardware acceleration.";
      return;
    }
    try {
      renderer = new module.SceneRenderer(ui.canvas, () => {
        showMessage("The browser took the 3D drawing away (its graphics were reset). Reload the"
            + " page to draw it again.");
      });
    } catch (error) {
      unavailable = "The 3D view couldn't start: " + error.message;
    }
  }).catch((error) => {
    unavailable = "The 3D view couldn't load: " + error.message;
  });
  return loading;
}

function showMessage(text) {
  ui.message.textContent = text;
  ui.message.hidden = !text;
}

function camera(view) {
  if (!renderer) return;
  renderer.use(view);
  ui.top.setAttribute("aria-pressed", String(view === "top"));
  ui.around.setAttribute("aria-pressed", String(view !== "top"));
}

function toggleFollow() {
  const now = scene();
  following = !following && Boolean(now && now.focus);
  ui.follow.setAttribute("aria-pressed", String(following));
  if (renderer) renderer.follow(following ? now.focus : null);
}

// ---- Inspecting ----

async function clicked(x, y) {
  if (!renderer) return;
  const picked = renderer.pick(x, y);
  if (!picked) {
    closeDetails();
    return;
  }
  inspected = picked;
  inspection = null;
  clickedX = x - ui.canvas.getBoundingClientRect().left;
  renderer.select(picked.id);
  showDetails("Asking the robot…");
  ask();
}

/**
 * Asks the robot what the shape inspected is, in the loop the drawing is of: the newest while the
 * cursor is live, and otherwise the cursor's. Asked again whenever the drawing becomes another
 * loop's, so the details always say the moment shown.
 */
async function ask() {
  const picked = inspected;
  const at = drawnAt;
  const result = await send("GET", "/api/scenes/" + encodeURIComponent(sceneId) + "/inspect/"
      + encodeURIComponent(picked.id) + (at === null ? "" : "?index=" + at));
  if (inspected !== picked || drawnAt !== at) return;
  if (!result.ok) {
    inspection = null;
    showDetails(result.problem.detail || "The robot has nothing to say about it now.");
    return;
  }
  inspection = result.body;
  showDetails();
}

/** The details panel: what the robot said of the shape clicked, ending in what can be done. */
function showDetails(waiting = "") {
  if (!inspected) return;
  detailsShown(true);
  const close = h("button", { type: "button", class: "link world-close", "aria-label": "Close",
                              onclick: closeDetails }, "✕");
  if (!inspection) {
    replace(ui.details, h("div", { class: "world-details-head" },
      h("h2", null, inspected.id), close), h("p", { class: "muted" }, waiting));
    return;
  }
  const details = h("dl", { class: "world-facts" },
    inspection.details.map((detail) => [h("dt", null, detail.label), h("dd", null, detail.value)]));
  const actions = inspection.actions.map((asked) => {
    const now = (robot.doc && robot.doc.actions && robot.doc.actions[asked.name]) || asked;
    const running = now.phase === "RUNNING";
    // Stop is never locked; Run is, with why beside it, as on Pit.
    const locked = !running && (robot.connected ? now.locked : "Not connected to the robot.");
    return h("div", { class: "world-action" },
      h("button", { type: "button", class: running ? "danger" : "primary", disabled: Boolean(locked),
                    "data-action": asked.name, "data-run": running ? "cancel" : "start",
                    onclick: runAction },
        running ? "■ Stop" : "▶ Run"),
      h("span", null, asked.name),
      locked ? h("span", { class: "gate locked" }, "🔒 " + locked) : null);
  });
  replace(ui.details,
    h("div", { class: "world-details-head" }, h("h2", null, inspection.title), close),
    details,
    actions.length ? h("div", { class: "world-actions" }, actions) : null);
}

/**
 * Runs or stops an action from the panel, as Pit would: what the button says, its name and whether
 * it starts or stops it on the button itself. Never the opposite of what it shows: a Stop pressed
 * just as the action ended asks to cancel it, which leaves it stopped, rather than start it again.
 */
async function runAction(event) {
  const { action: name, run } = event.currentTarget.dataset;
  const stopping = run === "cancel";
  const result = await send("POST", "/api/actions", stopping ? { cancel: name } : { start: name });
  if (!result.ok) say(name + ": " + result.problem.detail, true);
  else say((stopping ? "Stopped: " : "Started: ") + name + ".");
}

function closeDetails() {
  inspected = null;
  inspection = null;
  detailsShown(false);
  if (renderer) renderer.select(null);
}

/**
 * Shows or hides the details beside the drawing, which takes or gives back their width: the
 * drawing is fitted to what it has then, keeping the point clicked in sight.
 */
function detailsShown(shown) {
  if (ui.details.hidden === !shown) return;
  ui.details.hidden = !shown;
  if (renderer && visible) renderer.refit(shown ? clickedX : null);
}

export default { id: "world", title: "World", mount, show, hide, route, offered };
