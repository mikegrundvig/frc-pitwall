// Pit's devices: a card for each device the robot reports on (the venue it plays on, say), saying
// whether it's there and how it is, and ending in what can be done about it: a file to download
// from it or send to it, a choice to make, or a button to press, some asking for words first. The robot program asks the device; this page asks
// only the robot (/api/devices/...), but for a card's live picture, which it loads from the device
// itself (picture.js). A card may show a live configuration's values too, tuned there as in Tune:
// a camera's exposure and gain beside its picture.

import { robot, onChange, onConnection, api, go, h, replace, say } from "./core.js";
import { onEdits } from "./edits.js";
import { makeRow, updateRow } from "./rows.js";
import { makePicture, dropPicture, updatePicture } from "./picture.js";

const MARKS = { ok: ["✓", "ok"], note: ["•", "none"], warning: ["▲", "warn"], problem: ["✗", "bad"] };

let section;
let grid;
let built = "";
const cards = new Map(); // device name -> its card's parts

/** Mounts the devices' section in a view: hidden while the robot reports no devices. */
export function mountDevices(parent) {
  grid = h("div", { class: "devices" });
  section = h("section", { class: "devices-section", hidden: true, "aria-label": "Devices" },
    h("h2", { class: "devices-title" }, "Devices"),
    h("p", { class: "meta" }, "Each device the robot program asks after, as of its last answer. "
        + "The robot fetches downloads from the device; this page reaches a device only for a "
        + "card's live picture."),
    grid);
  parent.append(section);
  onChange((patch, snapshot) => {
    if (snapshot || "devices" in patch || "tunables" in patch) render(snapshot);
    else if ("values" in patch || "tuning" in patch || "changes" in patch) updateTunes();
  });
  onEdits(updateTunes);
  onConnection(updateTunes);
}

function render(snapshot) {
  const devices = (robot.doc && robot.doc.devices) || {};
  const names = Object.keys(devices);
  section.hidden = names.length === 0;
  // Rebuilt only when the devices or what can be done from them change; updated in place after.
  const key = names.map((name) => name + ":" + JSON.stringify([devices[name].kind,
    devices[name].downloads, devices[name].uploads, devices[name].presses,
    (devices[name].choices || []).map((choice) => choice.id), "picture" in devices[name],
    tunedPaths(devices[name].tuning)])).join("\n");
  if (snapshot || key !== built) {
    built = key;
    for (const parts of cards.values()) if (parts.picture) dropPicture(parts.picture);
    cards.clear();
    grid.replaceChildren(...names.map((name) => makeCard(name, devices[name])));
  }
  for (const [name, device] of Object.entries(devices)) update(cards.get(name), device);
}

/** The values of the live configuration a card tunes, by its name, in the robot's order. */
function tunedPaths(config) {
  if (!config || !robot.doc) return [];
  return Object.keys(robot.doc.tunables).filter((path) => robot.doc.tunables[path].config === config);
}

/** Every card's values as they are now: on the robot, staged, and committed. */
function updateTunes() {
  for (const parts of cards.values()) parts.rows.forEach(updateRow);
}

function makeCard(name, device) {
  const parts = {
    state: h("span", { class: "dstate" }),
    lines: h("ul", { class: "slist" }),
    choices: h("div", { class: "dchoices" }),
    shownChoices: "",
  };
  const actions = [];
  for (const download of device.downloads || []) {
    const button = h("button", { type: "button" }, download.label);
    button.addEventListener("click", () => fetchFile(button,
      "/api/devices/" + name + "/downloads/" + download.id, name + " " + download.label));
    actions.push(button);
  }
  for (const upload of device.uploads || []) {
    const input = h("input", { type: "file", hidden: true });
    const button = h("button", { type: "button", onclick: () => input.click() }, upload.label);
    input.addEventListener("change", () => {
      const file = input.files && input.files[0];
      input.value = "";
      if (file) send(button, "/api/devices/" + name + "/uploads/" + upload.id, file, upload.label);
    });
    actions.push(button, input);
  }
  for (const press of device.presses || []) {
    const path = "/api/devices/" + name + "/presses/" + press.id;
    if (!press.asks) {
      const button = h("button", { type: "button" }, press.label);
      button.addEventListener("click", () => send(button, path, { text: "" }, press.label));
      actions.push(button);
      continue;
    }
    // A button that asks for words first: what's typed goes with it, and stays until it's taken.
    const input = h("input", { type: "text", placeholder: press.asks, "aria-label": press.asks });
    const button = h("button", { type: "button" }, press.label);
    // What's typed is cleared once the robot takes it, and kept to fix when it refuses.
    button.addEventListener("click", async () => {
      if (await send(button, path, { text: input.value }, press.label)) input.value = "";
    });
    actions.push(h("label", { class: "dchoice" }, input, button));
  }
  // The values it tunes, each a row as in Tune, its edits applied from the bar as there.
  // What a value means is in Tune, where its name leads.
  const explain = (row, how) => {
    const value = robot.doc.tunables[row.path];
    if (how !== "focus" && value) go("#tune/" + value.config + "/" + value.field);
  };
  parts.rows = tunedPaths(device.tuning).map((path) => makeRow(path, "pit", { select: explain }));
  parts.rows.forEach(updateRow);
  const tune = parts.rows.length > 0
    && h("div", { class: "rowlist dtune", "aria-label": name + ": tune" },
      parts.rows.map((row) => row.el));
  const about = [parts.lines, tune, parts.choices, h("div", { class: "acts" }, actions)];
  // A card with a picture has it first, its lines and values beside it where there's room.
  let body = about;
  if ("picture" in device) {
    parts.picture = makePicture(name);
    body = h("div", { class: "dbody" }, parts.picture.el, h("div", { class: "dabout" }, about));
  }
  parts.el = h("section", { class: "card device" + (parts.picture ? " pictured" : ""),
                            "aria-label": name },
    h("h3", { class: "dhead" }, h("span", { class: "mono" }, name),
      h("span", { class: "tag plain" }, device.kind), parts.state),
    body);
  parts.name = name;
  cards.set(name, parts);
  return parts.el;
}

