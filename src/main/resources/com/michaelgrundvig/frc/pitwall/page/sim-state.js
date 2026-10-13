// The simulator as this page knows it, and what the page asks of it: the one place that says
// whether this is the simulator at all, reads its Driver Station, and enables or disables the
// robot. Every control of the simulator, wherever it is mounted (the Sim view, the Sim panel, the
// state bar, a click in the World view), reads and asks here, so there is one of each.
//
// The robot program sees a Driver Station change as its next loop begins, as from a real one; the
// page shows it as the simulator answers. A paused simulator runs no loops, so it sends nothing
// new: a page opened while it's paused gets no snapshot, and asks the simulator directly instead.
//
// On a real robot none of this exists: its program has no simulator section and none of the
// routes (they answer 404), so isSimulator() is false and no control of these is ever built or
// shown. The Driver Station enables a real robot, never this page.

import { robot, onChange, onConnection, send, announce, say } from "./core.js";
import { outcome, loopRan } from "./sim-outcome.js";
import { onDrive } from "./sim-drive.js";

/** The sections of the robot's state the simulator's controls read. */
const READS = ["simulator", "components", "scenarios", "devices"];

export const MODES = [["AUTONOMOUS", "Autonomous"], ["TELEOPERATED", "Teleoperated"],
  ["UTILITY", "Utility"]];
export const STATIONS = ["RED_1", "RED_2", "RED_3", "BLUE_1", "BLUE_2", "BLUE_3"];

/** Everything changed: what a section is drawn with when it comes on screen. */
export const ALL = { station: true, faults: true, drive: true, devices: true };

/**
 * The simulator as GET /api/simulator answered, used only while no snapshot has come: a page that
 * opened while the simulator was paused gets none until a loop runs.
 */
let fallback = null;
let lastEnabled = null;
const listeners = [];

/**
 * Calls {@code listener(what)} when the simulator's state changes: {@code what} says which parts
 * did, as {station, faults, drive, devices}, so a section draws only its own; {opened} is true
 * as a place of sections comes on screen, when all of it is drawn.
 */
export function onSim(listener) {
  listeners.push(listener);
}

function tell(what) {
  for (const listener of listeners) {
    try {
      listener(what);
    } catch (error) {
      console.error(error); // one section's bug doesn't stop the others
    }
  }
}

/** The simulator's sections, from the stream; or before a snapshot, as the simulator answered. */
export function state() {
  return robot.doc && robot.doc.simulator ? robot.doc : fallback;
}

/** The simulated Driver Station and clock, or null when there is no simulator, or none yet. */
export function sim() {
  const now = state();
  return now ? now.simulator : null;
}

/**
 * Whether this page's robot is the simulator, with the simulator's controls: the only switch for
 * every control that enables, disables, drives or fails anything. True before any snapshot once a
 * paused simulator has answered.
 */
export function isSimulator() {
  const doc = robot.doc;
  return Boolean(doc ? doc.robot.simulated && doc.simulator : fallback);
}

export function isPaused() {
  return Boolean(sim() && sim().paused);
}

/** Whether the simulated robot is enabled, as last heard. */
export function isEnabled() {
  return Boolean(isSimulator() && sim() && sim().enabled);
}

/** Whether the page shows a paused simulator's own answer, having no snapshot yet. */
export function withoutSnapshot() {
  return Boolean(fallback && !robot.doc);
}

/** Whether anything can be asked of the simulator now. */
export function canAct() {
  return !robot.stopped && state() !== null
      && (robot.connected === true || (robot.connected === null && fallback !== null));
}

export function modeName(mode) {
  const found = MODES.find(([name]) => name === mode);
  return found ? found[1] : "no mode";
}

/** "RED_1" as people say it: "Red 1". */
export function stationName(station) {
  if (!STATIONS.includes(station)) return "no alliance station";
  const [alliance, number] = station.split("_");
  return capital(alliance.toLowerCase()) + " " + number;
}

export function capital(text) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The time as a Driver Station shows it: 2:15. */
export function clock(seconds) {
  const whole = Math.max(0, Math.round(seconds));
  return Math.floor(whole / 60) + ":" + String(whole % 60).padStart(2, "0");
}

// ---- Requests ----

