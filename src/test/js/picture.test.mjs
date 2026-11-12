// A card's live picture, through its life: what it says while there's no address, the first
// picture it waits for, trying again when the address doesn't answer, pausing while the tab is
// hidden, and letting go of the stream when its card is gone. Time is the test's own (fake timers).

import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { makePicture, dropPicture, updatePicture, showPictures } from "../page/picture.js";

const ADDRESS = "http://10.12.34.11:5809/stream/front";
const NOT_ANSWERING = "The live view at " + ADDRESS + " isn't answering this browser: the device may"
    + " be off, or not on this network. Trying again every 5 s.";

let picture;

beforeEach((t) => {
  t.mock.timers.enable({ apis: ["setInterval", "setTimeout"] });
  document.setVisibility("visible");
  showPictures(true);
  picture = makePicture("front");
});

afterEach(() => {
  dropPicture(picture);
  showPictures(false);
});

/** The image streaming the picture, or null while there's none. */
function image() {
  return picture.el.find((element) => element.localName === "img");
}

function note() {
  return picture.note.hidden ? null : picture.note.textContent;
}

test("what a picture says while it has no address", () => {
  const rows = [
    { given: undefined, says: "No live view." },
    { given: {}, says: "No live view." },
    { given: { unavailable: "No live view: the camera is on PhotonVision" },
      says: "No live view: the camera is on PhotonVision" },
  ];
  for (const row of rows) {
    updatePicture(picture, { unavailable: "something else first" });
    updatePicture(picture, row.given);
    assert.equal(note(), row.says, JSON.stringify(row.given));
    assert.equal(image(), null);
  }
});

test("a picture shows once its first one comes", (t) => {
  updatePicture(picture, { url: ADDRESS });
  const img = image();

  assert.equal(img.src, ADDRESS);
  assert.equal(img.hidden, true, "hidden until a picture comes");
  assert.equal(note(), "Connecting to " + ADDRESS + "…");

  img.naturalWidth = 640;
  t.mock.timers.tick(250);

  assert.equal(img.hidden, false);
  assert.equal(note(), null);
  t.mock.timers.tick(10_000);
  assert.equal(image(), img, "a stream that sends pictures is kept");
});

test("an address that sends no first picture is tried again", (t) => {
  updatePicture(picture, { url: ADDRESS });
  const first = image();

  t.mock.timers.tick(7_750);
  assert.equal(image(), first, "still waiting");
  t.mock.timers.tick(250);

  assert.equal(image(), null, "given up on");
  assert.equal(first.src, "", "its stream closed");
  assert.equal(note(), NOT_ANSWERING);

  t.mock.timers.tick(4_999);
  assert.equal(image(), null);
  t.mock.timers.tick(1);

  const again = image();
  assert.notEqual(again, null, "tried again");
  assert.notEqual(again, first);
  assert.equal(again.src, ADDRESS);
});

test("an address the browser can't load is tried again", (t) => {
  updatePicture(picture, { url: ADDRESS });
  const first = image();

  first.dispatchEvent({ type: "error" });

  assert.equal(image(), null);
  assert.equal(note(), NOT_ANSWERING);
  t.mock.timers.tick(5_000);
  assert.equal(image().src, ADDRESS);
});

test("a picture pauses while it's out of sight, and opens again when it's back", (t) => {
  const rows = [
    { name: "the tab is hidden", hide: () => document.setVisibility("hidden"),
      show: () => document.setVisibility("visible") },
    { name: "another view is shown", hide: () => showPictures(false),
      show: () => showPictures(true) },
  ];
  for (const row of rows) {
    updatePicture(picture, { url: ADDRESS });
    const open = image();

    row.hide();

    assert.equal(image(), null, row.name);
    assert.equal(open.src, "", row.name + ": its stream closed");
    assert.equal(note(), "Paused while the page is out of sight.", row.name);
    t.mock.timers.tick(60_000);
    assert.equal(image(), null, row.name + ": not tried while out of sight");

    row.show();

    assert.equal(image().src, ADDRESS, row.name);
    updatePicture(picture, {});
  }
});

test("a retry waiting while the tab is hidden doesn't open the picture", (t) => {
  updatePicture(picture, { url: ADDRESS });
  image().dispatchEvent({ type: "error" });

  document.setVisibility("hidden");
  t.mock.timers.tick(5_000);

  assert.equal(image(), null);
});

test("a dropped picture lets go of its stream and its timers", (t) => {
  updatePicture(picture, { url: ADDRESS });
  const open = image();

  dropPicture(picture);

  assert.equal(image(), null);
  assert.equal(open.src, "");
  t.mock.timers.tick(60_000);
  showPictures(true);
  document.setVisibility("visible");
  assert.equal(image(), null, "nothing brings it back");
});

test("the same address again leaves the open picture as it is", () => {
  updatePicture(picture, { url: ADDRESS });
  const open = image();

  updatePicture(picture, { url: ADDRESS });

  assert.equal(image(), open);
});

test("a new address replaces the open one", () => {
  updatePicture(picture, { url: ADDRESS });
  const open = image();

  updatePicture(picture, { url: "http://10.12.34.12:5809/stream/front" });

  assert.equal(open.src, "");
  assert.equal(image().src, "http://10.12.34.12:5809/stream/front");
});
