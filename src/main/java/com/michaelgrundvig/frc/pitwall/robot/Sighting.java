package com.michaelgrundvig.frc.pitwall.robot;

/**
 * One tag in one camera image, and what became of it: a fact the robot program logs for each of a
 * camera's tags ({@link LogKeys#SIGHTINGS}), as the model's records are logged. It holds no text. A
 * tag is known by its number on the field, and a verdict and a solver by their numbers in the robot
 * program's own lists, which {@link SightingWords} says in words.
 *
 * <p>A sighting's measurements change with every image, so they are logged beside it as plain
 * numbers ({@link LogKeys#SIGHTING_NUMBERS}), {@link #NUMBERS} for each sighting in the sightings'
 * order. The facts here change only when a camera sees other tags, or its tags fare otherwise.
 *
 * @param tag the tag's number, as the field's layout has it
 * @param image which of the loop's images of this camera saw it, counted from 0: a camera faster
 *     than the robot's loop hands over two in some loops
 * @param fate what became of it
 * @param verdict why, by the verdict's number in the robot program's list of verdicts
 * @param solver how the image was solved, by the solver's number in the robot program's list; -1
 *     for an image that never became a measurement
 */
public record Sighting(int tag, int image, Fate fate, int verdict, int solver) {
  /** How many numbers are logged beside each sighting. */
  public static final int NUMBERS = 7;

  /** Among a sighting's numbers: the verdict's own number, such as a χ² or a distance. */
  public static final int VALUE = 0;

  /** Among a sighting's numbers: the limit the verdict's number was held to. */
  public static final int THRESHOLD = 1;

  /** Among a sighting's numbers: how far the measurement was from the estimate, in meters. */
  public static final int INNOVATION_METERS = 2;

  /** Among a sighting's numbers: the measurement's χ² against the estimate. */
  public static final int CHI_SQUARED = 3;

  /** Among a sighting's numbers: the measured pose's x on the field, in meters. */
  public static final int MEASURED_X = 4;

  /** Among a sighting's numbers: the measured pose's y on the field, in meters. */
  public static final int MEASURED_Y = 5;

  /** Among a sighting's numbers: the measured pose's heading, in radians. */
  public static final int MEASURED_HEADING = 6;

  /** What became of a tag's measurement. */
  public enum Fate {
    /** The measurement moved the estimate. */
    ACCEPTED,
    /** The estimator weighed the measurement and refused it. */
    REJECTED,
    /** The measurement went to a localizer that does not report what became of it. */
    GIVEN,
    /**
     * The image was refused before it became a measurement: there is no measured pose, and nothing
     * was compared with the estimate.
     */
    LEFT_OUT,
    /**
     * The robot held the image back on purpose, while it calibrates the camera or trusts a placed
     * start: nothing is wrong with the image.
     */
    HELD_BACK
  }
}
