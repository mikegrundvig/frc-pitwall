package com.michaelgrundvig.frc.pitwall;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The rule for the names of the Pit view's cards: no two cards have one name, since a card's name
 * is its place in the page's addresses ({@code /api/devices/<name>/...}). {@link Devices#add} keeps
 * it as devices are added. A robot program can also check the names its cards will have before it
 * makes any of them, so that a clash is refused with a reason as its configuration is built, not
 * when Pitwall starts: the first card to take a name has it, and a later one is told whose card has
 * it.
 */
public final class CardNames {
  /** Each name taken, with whose card has it. */
  private final Map<String, String> taken = new HashMap<>();

  /**
   * Takes a name for a card, unless another card has it.
   *
   * @param name the card's name, as its device is named
   * @param whose whose card it is, as a reason would name it: "the autos' card"
   * @return whose card has the name already, or empty when this card has it now
   */
  public Optional<String> take(String name, String whose) {
    return Optional.ofNullable(taken.putIfAbsent(name, whose));
  }
}
