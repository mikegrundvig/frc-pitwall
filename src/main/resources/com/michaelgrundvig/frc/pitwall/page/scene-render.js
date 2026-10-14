// Draws a scene with three.js: its frames as groups, its shapes in them (scene-shapes.js), moved by
// the robot's patches. Loaded only once the World view is first shown, with three.js itself
// (/three/, served from the robot like uPlot: no internet at an event).
//
// One renderer, two cameras: from above, orthographic (the 2D map), and around, in perspective. It
// draws only when something changed (a patch, a drag, a resize), never in a loop, so a page left on
// a still robot costs the laptop nothing. Colors are the page's theme tokens, read through computed
// style, light or dark. The canvas is sized by the page's stylesheet: nothing here writes a style.
//
// Labels are a fixed size on screen, so zoomed out they crowd what they name. Each drawing they are
// laid out on the screen (layoutLabels): a label that belongs to the scene's focus (the robot: its
// cameras' names) is moved straight out from the robot until it is clear of it, with a thin leader
// back to where it was; any other label (a tag's number) fades while the robot or one of its
// labels is over it, and comes back as they move off.

import * as THREE from "/three/three.module.min.js";
import { OrbitControls } from "/three/OrbitControls.js";
import { Kit, TOKENS, build, measureDashes, restyle, setPose } from "./scene-shapes.js";

/** How far from a thin line a click still picks it, in CSS pixels. */
const PICK_PIXELS = 12;

/** How much farther a line flat on the floor counts when a click picks, in CSS pixels. */
const FLOOR_PENALTY = 4;

/** How high above the floor a line may be and still lie on it, in meters. */
const FLOOR_HEIGHT = 0.01;

/** A label's height on screen, in CSS pixels, at any zoom. */
const LABEL_PIXELS = 15;

/** How far a label stays clear of the robot's outline and of another label, in CSS pixels. */
const LABEL_CLEAR = 4;

/** How far under the robot a label is before it has faded as far as it goes, in CSS pixels. */
const FADE_PIXELS = 8;

/** How faint a label under the robot is drawn: there, and out of the way. */
const FADED = 0.12;

/** A leader is drawn once its label has moved this far, in CSS pixels. */
const LEADER_FROM = 6;

// What a legend needs of how shapes are drawn, so its samples use the drawing's own styles.
export { TOKENS, FILL_OPACITY, tokenOf, isDashed } from "./scene-shapes.js";

/** Whether this browser can draw 3D at all: three.js needs WebGL 2. */
export function supported() {
  try {
    return Boolean(document.createElement("canvas").getContext("webgl2"));
  } catch (error) {
    return false;
  }
}

