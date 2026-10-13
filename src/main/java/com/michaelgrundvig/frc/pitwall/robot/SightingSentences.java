package com.michaelgrundvig.frc.pitwall.robot;

import java.util.Locale;

/**
 * Says a camera's sighting of a tag in words, from the moment that holds it: which camera saw which
 * tag, and what became of it. The World view's sight lines and the session's events both say it
 * with these, so a line clicked and an event listed read the same. The verdict itself is in the
 * robot program's words ({@link SightingWords}).
 */
public final class SightingSentences {
  private SightingSentences() {}

  /** Which camera saw which tag: "front-left → tag 7". */
  public static String title(String camera, int tag) {
    return camera + " → tag " + tag;
  }

  /**
   * What became of a sighting: "accepted", or "rejected: gate: 45.2 > 13.8". A measurement given to
   * a localizer that does not report what became of it says so.
   */
  public static String verdict(Moment.Sighted sighted, SightingWords words) {
    return switch (sighted.fate()) {
      case ACCEPTED -> "accepted";
      case GIVEN -> "not reported by this localizer: the measurement was given to it";
      case REJECTED, LEFT_OUT, HELD_BACK ->
          "rejected: " + words.verdict(sighted.verdict(), sighted.value(), sighted.threshold());
    };
  }

  /** A distance to two places of meters, or that it wasn't measured. */
  public static String meters(double meters) {
    if (!Double.isFinite(meters)) {
      return "not measured";
    }
    return String.format(Locale.ROOT, "%.2f m", meters);
  }
}
