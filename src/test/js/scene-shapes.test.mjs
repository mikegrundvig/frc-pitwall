// A scene's shapes as three.js objects: styles, poses, the outline of a frustum on the floor, and
// every kind of shape the robot describes, built and styled as the World view builds them.

import { test } from "node:test";
import assert from "node:assert/strict";
import * as THREE from "/three/three.module.min.js";
import {
  Kit, tokenOf, isDashed, setPose, build, restyle, convexHull, measureDashes,
} from "../page/scene-shapes.js";

const near = (a, b) => Math.abs(a - b) < 1e-9;

test("a style is a color token, solid or dashed", () => {
  const rows = [
    ["ok", "ok", false],
    ["bad dashed", "bad", true],
    ["truth", "truth", false],
    ["neon", "muted", false],
    ["neon dashed", "muted", true],
    ["", "muted", false],
    [undefined, "muted", false],
  ];
  for (const [style, token, dashed] of rows) {
    assert.equal(tokenOf(style), token, String(style));
    assert.equal(isDashed(style), dashed, String(style));
  }
});

test("a pose is put on an object as WPILib turns: roll, pitch, then yaw about the fixed axes",
  () => {
    const rows = [
      { pose: [1, 2, 90], at: [1, 2, 0], turn: [0, 0, 90] },
      { pose: [1, 2, 0.5, 10, 20, 30], at: [1, 2, 0.5], turn: [10, 20, 30] },
    ];
    for (const row of rows) {
      const object = new THREE.Object3D();
      setPose(object, row.pose);
      assert.deepEqual(object.position.toArray(), row.at);
      const degrees = [object.rotation.x, object.rotation.y, object.rotation.z]
        .map((radians) => radians * 180 / Math.PI);
      degrees.forEach((each, axis) => assert.ok(near(each, row.turn[axis]), row.pose + ": " + axis));
      assert.equal(object.rotation.order, "ZYX");
    }
    const untouched = new THREE.Object3D();
    setPose(untouched, null);
    assert.deepEqual(untouched.position.toArray(), [0, 0, 0], "no pose leaves it where it is");
  });

test("the outline of points is their convex hull, counterclockwise", () => {
  const rows = [
    { points: [], hull: [] },
    { points: [[1, 1], [0, 0]], hull: [[0, 0], [1, 1]] },
    { points: [[0, 0], [2, 0], [2, 2], [0, 2], [1, 1]], hull: [[0, 0], [2, 0], [2, 2], [0, 2]] },
    { points: [[0, 0], [1, 0], [2, 0], [1, 1]], hull: [[0, 0], [2, 0], [1, 1]] },
    { points: [[0, 0], [1, 1], [2, 2]], hull: [[0, 0], [2, 2]] },
  ];
  for (const row of rows) {
    assert.deepEqual(convexHull(row.points), row.hull, JSON.stringify(row.points));
  }
});

/** One of each kind the robot describes, with what it builds. */
const SHAPES = [
  { def: { id: "bumper", kind: "box", size: [0.8, 0.8, 0.1], style: "accent" },
    lines: 1, fills: 1 },
  { def: { id: "frame", kind: "box", size: [1, 1, 1], outline: true, style: "muted" },
    lines: 1, fills: 0 },
  { def: { id: "wheel", kind: "cylinder", radius: 0.05, length: 0.04, style: "ok" },
    lines: 1, fills: 1 },
  { def: { id: "zone", kind: "cylinder", radii: ["reach", 0.5], style: "bad dashed" },
    lines: 1, fills: 1 },
  { def: { id: "tether", kind: "line", from: [0, 0, 0], to: [1, 0, 0], style: "truth dashed" },
    lines: 1, fills: 0 },
  { def: { id: "path", kind: "polyline", points: [[0, 0, 0], [1, 0, 0], [1, 1, 0]],
           style: "accent" }, lines: 1, fills: 0 },
  { def: { id: "goal", kind: "polygon", points: [[0, 0, 0], [1, 0, 0], [0, 1, 0]],
           style: "structure" }, lines: 1, fills: 1 },
  { def: { id: "tag", kind: "mesh", vertices: [[0, 0, 0], [1, 0, 0], [0, 1, 0]],
           triangles: [[0, 1, 2]], style: "face" }, lines: 1, fills: 1 },
  { def: { id: "push", kind: "arrow", from: [0, 0, 0], to: [0, 0, 1], style: "ok" },
    lines: 1, fills: 0, heads: 1 },
  { def: { id: "view", kind: "frustum", fov: [70, 50], range: [0.1, 4], plan: true,
           style: "muted dashed" }, lines: 2, fills: 1 },
  { def: { id: "name", kind: "label", text: "front" }, lines: 0, fills: 0 },
];

test("every kind of shape builds its lines and fills in its style", () => {
  const kit = new Kit();
  const labels = [];
  const label = (text) => {
    labels.push(text);
    return new THREE.Object3D();
  };
  for (const shape of SHAPES) {
    const built = build(shape.def, kit, label);
    const name = shape.def.id;

    assert.equal(built.object.name, name);
    assert.equal(built.lines.length, shape.lines, name + ": lines");
    assert.equal(built.fills.length, shape.fills, name + ": fills");
    assert.equal(built.heads.length, shape.heads || 0, name + ": heads");
    for (const line of built.lines) {
      assert.equal(line.material, kit.line(shape.def.style), name + ": drawn in its style");
    }
    for (const fill of built.fills) {
      assert.equal(fill.material, kit.fills.get(tokenOf(shape.def.style)), name);
    }
  }
  assert.deepEqual(labels, ["front"]);
});

