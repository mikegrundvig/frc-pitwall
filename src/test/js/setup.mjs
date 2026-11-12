// What the page's modules expect of a browser, put in place before any of them loads: Node runs
// this first (--import). The DOM is dom.mjs's stand-in; the robot's event stream and its API are
// stand-ins a test drives (stream.mjs); the page's absolute addresses (/three/...) are found
// beside the page (hooks.mjs).

import { register } from "node:module";
import * as dom from "./dom.mjs";

register("./hooks.mjs", import.meta.url);

globalThis.Node = dom.Node;
globalThis.Element = dom.Element;
globalThis.HTMLElement = dom.Element;
globalThis.HTMLInputElement = dom.HTMLInputElement;
globalThis.HTMLTextAreaElement = dom.HTMLTextAreaElement;
globalThis.HTMLSelectElement = dom.HTMLSelectElement;
globalThis.HTMLImageElement = dom.HTMLImageElement;
globalThis.document = new dom.Document();

/** The window: its own events, and where the page is (#tune/...). */
const window = new dom.Listening();
window.location = { hash: "" };
globalThis.window = window;
globalThis.location = window.location;
globalThis.HashChangeEvent = class extends dom.Event {};

/** A tab's storage, kept for the run of a test file. */
class Storage {
  constructor() {
    this.items = new Map();
  }

  getItem(key) {
    return this.items.has(key) ? this.items.get(key) : null;
  }

  setItem(key, value) {
    this.items.set(key, String(value));
  }

  removeItem(key) {
    this.items.delete(key);
  }
}
globalThis.localStorage = new Storage();
globalThis.sessionStorage = new Storage();

/**
 * The robot's event stream, as the browser's EventSource: each one opened is kept, so a test sends
 * the robot's snapshot and patches through it (stream.mjs).
 */
class EventSource extends dom.Listening {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  static opened = [];

  constructor(url) {
    super();
    this.url = url;
    this.readyState = EventSource.OPEN;
    EventSource.opened.push(this);
  }

  close() {
    this.readyState = EventSource.CLOSED;
  }
}
globalThis.EventSource = EventSource;

/** Frames drawn when a test says so (stream.mjs's frames()), as no browser runs here. */
const frames = new Map();
let frameIds = 0;
globalThis.requestAnimationFrame = (callback) => {
  frameIds++;
  frames.set(frameIds, callback);
  return frameIds;
};
globalThis.cancelAnimationFrame = (id) => frames.delete(id);
globalThis.__frames = frames;

globalThis.ResizeObserver = class {
  observe() {}
  disconnect() {}
};

// The page's modules start timers as they load (core.js's check of the stream, every second, and
// sim-state.js's first question to the simulator): they must not keep Node running once a file's
// tests are done. A test that waits on time uses fake timers (node:test's mock.timers).
for (const name of ["setInterval", "setTimeout"]) {
  const ofNode = globalThis[name];
  globalThis[name] = (...args) => {
    const timer = ofNode(...args);
    if (timer && typeof timer.unref === "function") timer.unref();
    return timer;
  };
}
