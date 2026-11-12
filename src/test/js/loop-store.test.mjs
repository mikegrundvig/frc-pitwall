// A view's store of the session's earlier loops: one fetch at a time, only the latest loop wanted,
// the loops beside it while nothing is, and a few hundred kept.

import { test } from "node:test";
import assert from "node:assert/strict";
import { makeLoopStore } from "../page/loop-store.js";
import { serve, settle } from "./stream.mjs";

const addressOf = (index) => "/api/robot/view?index=" + index;

/**
 * A robot whose answers wait until the test lets them go, each the loop's index in a body; {@code
 * missing} loops are answered 404, as for a loop the session no longer holds.
 */
function slowRobot(missing = new Set()) {
  const waiting = [];
  const asked = serve((path) => new Promise((resolve) => {
    const index = Number(path.split("=")[1]);
    waiting.push(() => resolve(missing.has(index) ? { status: 404 } : { body: { index } }));
  }));
  return {
    asked: () => asked.map((request) => request.path),
    async answer() {
      waiting.shift()();
      await settle();
    },
  };
}

test("a loop wanted is fetched, kept, and given at once when wanted again", async () => {
  const robot = slowRobot();
  const store = makeLoopStore(addressOf);
  const given = [];

  store.want(5, (answer) => given.push(answer));
  assert.deepEqual(given, [], "waits for the robot");
  await robot.answer();
  store.want(5, (answer) => given.push(answer));

  assert.deepEqual(given, [{ index: 5 }, { index: 5 }]);
  assert.deepEqual(robot.asked(), [addressOf(5)], "asked once");
  assert.deepEqual(store.kept(5), { index: 5 });
  assert.equal(store.kept(6), null);
});

test("dragging across loops fetches only the one wanted last", async () => {
  const robot = slowRobot();
  const store = makeLoopStore(addressOf);
  const given = [];

  for (const index of [1, 2, 3, 4]) store.want(index, (answer) => given.push([index, answer]));
  await robot.answer();
  await robot.answer();

  assert.deepEqual(robot.asked(), [addressOf(1), addressOf(4)]);
  assert.deepEqual(given, [[4, { index: 4 }]], "a loop wanted meanwhile is the one given");
  assert.deepEqual(store.kept(1), { index: 1 }, "what came is kept anyway");
});

test("while nothing is wanted, the loops beside the last are fetched", async () => {
  const robot = slowRobot();
  const store = makeLoopStore(addressOf);

  store.want(10, () => {}, [9, 11, -1]);
  await robot.answer();
  await robot.answer();
  await robot.answer();

  assert.deepEqual(robot.asked(), [addressOf(10), addressOf(9), addressOf(11)],
    "no loop before the first");
  assert.deepEqual(store.kept(11), { index: 11 });
});

test("a loop the robot doesn't hold, or a robot not answering, gives null and keeps nothing",
  async () => {
    const rows = [
      { name: "a loop no longer held", serveIt: () => slowRobot(new Set([3])) },
      { name: "a robot not answering", serveIt: () => {
        serve(() => {
          throw new Error("unreachable");
        });
        return { answer: settle };
      } },
    ];
    for (const row of rows) {
      const robot = row.serveIt();
      const store = makeLoopStore(addressOf);
      const given = [];

      store.want(3, (answer) => given.push(answer));
      await robot.answer();

      assert.deepEqual(given, [null], row.name);
      assert.equal(store.kept(3), null, row.name);
    }
  });

test("a store keeps the latest 400 loops it was given", async () => {
  serve((path) => ({ body: { index: Number(path.split("=")[1]) } }));
  const store = makeLoopStore(addressOf);

  for (let index = 0; index <= 400; index++) {
    store.want(index, () => {});
    await settle();
  }
  store.want(1, () => {}); // asked for again, so dropped last

  assert.equal(store.kept(0), null, "the oldest went");
  assert.deepEqual(store.kept(1), { index: 1 });
  assert.deepEqual(store.kept(400), { index: 400 });
});

test("forgetting drops every loop and what was wanted", async () => {
  const robot = slowRobot();
  const store = makeLoopStore(addressOf);
  const given = [];
  store.want(1, () => {});
  await robot.answer();

  store.want(2, (answer) => given.push(answer));
  store.forget();
  await robot.answer();

  assert.equal(store.kept(1), null);
  assert.equal(store.kept(2), null, "an answer that lands after forgetting is the old session's");
  assert.deepEqual(given, [], "the session it was wanted from is gone");
});

test("a loop wanted again after forgetting is fetched from the new session", async () => {
  const robot = slowRobot();
  const store = makeLoopStore(addressOf);
  const given = [];
  store.want(2, () => {});

  store.forget();
  await robot.answer(); // the old session's answer lands
  store.want(2, (answer) => given.push(answer));
  await robot.answer();

  assert.deepEqual(robot.asked(), [addressOf(2), addressOf(2)], "asked again");
  assert.deepEqual(given, [{ index: 2 }]);
});