test("a selected shape is drawn in the selection's color, dashed if its style is", () => {
  const kit = new Kit();
  const rows = [
    { style: "ok", selected: true, material: kit.selected },
    { style: "ok dashed", selected: true, material: kit.selectedDashed },
    { style: "bad", selected: false, material: kit.line("bad") },
  ];
  for (const row of rows) {
    const built = build({ id: "x", kind: "box", size: [1, 1, 1], style: "ok" }, kit);
    restyle(built, row.style, kit, row.selected);
    assert.equal(built.lines[0].material, row.material, row.style);
    assert.equal(built.style, row.style);
  }
});

test("a shape whose size follows the scene's values resizes, and hides at zero", () => {
  const kit = new Kit();
  const zone = build({ id: "zone", kind: "cylinder", radii: ["reach", 0.5] }, kit);
  const push = build({ id: "push", kind: "arrow", from: [0, 0, 0], to: [1, 0, 0],
                       length: "force" }, kit);
  const sized = zone.object.children[0];

  assert.deepEqual(zone.follows, ["reach"]);
  assert.equal(sized.visible, false, "no value yet");
  zone.resize(new Map([["reach", 2]]));
  assert.equal(sized.visible, true);
  assert.deepEqual([sized.scale.x, sized.scale.y], [2, 0.5]);

  assert.deepEqual(push.follows, ["force"]);
  assert.equal(push.lines[0].visible, false, "no value yet");
  push.resize(new Map([["force", -0.5]]));
  assert.equal(push.lines[0].visible, true);
  assert.ok(push.heads[0].position.x < 0, "a negative length points back");
});

test("a dashed line's lengths are measured along it, each segment on its own", () => {
  const rows = [
    { line: new THREE.Line(new THREE.BufferGeometry().setFromPoints(
      [new THREE.Vector3(0, 0, 0), new THREE.Vector3(3, 0, 0), new THREE.Vector3(3, 4, 0)])),
      along: [0, 3, 7] },
    { line: new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(
      [new THREE.Vector3(0, 0, 0), new THREE.Vector3(1, 0, 0), new THREE.Vector3(5, 0, 0),
        new THREE.Vector3(5, 2, 0)])),
      along: [0, 1, 1, 3] },
  ];
  for (const row of rows) {
    measureDashes(row.line);
    const measured = row.line.geometry.getAttribute("lineDistance");
    assert.deepEqual(Array.from(measured.array), row.along);
    measureDashes(row.line);
    assert.equal(row.line.geometry.getAttribute("lineDistance"), measured, "measured in place");
  }
});

test("a frustum's outline on the floor follows its frame", () => {
  const kit = new Kit();
  const built = build({ id: "view", kind: "frustum", fov: [90, 60], range: [0, 2], plan: true },
    kit);
  const outline = built.plan.group.children[1];

  built.plan.update(new THREE.Matrix4());
  const level = convexHull([[0, 0], [2, 2], [2, -2]]);
  const points = outline.geometry.getAttribute("position");
  assert.equal(points.count, level.length);
  level.forEach(([x, y], i) => {
    assert.ok(near(points.getX(i), x) && near(points.getY(i), y), "corner " + i);
  });
  assert.equal(built.plan.group.visible, true);

  // Turned to face along y, its outline turns with it.
  built.plan.update(new THREE.Matrix4().makeRotationZ(Math.PI / 2));
  const turned = convexHull([[0, 0], [-2, 2], [2, 2]]);
  turned.forEach(([x, y], i) => {
    assert.ok(near(points.getX(i), x) && near(points.getY(i), y), "turned corner " + i);
  });
});

test("a frustum's outline on the floor has each corner of its hull as the hull's size changes",
  () => {
    const kit = new Kit();
    const built = build({ id: "view", kind: "frustum", fov: [90, 60], range: [0, 2], plan: true },
      kit);
    const outline = built.plan.group.children[1];
    const far = 2 * Math.tan(30 * Math.PI / 180);
    const corners = [[0, 0, 0], [2, 2, far], [2, -2, far], [2, -2, -far], [2, 2, -far]];
    // The outline holds 32-bit floats, so a turned corner is as near as one of those can be.
    const close = (a, b) => Math.abs(a - b) < 1e-6;
    const rows = [
      { name: "level: a triangle", matrix: new THREE.Matrix4() },
      { name: "pitched: the far corners apart", matrix: new THREE.Matrix4().makeRotationY(0.3) },
      { name: "level again", matrix: new THREE.Matrix4() },
    ];
    for (const row of rows) {
      built.plan.update(row.matrix);
      const hull = convexHull(corners.map((c) => {
        const at = new THREE.Vector3(...c).applyMatrix4(row.matrix);
        return [at.x, at.y];
      }));
      const points = outline.geometry.getAttribute("position");
      assert.equal(points.count, hull.length, row.name + ": every corner, and no more");
      hull.forEach(([x, y], i) => {
        assert.ok(close(points.getX(i), x) && close(points.getY(i), y), row.name + ": corner " + i);
      });
    }
  });
