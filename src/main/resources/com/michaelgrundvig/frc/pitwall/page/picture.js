// A card's live picture (Devices.Picture): a camera's video, as an image that updates as it comes,
// which this browser loads straight from the device, never through the robot. It's open only while
// the Pit view is shown and the page is in sight, so a device makes no pictures for a page no one
// looks at. While the robot names no address, the card says why in the picture's place; while the
// browser can't reach the address, it says so too, plainly, never a broken image, and tries again
// every few seconds.

import { h } from "./core.js";

/** How soon an address that didn't answer is tried again. */
const RETRY_MS = 5000;

/** How long to wait for the first picture before saying the address isn't answering. */
const FIRST_PICTURE_MS = 8000;

/** How often to look whether the first picture has come. */
const CHECK_MS = 250;

const pictures = new Set();
let viewShown = false;

/** A picture's place on a card, saying why there's none until the robot names an address. */
export function makePicture(name) {
  const picture = { name, url: null, unavailable: null, img: null, check: null, retry: null };
  picture.note = h("p", { class: "dpicture-note", role: "status" });
  picture.el = h("div", { class: "dpicture" }, picture.note);
  pictures.add(picture);
  return picture;
}

/** Closes a picture whose card is gone. */
export function dropPicture(picture) {
  close(picture);
  pictures.delete(picture);
}

/**
 * The picture as the robot names it now: {url} where it is, or {unavailable} why there's none.
 * Unchanged, it's left as it is.
 */
export function updatePicture(picture, given) {
  const url = (given && given.url) || null;
  const unavailable = (given && given.unavailable) || "";
  if (url === picture.url && unavailable === picture.unavailable) return;
  picture.url = url;
  picture.unavailable = unavailable;
  refresh(picture);
}

/** Says whether the view the pictures are on is shown: they're open only while it is. */
export function showPictures(shown) {
  viewShown = shown;
  for (const picture of pictures) refresh(picture);
}

document.addEventListener("visibilitychange", () => {
  for (const picture of pictures) refresh(picture);
});

function inSight() {
  return viewShown && document.visibilityState === "visible";
}

/** Opens the picture's address, or closes it and says why there's no picture. */
function refresh(picture) {
  close(picture);
  if (!picture.url) {
    say(picture, picture.unavailable || "No live view.");
  } else if (inSight()) {
    open(picture);
  } else {
    say(picture, "Paused while the page is out of sight.");
  }
}

function open(picture) {
  const img = h("img", { class: "dpicture-img", alt: "Live view of " + picture.name, hidden: true });
  const url = picture.url;
  let waited = 0;
  picture.img = img;
  say(picture, "Connecting to " + url + "…");
  img.addEventListener("error", () => {
    if (picture.img === img) failed(picture, url);
  });
  // A stream's image fires no load event a browser agrees on, so its first picture is looked for:
  // an address that never sends one counts as not answering.
  picture.check = setInterval(() => {
    if (picture.img !== img) return;
    if (img.naturalWidth > 0) {
      img.hidden = false;
      picture.note.hidden = true;
      return;
    }
    waited += CHECK_MS;
    if (waited >= FIRST_PICTURE_MS) failed(picture, url);
  }, CHECK_MS);
  picture.el.append(img);
  img.src = url;
}

function failed(picture, url) {
  close(picture);
  say(picture, "The live view at " + url + " isn't answering this browser: the device may be off,"
      + " or not on this network. Trying again every " + RETRY_MS / 1000 + " s.");
  picture.retry = setTimeout(() => {
    picture.retry = null;
    if (picture.url === url && inSight()) open(picture);
  }, RETRY_MS);
}

/** Stops loading: taking the image's address away ends its stream. */
function close(picture) {
  clearInterval(picture.check);
  clearTimeout(picture.retry);
  picture.check = null;
  picture.retry = null;
  if (picture.img) {
    picture.img.removeAttribute("src");
    picture.img.remove();
    picture.img = null;
  }
}

function say(picture, text) {
  picture.note.hidden = false;
  picture.note.textContent = text;
}
