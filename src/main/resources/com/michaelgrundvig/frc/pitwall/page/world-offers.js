// The faults a clicked shape offers to apply as they stand, in the World view's details card: a
// start's "Place the robot here". Each is a button that makes the very request the Sim view's
// fault rows make (POST /api/simulator/faults), with the numbers the robot program set in the
// offer. The robot program offers them only in the simulator; on a robot the page is told of none.

import { send, h } from "./core.js";
import { canAct, isPaused } from "./sim-state.js";
import { note, outcome } from "./sim-outcome.js";

/**
 * Buttons for the offers a shape made, each saying under itself what became of the press.
 *
 * @param offers [{label, component, fault, values}] as the robot program answered
 * @return {element, render()}: render() locks the buttons while nothing can be asked of the
 *     simulator
 */
export function makeOffers(offers) {
  const rows = offers.map((offer) => {
    const message = h("p", { class: "message", hidden: true });
    const button = h("button", { type: "button", class: "primary" }, offer.label);
    const row = { offer, button, message, busy: false };
    button.addEventListener("click", () => apply(row));
    return row;
  });
  const element = h("div", { class: "world-offers" },
    rows.map((row) => h("div", { class: "world-offer" }, row.button, row.message)));

  function render() {
    for (const row of rows) row.button.disabled = row.busy || !canAct();
  }

  async function apply(row) {
    if (row.busy || !canAct()) return;
    const { component, fault, values } = row.offer;
    row.busy = true;
    render();
    const result = await send("POST", "/api/simulator/faults", { component, fault, values });
    row.busy = false;
    render();
    const done = component + ": " + fault + ", done.";
    // Kept under the button, unlike the Sim view's, since this card has no other sign of it.
    if (outcome(row.message, result, { done, paused: isPaused() })) note(row.message, "✓ " + done);
  }

  render();
  return { element, render };
}