/**
 * Changes the Driver Station or the clock, saying what became of it where it was asked (see
 * sim-outcome.js); asked from where there is no place to say it (the state bar), a refusal is
 * said on screen.
 *
 * @param message the note under what was pressed, or null
 */
export async function patch(body, message = null) {
  const result = await send("PATCH", "/api/simulator", body);
  answered(result, message);
  return result;
}

/** Says what became of a request, and takes a paused simulator's answer as its state. */
export function answered(result, message = null) {
  if (message) outcome(message, result, { paused: isPaused() });
  else if (!result.ok) say(result.problem.detail || result.problem.title, true);
  if (result.ok && result.status === 200 && fallback && !robot.doc && result.body
      && "enabled" in result.body) {
    fallback.simulator = result.body; // no stream to say so until a loop runs
    tell(ALL);
  }
}

/**
 * Enables the simulated robot, in the mode the Driver Station has. Only ever called by a press of
 * an Enable button: nothing else on the page enables.
 */
export async function enable(message = null) {
  const now = sim();
  if (!isSimulator() || !now || now.mode === "UNKNOWN") return;
  await patch({ enabled: true }, message);
}

/** Disables the simulated robot. Failing to is said loudly, wherever it was asked from. */
export async function disable(message = null) {
  if (!isSimulator()) return;
  const result = await send("PATCH", "/api/simulator", { enabled: false });
  if (message) outcome(message, result, { paused: isPaused() });
  if (result.ok && result.status === 200 && fallback && !robot.doc && result.body
      && "enabled" in result.body) {
    fallback.simulator = result.body;
    tell(ALL);
  }
  if (!result.ok) {
    say("The simulator didn't disable: " + (result.problem.detail || result.problem.title), true,
      true);
  }
}

// ---- Keeping up ----

/**
 * A page opened while the simulator is paused gets no snapshot: after a second and a half without
 * one, it asks the simulator, which answers at once while paused. Only a paused simulator's answer
 * stands in for a snapshot: anything else (a slow loop, a robot) waits for the stream.
 */
function askSoon() {
  setTimeout(async () => {
    if (robot.doc) return;
    const result = await send("GET", "/api/simulator");
    const answer = result.ok && result.body ? result.body.simulator : null;
    if (robot.doc || !answer || answer.paused !== true || !("enabled" in answer)) return;
    fallback = result.body;
    document.dispatchEvent(new Event("pitwall-offered"));
    tell(ALL);
  }, 1500);
}

/** Says when the robot is enabled or disabled, whoever did it: a disable at once. */
function noteEnabled(snapshot) {
  const now = sim();
  if (!now) return;
  if (!snapshot && lastEnabled !== null && now.enabled !== lastEnabled) {
    if (now.enabled) announce("Enabled: " + modeName(now.mode) + ".");
    else announce("Disabled.", true);
  }
  lastEnabled = now.enabled;
}

onChange((patched, snapshot) => {
  if (snapshot) fallback = null;
  // Anything from the robot means a loop ran: what waited for one is done.
  loopRan();
  // Only what the controls read: the status and the values change often, and say nothing here.
  if (!snapshot && !READS.some((section) => section in patched)) return;
  noteEnabled(snapshot);
  tell({
    station: snapshot || "simulator" in patched,
    faults: snapshot || "components" in patched || "scenarios" in patched,
    drive: snapshot || "simulator" in patched,
    devices: snapshot || "devices" in patched || "simulator" in patched,
  });
});
onConnection((connected) => {
  tell(ALL);
  if (connected && !robot.doc) askSoon();
});
onDrive(() => tell({ drive: true }));
askSoon();

/**
 * Keeps some sections of the simulator's controls drawn while their place is on screen: {@code
 * show(yes)} says when it is. Hidden, they draw nothing, and are drawn whole when shown again.
 *
 * @param parts each {render(what)}
 */
export function keepDrawn(parts) {
  let visible = false;
  const draw = (what) => {
    for (const part of parts) {
      try {
        part.render(what);
      } catch (error) {
        console.error(error);
      }
    }
  };
  onSim((what) => {
    if (visible) draw(what);
  });
  return {
    show(yes) {
      visible = yes;
      if (yes) draw({ ...ALL, opened: true });
    },
  };
}

let ids = 0;

/** An id for one section's element: each section may be mounted in more than one place. */
export function uid(stem) {
  return stem + "-" + ++ids;
}