export class SceneRenderer {
  /**
   * @param canvas where it draws
   * @param onLost told when the browser takes the drawing away (a lost GPU context)
   */
  constructor(canvas, onLost) {
    this.canvas = canvas;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    canvas.addEventListener("webglcontextlost", (event) => {
      event.preventDefault();
      onLost();
    });
    this.scene = new THREE.Scene();
    this.kit = new Kit();
    this.root = new THREE.Group();
    this.scene.add(this.root);
    this.grid = null;
    this.frames = new Map(); // name -> group; "field" is the root
    this.described = new Map(); // name -> the pose it was described at, or null
    this.shapes = new Map(); // id -> built shape
    this.layers = new Map(); // layer id -> {shown, ids: Set, moment: [built]}
    this.values = new Map();
    this.labels = [];
    this.focus = null; // the frame the scene is about (the robot), whose labels are kept clear
    // The leaders from labels that were moved, all in one object, drawn over everything.
    this.leaderMaterial = new THREE.LineBasicMaterial({ depthTest: false, transparent: true });
    this.leaders = new THREE.LineSegments(new THREE.BufferGeometry(), this.leaderMaterial);
    this.leaders.renderOrder = 9;
    this.leaders.frustumCulled = false;
    this.scene.add(this.leaders);
    this.bounds = new THREE.Box3(new THREE.Vector3(0, 0, 0), new THREE.Vector3(10, 10, 1));
    this.selected = null;
    this.revealed = new Set(); // the shape clicked and those it reveals: shown whatever their layer
    this.hovered = new Set(); // the same, of the shape the pointer is over
    this.followed = null;
    this.followedAt = null;
    this.fitted = new Set();
    this.moved = new Set(); // the views turned, panned or zoomed by hand since Fit framed them
    this.width = 0; // the canvas's width as last sized, in CSS pixels
    this.pending = false;

    // From above: x to the right, y up the screen, as a field's drawing has it.
    this.top = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 400);
    this.top.up.set(0, 1, 0);
    // Around: z is up, as on the field.
    this.around = new THREE.PerspectiveCamera(45, 1, 0.05, 500);
    this.around.up.set(0, 0, 1);
    this.topControls = new OrbitControls(this.top, canvas);
    this.topControls.enableRotate = false;
    this.topControls.screenSpacePanning = true;
    this.topControls.mouseButtons = { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY,
                                      RIGHT: THREE.MOUSE.PAN };
    this.topControls.touches = { ONE: THREE.TOUCH.PAN, TWO: THREE.TOUCH.DOLLY_PAN };
    this.aroundControls = new OrbitControls(this.around, canvas);
    this.aroundControls.maxPolarAngle = Math.PI / 2 - 0.02; // never under the floor
    for (const controls of [this.topControls, this.aroundControls]) {
      let dragging = false;
      controls.addEventListener("start", () => (dragging = true));
      controls.addEventListener("end", () => (dragging = false));
      controls.addEventListener("change", () => {
        if (dragging) this.moved.add(this.view); // by hand, not by Fit or Follow
        this.draw();
      });
    }
    this.use("top");
    this.recolor();
  }

  // ---- What's drawn ----

  /**
   * Builds the scene as described: {"layers": {id: {"title", "shown", "frames", "shapes"}}}. What
   * the robot sent of its state is applied after, by {@link apply}. A layer switched on or off
   * here stays so when the scene is described again.
   */
  describe(description) {
    this.clear();
    this.focus = (description && description.focus) || null;
    const layers = (description && description.layers) || {};
    // Every layer's frames first, since a shape may be in a frame another layer adds.
    this.frames.set("field", this.root);
    this.described.set("field", null);
    const parents = new Map();
    for (const layer of Object.values(layers)) {
      for (const [name, frame] of Object.entries(layer.frames || {})) {
        const group = new THREE.Group();
        group.name = name;
        this.frames.set(name, group);
        this.described.set(name, frame.pose || null);
        parents.set(name, frame.parent);
        if (frame.pose) setPose(group, frame.pose);
      }
    }
    for (const [name, parent] of parents) {
      (this.frames.get(parent) || this.root).add(this.frames.get(name));
    }
    for (const [id, layer] of Object.entries(layers)) {
      const before = this.layers.get(id);
      const chosen = Boolean(before && before.chosen);
      const entry = { shown: chosen ? before.shown : layer.shown !== false, chosen,
                      ids: new Set(), moment: [] };
      this.layers.set(id, entry);
      for (const def of layer.shapes || []) entry.ids.add(this.add(def, id).def.id);
    }
    this.bounds = this.staticBounds();
    this.makeGrid();
    this.place();
    this.fitted = new Set();
    this.moved = new Set();
    this.fit();
    this.draw();
  }

  /**
   * What stays put, which the view frames: every shape but those in frames the robot moves (a
   * frame described with no pose), which stand at the origin until it does.
   */
  staticBounds() {
    this.scene.updateMatrixWorld(true);
    const bounds = new THREE.Box3();
    for (const built of this.shapes.values()) {
      let moves = false;
      for (let object = built.object.parent; object && object !== this.root;
        object = object.parent) {
        if (this.frames.get(object.name) === object && this.described.get(object.name) === null) {
          moves = true;
        }
      }
      if (!moves && !built.between) bounds.expandByObject(built.object, true);
    }
    if (bounds.isEmpty()) bounds.set(new THREE.Vector3(0, 0, 0), new THREE.Vector3(10, 10, 1));
    return bounds;
  }

  /**
   * Applies the robot's state: {"frames", "values", "styles", "shapes"}. With {@code changed}, only
   * what changed (a patch); without, all of it, as when the page starts showing the scene or Hold
   * lets go: then whatever the state doesn't say is as described.
   */
  apply(state, changed) {
    const whole = !changed;
    const part = changed || state || {};
    if (whole) this.reset();
    for (const [name, pose] of Object.entries(part.frames || {})) {
      const frame = this.frames.get(name);
      if (frame && pose) setPose(frame, pose);
    }
    const resized = new Set();
    for (const [name, value] of Object.entries(part.values || {})) {
      this.values.set(name, typeof value === "number" ? value : 0);
      for (const built of this.shapes.values()) {
        if (built.follows && built.follows.includes(name)) resized.add(built);
      }
    }
    for (const built of resized) built.resize(this.values);
    for (const [id, style] of Object.entries(part.styles || {})) {
      const built = this.shapes.get(id);
      if (built) restyle(built, style || built.def.style, this.kit, this.selected === id);
    }
    for (const [layer, defs] of Object.entries(part.shapes || {})) this.moment(layer, defs || []);
    this.place();
    this.draw();
  }

  /** Back to the description: frames where described, sizes at zero, styles and shapes as built. */
  reset() {
    for (const [name, pose] of this.described) {
      const frame = this.frames.get(name);
      if (name === "field" || !frame) continue;
      if (pose) setPose(frame, pose);
      else {
        frame.position.set(0, 0, 0);
        frame.rotation.set(0, 0, 0);
      }
    }
    this.values.clear();
    for (const built of this.shapes.values()) {
      if (built.resize) built.resize(this.values);
      if (built.style !== built.def.style) {
        restyle(built, built.def.style, this.kit, this.selected === built.def.id);
      }
    }
    for (const layer of this.layers.keys()) this.moment(layer, []);
  }

  /** A layer's shapes of the moment, replacing the last of them whole. */
  moment(layerId, defs) {
    const layer = this.layers.get(layerId);
    if (!layer) return;
    for (const built of layer.moment) this.remove(built);
    layer.moment = defs.map((def) => this.add(def, layerId));
    for (const built of layer.moment) built.resize && built.resize(this.values);
  }

  add(def, layerId) {
    const built = build(def, this.kit, (text) => this.label(text));
    built.layer = layerId;
    const old = this.shapes.get(def.id);
    if (old) this.remove(old);
    this.shapes.set(def.id, built);
    // A line between two frames lives in the root, and is kept between them (place).
    const parent = built.between ? this.root : this.frames.get(def.frame) || this.root;
    parent.add(built.object);
    built.object.visible = this.layerShown(layerId) || this.isRevealed(def.id);
    if (this.selected === def.id) restyle(built, built.style, this.kit, true);
    return built;
  }

  /** Whether a layer is switched on: a shape in no layer always is. */
  layerShown(layerId) {
    const layer = this.layers.get(layerId);
    return !layer || layer.shown;
  }

  remove(built) {
    if (this.shapes.get(built.def.id) === built) this.shapes.delete(built.def.id);
    built.object.removeFromParent();
    built.object.traverse((object) => {
      if (object.geometry) object.geometry.dispose();
      if (object.isSprite) {
        object.material.map.dispose();
        object.material.dispose();
        this.labels = this.labels.filter((label) => label !== object);
      }
    });
  }

  clear() {
    for (const built of [...this.shapes.values()]) this.remove(built);
    for (const frame of this.frames.values()) if (frame !== this.root) frame.removeFromParent();
    this.frames.clear();
    this.described.clear();
    this.values.clear();
  }

  /** Puts each line between two frames where those frames are now. */
  place() {
    this.scene.updateMatrixWorld(true);
    const a = new THREE.Vector3();
    const b = new THREE.Vector3();
    for (const built of this.shapes.values()) {
      if (!built.between) continue;
      const from = this.frames.get(built.between[0]);
      const to = this.frames.get(built.between[1]);
      if (!from || !to) continue;
      from.getWorldPosition(a);
      to.getWorldPosition(b);
      const line = built.lines[0];
      line.geometry.setFromPoints([a, b]);
      line.geometry.computeBoundingSphere();
      if (line.material.isLineDashedMaterial) measureDashes(line);
    }
  }

  // ---- Layers, cameras, following ----

  /** Shows or hides a layer, as chosen on the page: kept when the scene is described again. */
  showLayer(id, shown) {
    const layer = this.layers.get(id);
    if (!layer) return;
    layer.shown = shown;
    layer.chosen = true;
    for (const built of this.shapes.values()) {
      if (built.layer === id) built.object.visible = shown || this.isRevealed(built.def.id);
    }
    this.draw();
  }

  /** Uses a camera: "top", from above, or "around", in perspective. */
  use(view) {
    this.view = view;
    this.camera = view === "top" ? this.top : this.around;
    this.controls = view === "top" ? this.topControls : this.aroundControls;
    this.topControls.enabled = view === "top";
    this.aroundControls.enabled = view !== "top";
    this.followedAt = null;
    this.resize();
    if (this.fitted && !this.fitted.has(view)) this.fit();
  }

  /** Frames the whole scene in the camera in use. */
  fit() {
    const center = this.bounds.getCenter(new THREE.Vector3());
    const size = this.bounds.getSize(new THREE.Vector3());
    if (this.view === "top") {
      this.top.zoom = 1;
      this.topControls.target.set(center.x, center.y, 0);
      this.top.position.set(center.x, center.y, 100);
      this.fitTop = Math.max(size.y, size.x / this.aspect()) / 2 * 1.08;
      this.resize();
    } else {
      // From behind the blue end, a little to the side, looking down the field.
      const reach = Math.max(size.x, size.y);
      this.aroundControls.target.set(center.x, center.y, 0);
      this.around.position.set(center.x - reach * 0.75, center.y - reach * 0.45, reach * 0.55);
    }
    this.controls.update();
    this.fitted.add(this.view);
    this.moved.delete(this.view);
    this.followedAt = null;
    this.draw();
  }

  /**
   * Fits the drawing to the canvas after the page changed its size (a panel opening or closing
   * beside the view, the strip under it growing a line). A view as Fit framed it is framed again;
   * one moved by hand keeps its left edge, since what opens beside a view opens on its right.
   * Following, the robot is put back in the middle.
   */
  refit() {
    const before = { width: this.width, height: this.height };
    this.resize();
    if (!before.width || !this.width) return;
    if (before.width === this.width && before.height === this.height) return;
    if (this.followed) {
      this.followedAt = null;
      this.draw();
      return;
    }
    if (!this.moved.has(this.view)) {
      this.fit();
      return;
    }
    // A narrower canvas shows less on both sides alike: shifting it back by half the change keeps
    // the left edge.
    if (before.width !== this.width) this.pan((before.width - this.width) / 2);
  }

  /** Moves the view so the drawing shifts right on screen by some CSS pixels: left if negative. */
  pan(pixels) {
    this.camera.updateMatrixWorld();
    const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
    const move = right.multiplyScalar(-pixels * this.metersPerPixel(this.controls.target));
    this.camera.position.add(move);
    this.controls.target.add(move);
    this.controls.update();
    this.draw();
  }

  /** Keeps a frame (the robot) in the middle of the view as it moves; null lets it go. */
  follow(frame) {
    this.followed = frame && this.frames.has(frame) ? frame : null;
    this.followedAt = null;
    this.draw();
  }

  // ---- Drawing ----

  /** Asks for a drawing at the next frame: one, however many things changed before it. */
  draw() {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => {
      this.pending = false;
      this.render();
    });
  }

  render() {
    if (this.followed) this.keepFollowing();
    this.sizeLabels();
    this.layoutLabels();
    this.renderer.render(this.scene, this.camera);
  }

  keepFollowing() {
    const frame = this.frames.get(this.followed);
    if (!frame) return;
    const now = frame.getWorldPosition(new THREE.Vector3());
    now.z = 0;
    if (this.followedAt) {
      const moved = now.clone().sub(this.followedAt);
      for (const [camera, controls] of [[this.top, this.topControls],
        [this.around, this.aroundControls]]) {
        camera.position.add(moved);
        controls.target.add(moved);
      }
    } else {
      const moved = now.clone().sub(this.controls.target);
      moved.z = 0;
      this.camera.position.add(moved);
      this.controls.target.add(moved);
    }
    this.followedAt = now;
    this.controls.update();
  }

  aspect() {
    const width = this.canvas.clientWidth || 1;
    const height = this.canvas.clientHeight || 1;
    return width / height;
  }

  /** Takes the canvas's size from the page's layout. */
  resize() {
    const width = this.canvas.clientWidth;
    const height = this.canvas.clientHeight;
    if (!width || !height) return;
    this.width = width;
    this.height = height;
    this.renderer.setSize(width, height, false); // false: the stylesheet sizes the canvas
    const half = this.fitTop || 5;
    const aspect = width / height;
    this.top.left = -half * aspect;
    this.top.right = half * aspect;
    this.top.top = half;
    this.top.bottom = -half;
    this.top.updateProjectionMatrix();
    this.around.aspect = aspect;
    this.around.updateProjectionMatrix();
    this.draw();
  }

  /** How many meters a CSS pixel spans at a point, in the camera in use. */
  metersPerPixel(point) {
    const height = this.canvas.clientHeight || 1;
    if (this.camera.isOrthographicCamera) {
      return (this.top.top - this.top.bottom) / this.top.zoom / height;
    }
    const distance = this.camera.position.distanceTo(point);
    return (2 * distance * Math.tan((this.around.fov * Math.PI) / 360)) / height;
  }

  /** Keeps every label the same size on screen at any zoom. */
  sizeLabels() {
    const at = new THREE.Vector3();
    for (const sprite of this.labels) {
      sprite.getWorldPosition(at);
      const meters = LABEL_PIXELS * this.metersPerPixel(at);
      sprite.scale.set(meters * sprite.userData.aspect, meters, 1);
    }
  }

  /** A point of the scene on the canvas, in CSS pixels from its top left. */
  onScreen(point) {
    const v = point.clone().project(this.camera);
    return [(v.x + 1) / 2 * this.canvas.clientWidth, (1 - v.y) / 2 * this.canvas.clientHeight];
  }

  /**
   * The focus's outline on the canvas: the box around the shapes drawn in its own frame (the
   * robot's frame, bumpers and front arrow), as {x, y, w, h} about its middle, or null without one.
   */
  focusOnScreen() {
    const frame = this.focus ? this.frames.get(this.focus) : null;
    if (!frame) return null;
    const box = new THREE.Box3();
    for (const built of this.shapes.values()) {
      if (built.label || built.object.parent !== frame || !this.isShown(built)) continue;
      box.expandByObject(built.object);
    }
    if (box.isEmpty()) return null;
    let left = Infinity, right = -Infinity, top = Infinity, bottom = -Infinity;
    for (const x of [box.min.x, box.max.x]) {
      for (const y of [box.min.y, box.max.y]) {
        for (const z of [box.min.z, box.max.z]) {
          const [px, py] = this.onScreen(new THREE.Vector3(x, y, z));
          left = Math.min(left, px);
          right = Math.max(right, px);
          top = Math.min(top, py);
          bottom = Math.max(bottom, py);
        }
      }
    }
    return { x: (left + right) / 2, y: (top + bottom) / 2,
             w: right - left + 2 * LABEL_CLEAR, h: bottom - top + 2 * LABEL_CLEAR };
  }

  /**
   * Lays the labels out on the screen so they stay off the focus (the robot) and off each other.
   *
   * <p>A label in the focus's own frame is the robot's (a camera's name): it moves straight out
   * from the robot's middle, just far enough to clear the robot's outline, and then on in small
   * steps while it still lies on a label placed before it. How far is worked out from where things
   * are on screen and nothing else, so as the robot moves a label moves with it and doesn't jump.
   * A leader runs from where the label was to its near edge.
   *
   * <p>Any other label (a tag's number) stays where it is and fades by how far the robot, or one
   * of the robot's labels, lies over it: gradually, so it never blinks.
   */
  layoutLabels() {
    const robot = this.focusOnScreen();
    const frame = this.focus ? this.frames.get(this.focus) : null;
    const mine = [];
    const others = [];
    const at = new THREE.Vector3();
    for (const built of this.shapes.values()) {
      const sprite = built.label;
      if (!sprite) continue;
      sprite.center.set(0.5, 0.5);
      sprite.material.opacity = 1;
      if (!robot || !this.isShown(built)) continue;
      sprite.getWorldPosition(at);
      const [x, y] = this.onScreen(at);
      const placed = { sprite, x, y, w: LABEL_PIXELS * sprite.userData.aspect, h: LABEL_PIXELS,
                       world: at.clone() };
      let inFocus = false;
      for (let object = sprite.parent; object; object = object.parent) {
        if (object === frame) inFocus = true;
      }
      (inFocus ? mine : others).push(placed);
    }
    const leaders = [];
    const obstacles = robot ? [robot] : [];
    for (const label of mine) {
      let dx = label.x - robot.x;
      let dy = label.y - robot.y;
      const length = Math.hypot(dx, dy);
      // A label at the robot's very middle goes up the screen.
      [dx, dy] = length < 1e-6 ? [0, -1] : [dx / length, dy / length];
      // Just far enough along its way out to be clear of the robot on one axis.
      const needX = (label.w + robot.w) / 2 - Math.abs(label.x - robot.x);
      const needY = (label.h + robot.h) / 2 - Math.abs(label.y - robot.y);
      let out = needX <= 0 || needY <= 0 ? 0
        : Math.min(Math.abs(dx) > 1e-6 ? needX / Math.abs(dx) : Infinity,
          Math.abs(dy) > 1e-6 ? needY / Math.abs(dy) : Infinity);
      const moved = (by) => ({ x: label.x + dx * by, y: label.y + dy * by, w: label.w, h: label.h });
      for (let tries = 0; tries < 80
          && obstacles.slice(1).some((other) => overlap(moved(out), other) > 0); tries++) {
        out += 3;
      }
      const placed = moved(out);
      obstacles.push({ ...placed, w: placed.w + 2 * LABEL_CLEAR, h: placed.h + 2 * LABEL_CLEAR });
      label.sprite.center.set(0.5 - (dx * out) / label.w, 0.5 + (dy * out) / label.h);
      // The leader stops at the label's near edge.
      const edge = Math.min(Math.abs(dx) > 1e-6 ? label.w / 2 / Math.abs(dx) : Infinity,
        Math.abs(dy) > 1e-6 ? label.h / 2 / Math.abs(dy) : Infinity);
      const reach = out - edge;
      if (out > LEADER_FROM && reach > 2) leaders.push([label, dx * reach, dy * reach]);
    }
    for (const label of others) {
      let under = 0;
      for (const obstacle of obstacles) under = Math.max(under, overlap(label, obstacle));
      const faded = Math.min(1, Math.max(0, under / FADE_PIXELS));
      label.sprite.material.opacity = 1 - (1 - FADED) * faded;
    }
    this.drawLeaders(leaders);
  }

  /** Draws the leaders: each from where a label was to so many pixels right and down of it. */
  drawLeaders(leaders) {
    this.camera.updateMatrixWorld();
    const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
    const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);
    const points = [];
    for (const [label, pixelsRight, pixelsDown] of leaders) {
      const meters = this.metersPerPixel(label.world);
      points.push(label.world, label.world.clone()
        .addScaledVector(right, pixelsRight * meters).addScaledVector(up, -pixelsDown * meters));
    }
    if (points.length !== this.leaderPoints) {
      // Another number of leaders needs a buffer of another size.
      this.leaders.geometry.dispose();
      this.leaders.geometry = new THREE.BufferGeometry();
      this.leaderPoints = points.length;
    }
    this.leaders.geometry.setFromPoints(points);
    this.leaders.visible = points.length > 0;
  }

  /** A label's sprite: its text drawn in the page's text color, with an outline to stand out. */
  label(text) {
    const material = new THREE.SpriteMaterial({ depthTest: false, transparent: true });
    const sprite = new THREE.Sprite(material);
    sprite.renderOrder = 10;
    sprite.userData.text = text;
    this.paintLabel(sprite);
    this.labels.push(sprite);
    return sprite;
  }

  paintLabel(sprite) {
    const scale = 2 * Math.min(window.devicePixelRatio || 1, 2);
    const font = `600 ${16 * scale}px ` + (this.colors ? this.colors.font : "sans-serif");
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d");
    context.font = font;
    const width = Math.ceil(context.measureText(sprite.userData.text).width) + 8 * scale;
    const height = 22 * scale;
    canvas.width = width;
    canvas.height = height;
    context.font = font;
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.lineJoin = "round";
    context.lineWidth = 4 * scale;
    context.strokeStyle = this.colors ? this.colors.backdrop : "#ffffff";
    context.strokeText(sprite.userData.text, width / 2, height / 2);
    context.fillStyle = this.colors ? this.colors.text : "#000000";
    context.fillText(sprite.userData.text, width / 2, height / 2);
    if (sprite.material.map) sprite.material.map.dispose();
    sprite.material.map = new THREE.CanvasTexture(canvas);
    sprite.material.map.colorSpace = THREE.SRGBColorSpace;
    sprite.material.needsUpdate = true;
    sprite.userData.aspect = width / height;
  }

  /** A faint meter grid under the scene, so distance and depth read in 3D. */
  makeGrid() {
    if (this.grid) {
      this.grid.removeFromParent();
      this.grid.geometry.dispose();
    }
    const min = this.bounds.min;
    const max = this.bounds.max;
    const points = [];
    for (let x = Math.floor(min.x); x <= Math.ceil(max.x); x++) {
      points.push(x, Math.floor(min.y), 0, x, Math.ceil(max.y), 0);
    }
    for (let y = Math.floor(min.y); y <= Math.ceil(max.y); y++) {
      points.push(Math.floor(min.x), y, 0, Math.ceil(max.x), y, 0);
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.Float32BufferAttribute(points, 3));
    this.grid = new THREE.LineSegments(geometry, this.gridMaterial
      || (this.gridMaterial = new THREE.LineBasicMaterial({ transparent: true, opacity: 0.6 })));
    this.grid.renderOrder = -1;
    this.scene.add(this.grid);
    if (this.colors) this.gridMaterial.color.copy(this.colors.grid);
  }

  /** Takes the page's colors, as its theme has them now: after a change of theme, say. */
  recolor() {
    const style = getComputedStyle(document.documentElement);
    const color = (name, fallback) => {
      const value = style.getPropertyValue(name).trim();
      try {
        return new THREE.Color(value || fallback);
      } catch (error) {
        return new THREE.Color(fallback);
      }
    };
    const colors = { focus: color("--focus", "#2a78d6") };
    for (const [token, name] of Object.entries(TOKENS)) colors[token] = color(name, "#777777");
    colors.grid = color("--line", "#dddddd");
    this.colors = {
      ...colors,
      text: style.getPropertyValue("--text").trim() || "#000000",
      backdrop: style.getPropertyValue("--surface").trim() || "#ffffff",
      font: style.getPropertyValue("--sans").trim() || "sans-serif",
    };
    this.kit.recolor(colors);
    this.renderer.setClearColor(color("--surface", "#ffffff"));
    if (this.gridMaterial) this.gridMaterial.color.copy(colors.grid);
    this.leaderMaterial.color.copy(colors.muted);
    for (const sprite of this.labels) this.paintLabel(sprite);
    this.draw();
  }

  // ---- Clicking ----

  /**
   * The place on the floor under a point of the canvas, in the scene's own frame (the field's):
   * {x, y} in meters, or null where the view doesn't look at the floor there.
   */
  floorPoint(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    const pointer = new THREE.Vector2(((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(pointer, this.camera);
    const floor = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0);
    const hit = ray.ray.intersectPlane(floor, new THREE.Vector3());
    return hit ? { x: hit.x, y: hit.y } : null;
  }

  /**
   * What a click at a point picks: the nearest inspectable line within a few pixels (a thin ray's
   * hit area is wide), else the nearest inspectable solid under the pointer. Null for nothing.
   */
  pick(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    const y = clientY - rect.top;
    const pointer = new THREE.Vector2((x / rect.width) * 2 - 1, -(y / rect.height) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(pointer, this.camera);
    let nearestLine = null;
    let lineDistance = PICK_PIXELS;
    let nearestSolid = null;
    let solidDistance = Infinity;
    for (const built of this.shapes.values()) {
      if (!built.def.inspect || !this.isShown(built)) continue;
      for (const line of built.lines) {
        // A line flat on the floor (a start's outline) counts a few pixels farther than one above
        // it, so a robot standing on a start is still the one clicked at its bumpers.
        const distance = this.pixelsTo(line, x, y, rect) + (this.onTheFloor(line) ? FLOOR_PENALTY : 0);
        if (distance < lineDistance) {
          lineDistance = distance;
          nearestLine = built;
        }
      }
      for (const fill of built.fills) {
        const hit = ray.intersectObject(fill, false)[0];
        if (hit && hit.distance < solidDistance) {
          solidDistance = hit.distance;
          nearestSolid = built;
        }
      }
    }
    const picked = nearestLine || nearestSolid;
    return picked ? { id: picked.def.id, layer: picked.layer } : null;
  }

  /** Whether a line lies flat on the floor: none of its points is above a centimeter. */
  onTheFloor(line) {
    const position = line.geometry.getAttribute("position");
    const v = new THREE.Vector3();
    for (let i = 0; position && i < position.count; i++) {
      if (v.fromBufferAttribute(position, i).applyMatrix4(line.matrixWorld).z > FLOOR_HEIGHT) {
        return false;
      }
    }
    return Boolean(position);
  }

  isShown(built) {
    for (let object = built.object; object; object = object.parent) {
      if (!object.visible) return false;
    }
    return true;
  }

  /** How far a point on the canvas is from a line's nearest segment on screen, in CSS pixels. */
  pixelsTo(line, x, y, rect) {
    const position = line.geometry.getAttribute("position");
    if (!position) return Infinity;
    const points = [];
    const v = new THREE.Vector3();
    for (let i = 0; i < position.count; i++) {
      v.fromBufferAttribute(position, i).applyMatrix4(line.matrixWorld).project(this.camera);
      points.push(v.z > 1 || v.z < -1 ? null
        : [(v.x + 1) / 2 * rect.width, (1 - v.y) / 2 * rect.height]);
    }
    const pairs = [];
    if (line.isLineSegments) {
      for (let i = 0; i + 1 < points.length; i += 2) pairs.push([points[i], points[i + 1]]);
    } else {
      for (let i = 0; i + 1 < points.length; i++) pairs.push([points[i], points[i + 1]]);
      if (line.isLineLoop && points.length > 2) pairs.push([points[points.length - 1], points[0]]);
    }
    let best = Infinity;
    for (const [a, b] of pairs) {
      if (a && b) best = Math.min(best, segmentDistance(x, y, a, b));
    }
    return best;
  }

  /**
   * Marks a shape as the one inspected (in the focus color), or none: it, and the shapes it reveals
   * (a camera's full view), are drawn whatever their layers until another is, or none.
   */
  select(id) {
    const before = this.shapes.get(this.selected);
    this.selected = id;
    if (before) restyle(before, before.style, this.kit, false);
    const now = this.shapes.get(id);
    if (now) restyle(now, now.style, this.kit, true);
    const was = this.revealed;
    this.revealed = new Set(now ? [id, ...(now.def.reveals || [])] : []);
    this.showRevealed([...was, ...this.revealed]);
  }

  /**
   * Shows what the shape under the pointer reveals (a camera's name and its view) for as long as
   * the pointer is over it, or nothing more for none.
   */
  hover(id) {
    const now = id ? this.shapes.get(id) : null;
    const reveals = now ? now.def.reveals || [] : [];
    if (reveals.length === this.hovered.size && reveals.every((each) => this.hovered.has(each))) {
      return;
    }
    const was = this.hovered;
    this.hovered = new Set(reveals);
    this.showRevealed([...was, ...this.hovered]);
  }

  /** Whether a shape is shown whatever its layer: it, or what reveals it, is clicked or hovered. */
  isRevealed(id) {
    return this.revealed.has(id) || this.hovered.has(id);
  }

  /** Shows or hides some shapes again, as their layers and what reveals them now say. */
  showRevealed(ids) {
    for (const id of new Set(ids)) {
      const built = this.shapes.get(id);
      if (built) built.object.visible = this.isRevealed(id) || this.layerShown(built.layer);
    }
    this.draw();
  }

  dispose() {
    this.clear();
    this.leaders.geometry.dispose();
    this.leaderMaterial.dispose();
    this.topControls.dispose();
    this.aroundControls.dispose();
    this.renderer.dispose();
  }
}

/**
 * How far two boxes on the screen ({x, y, w, h} about their middles) lie over each other: the
 * lesser of how far they overlap across and down, in pixels; zero or less when they don't.
 */
function overlap(a, b) {
  return Math.min((a.w + b.w) / 2 - Math.abs(a.x - b.x), (a.h + b.h) / 2 - Math.abs(a.y - b.y));
}

/** How far a point is from a segment, in the same units. */
function segmentDistance(x, y, a, b) {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const length = dx * dx + dy * dy;
  const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / length));
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
}
