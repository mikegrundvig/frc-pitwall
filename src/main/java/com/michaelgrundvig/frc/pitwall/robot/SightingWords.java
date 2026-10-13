package com.michaelgrundvig.frc.pitwall.robot;

/**
 * The robot program's own words for what a {@link Sighting} holds as numbers. A verdict and a
 * solver are each one of a list the robot program has, and the sentence for one is made where that
 * list is, by the function that makes it for the console and the log. The robot program hands that
 * function to the session ({@link RobotSession#of}), so the page says a verdict exactly as the log
 * does, and nothing here words one a second time.
 */
public interface SightingWords {
  /** Words for a robot program that logs no sightings: every one is said as nothing. */
  SightingWords NONE =
      new SightingWords() {
        @Override
        public String verdict(int verdict, double value, double threshold) {
          return "";
        }

        @Override
        public String solver(int solver) {
          return "";
        }
      };

  /**
   * A verdict with its numbers: "accepted", "gate: 45.2 > 13.8", "too far: 6.3 m".
   *
   * @param verdict the verdict's number in the robot program's list
   * @param value the verdict's own number: a χ², a distance, a turn rate
   * @param threshold the limit that number was held to
   */
  String verdict(int verdict, double value, double threshold);

  /**
   * A solver's name as the robot program has it: "MULTI_TAG".
   *
   * @param solver the solver's number in the robot program's list
   */
  String solver(int solver);
}