function update(parts, device) {
  if (!parts) return;
  if (parts.picture) updatePicture(parts.picture, device.picture);
  const [mark, tone] = MARKS[device.level] || MARKS.note;
  replace(parts.state, h("span", { class: "sev " + tone, "aria-hidden": "true" }, mark),
    " " + device.state);
  parts.el.classList.toggle("problem", device.level === "problem");
  replace(parts.lines, (device.lines || []).map(lineItem));
  // A choice's options are rebuilt only when they or what's chosen change, so a pick being made
  // isn't swept away by the next update.
  const choices = device.choices || [];
  const shown = JSON.stringify(choices);
  if (shown !== parts.shownChoices) {
    parts.shownChoices = shown;
    replace(parts.choices, choices.map((choice) => makeChoice(parts.name, choice)));
  }
}

/**
 * One line of a card as the page draws it, wherever it is drawn: its level's mark (never its color
 * alone), its label and what it says. The Sim panel's auto card draws its lines with this too, so
 * a warning looks the same there as on Pit.
 */
export function lineItem(line) {
  const [mark, tone] = MARKS[line.level] || MARKS.note;
  return h("li", null, h("span", { class: "sev " + tone, "aria-hidden": "true" }, mark),
    h("span", { class: "k" }, line.label), h("span", null, line.value));
}

/**
 * A choice on a card: its options, what's chosen now, and a button to choose another. The Sim
 * panel shows the auto's with the same one (sim-auto.js).
 */
export function makeChoice(name, choice) {
  const select = h("select", { "aria-label": choice.label },
    choice.options.map((option) => h("option", { value: option.value }, option.label)));
  select.value = choice.current;
  const button = h("button", { type: "button" }, "Choose");
  button.disabled = true;
  select.addEventListener("change", () => { button.disabled = select.value === choice.current; });
  button.addEventListener("click", () => send(button,
    "/api/devices/" + name + "/choices/" + choice.id, { value: select.value }, choice.label));
  return h("label", { class: "dchoice" }, h("span", { class: "k" }, choice.label), select, button);
}

/**
 * Sends a file, a choice or a press to the robot, which takes it or says why not. Either way the
 * page says what happened; the card itself shows the result at its next update.
 *
 * @return whether the robot took it
 */
async function send(button, path, body, what) {
  button.disabled = true;
  try {
    const response = await api("POST", path, body);
    const answer = await response.json().catch(() => null);
    if (!response.ok) {
      say(what + ": " + (answer && answer.detail ? answer.detail : response.statusText), true);
      return false;
    }
    say(what + ": " + (answer && answer.message ? answer.message : "done."));
    return true;
  } catch (error) {
    say(what + ": the robot isn't answering.", true);
    return false;
  } finally {
    button.disabled = false;
  }
}

/**
 * Fetches a file through the robot and saves it. A device that doesn't answer is said so here,
 * rather than saved as a file holding the robot's reason.
 */
async function fetchFile(button, path, what) {
  button.disabled = true;
  const was = button.textContent;
  button.textContent = "Fetching…";
  try {
    const response = await api("GET", path);
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      say(what + ": " + (problem && problem.detail ? problem.detail : response.statusText), true);
      return;
    }
    const blob = await response.blob();
    const disposition = response.headers.get("Content-Disposition") || "";
    const named = /filename="([^"]+)"/.exec(disposition);
    save(blob, named ? named[1] : "download");
    say(what + ": saved.");
  } catch (error) {
    say(what + ": the robot isn't answering.", true);
  } finally {
    button.disabled = false;
    button.textContent = was;
  }
}

function save(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = h("a", { href: url, download: name, hidden: true });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
