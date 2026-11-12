// A small stand-in for the browser's DOM, enough for the page's modules to build and update their
// elements in Node: elements with attributes, classes, data- attributes, children and events, text,
// markup to compare (core.js's replace), and a document that can be hidden. Nothing is laid out:
// a size is what a test says it is.

/** Every node: an element or text. */
export class Node {
  constructor() {
    this.parentNode = null;
  }

  remove() {
    if (!this.parentNode) return;
    const siblings = this.parentNode.childNodes;
    siblings.splice(siblings.indexOf(this), 1);
    this.parentNode = null;
  }
}

export class Text extends Node {
  constructor(data) {
    super();
    this.nodeType = 3;
    this.data = String(data);
  }

  get textContent() {
    return this.data;
  }

  set textContent(text) {
    this.data = String(text);
  }

  get markup() {
    return escape(this.data);
  }
}

function escape(text) {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/** Takes events, as the browser's EventTarget does, for an element, the document or the window. */
export class Listening extends Node {
  constructor() {
    super();
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  removeEventListener(type, listener) {
    const each = this.listeners.get(type) || [];
    const at = each.indexOf(listener);
    if (at >= 0) each.splice(at, 1);
  }

  dispatchEvent(event) {
    // A browser's event knows its target; Node's own Event (the page makes some) has it read-only.
    if (!event.target) Object.defineProperty(event, "target", { value: this, configurable: true });
    for (const listener of [...(this.listeners.get(event.type) || [])]) listener(event);
    return !event.defaultPrevented;
  }
}

/** An event, as the page's listeners read one. */
export class Event {
  constructor(type, init = {}) {
    Object.assign(this, init);
    this.type = type;
    this.defaultPrevented = false;
  }

  preventDefault() {
    this.defaultPrevented = true;
  }
}

/** Attributes the browser mirrors as true-or-false properties. */
const FLAGS = ["hidden", "disabled", "readOnly", "checked"];

export class Element extends Listening {
  /** How wide every element says it is, in pixels: what a test lays out, as no browser does. */
  static width = 0;

  constructor(tagName, namespaceURI = null) {
    super();
    this.nodeType = 1;
    this.tagName = tagName.toUpperCase();
    this.localName = tagName;
    this.namespaceURI = namespaceURI;
    this.attributes = new Map();
    this.childNodes = [];
    this.style = {};
    this.value = "";
    const element = this;
    this.classList = {
      contains: (name) => element.className.split(" ").includes(name),
      add: (...names) => names.forEach((name) => element.classList.toggle(name, true)),
      remove: (...names) => names.forEach((name) => element.classList.toggle(name, false)),
      toggle(name, force) {
        const names = element.className.split(" ").filter(Boolean);
        const has = names.includes(name);
        const want = force === undefined ? !has : Boolean(force);
        if (want && !has) names.push(name);
        if (!want && has) names.splice(names.indexOf(name), 1);
        element.className = names.join(" ");
        return want;
      },
    };
    this.dataset = new Proxy({}, {
      get: (_, key) => element.getAttribute("data-" + kebab(key)) ?? undefined,
      set: (_, key, value) => {
        element.setAttribute("data-" + kebab(key), value);
        return true;
      },
    });
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  get id() {
    return this.getAttribute("id") || "";
  }

  set id(id) {
    this.setAttribute("id", id);
  }

  get className() {
    return this.getAttribute("class") || "";
  }

  set className(name) {
    this.setAttribute("class", name);
  }

  get src() {
    return this.getAttribute("src") || "";
  }

  set src(address) {
    this.setAttribute("src", address);
  }

  get title() {
    return this.getAttribute("title") || "";
  }

  set title(text) {
    this.setAttribute("title", text);
  }

  get children() {
    return this.childNodes.filter((node) => node instanceof Element);
  }

  get firstChild() {
    return this.childNodes[0] || null;
  }

  get textContent() {
    return this.childNodes.map((node) => node.textContent).join("");
  }

  set textContent(text) {
    this.replaceChildren(String(text));
  }

  get clientWidth() {
    return Element.width;
  }

  get offsetWidth() {
    return Element.width;
  }

  getBoundingClientRect() {
    return { left: 0, top: 0, width: Element.width, height: 0, right: Element.width, bottom: 0 };
  }

  append(...nodes) {
    for (const node of nodes) {
      const child = node instanceof Node ? node : new Text(node);
      child.remove();
      child.parentNode = this;
      this.childNodes.push(child);
    }
  }

  appendChild(node) {
    this.append(node);
    return node;
  }

  replaceChildren(...nodes) {
    for (const child of [...this.childNodes]) child.remove();
    this.append(...nodes);
  }

  /** The element's own markup, its children's: what core.js's replace compares. */
  get innerHTML() {
    return this.childNodes.map((node) => node.markup).join("");
  }

  get markup() {
    const attributes = [...this.attributes].map(([name, value]) => " " + name + "=\""
        + escape(value).replace(/"/g, "&quot;") + "\"").join("");
    return "<" + this.localName + attributes + ">" + this.innerHTML + "</" + this.localName + ">";
  }

  /** Every element under this one, in document order. */
  *descendants() {
    for (const child of this.children) {
      yield child;
      yield* child.descendants();
    }
  }

  /** The first element under this one matching a test (a selector is more than this needs). */
  find(test) {
    for (const each of this.descendants()) if (test(each)) return each;
    return null;
  }

  /** Every element under this one matching a test. */
  findAll(test) {
    return [...this.descendants()].filter(test);
  }

  querySelector() {
    return null;
  }

  closest() {
    return null;
  }

  click() {
    this.dispatchEvent(new Event("click"));
  }

  focus() {
    globalThis.document.activeElement = this;
  }

  blur() {
    if (globalThis.document.activeElement === this) globalThis.document.activeElement = null;
  }

  setPointerCapture() {}
}

for (const flag of FLAGS) {
  const name = flag.toLowerCase();
  Object.defineProperty(Element.prototype, flag, {
    get() {
      return this.hasAttribute(name);
    },
    set(on) {
      if (on) this.setAttribute(name, "");
      else this.removeAttribute(name);
    },
  });
}

function kebab(key) {
  return String(key).replace(/[A-Z]/g, (letter) => "-" + letter.toLowerCase());
}

export class HTMLInputElement extends Element {}
export class HTMLTextAreaElement extends Element {}
export class HTMLSelectElement extends Element {}

/** An image: it has a picture once a test says how wide the picture is. */
export class HTMLImageElement extends Element {
  constructor(tagName) {
    super(tagName);
    this.naturalWidth = 0;
  }
}

const KINDS = { input: HTMLInputElement, textarea: HTMLTextAreaElement, select: HTMLSelectElement,
                img: HTMLImageElement };

/** The page's document: its body, and whether its tab is in sight. */
export class Document extends Listening {
  constructor() {
    super();
    this.body = new Element("body");
    this.activeElement = null;
    this.visibilityState = "visible";
  }

  get hidden() {
    return this.visibilityState !== "visible";
  }

  createElement(tag) {
    const Kind = KINDS[tag] || Element;
    return new Kind(tag);
  }

  createElementNS(namespace, tag) {
    return new Element(tag, namespace);
  }

  createTextNode(text) {
    return new Text(text);
  }

  getElementById(id) {
    return this.body.find((element) => element.id === id);
  }

  querySelector() {
    return null;
  }

  /** Hides the tab, or brings it back, as switching tabs does. */
  setVisibility(state) {
    this.visibilityState = state;
    this.dispatchEvent(new Event("visibilitychange"));
  }
}
