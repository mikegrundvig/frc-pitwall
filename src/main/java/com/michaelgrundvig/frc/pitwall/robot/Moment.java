package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.BindingFired;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.wpilib.math.geometry.Pose3d;

/**
 * One loop of the robot, as much of it as the Robot view and the World view show: compact, typed,
 * and never changed once made. Each view is a function of the robot's definition and one of these,
 * and the World view of the field too.
 *
 * <p>Numbers are in the model's units: radians or meters for where a joint is, and those a second
 * for how fast. A number the record did not have is NaN.
 *
 * @param timestampNanos when the loop was, on the robot's clock, in nanoseconds
 * @param enabled whether the robot was enabled
 * @param mode the mode the Driver Station chose
 * @param joints each driven joint the record has, by its label, in the model's order
 * @param sensors each switch and rangefinder the record has, by its label
 * @param gates each gate, by its name, in the order the record first had them
 * @param fired the bindings a press or a release started in this loop, each with the control on the
 *     gamepad that fired it
 * @param gamepads the gamepads, by port from 0
 * @param world where the robot was on the field, and what its cameras saw
 */
public record Moment(
    long timestampNanos,
    Enabled enabled,
    Mode mode,
    List<OfJoint> joints,
    List<OfSensor> sensors,
    List<OfGate> gates,
    List<Fired> fired,
    List<OfGamepad> gamepads,
    OfWorld world) {
  public Moment {
    joints = List.copyOf(joints);
    sensors = List.copyOf(sensors);
    gates = List.copyOf(gates);
    fired = List.copyOf(fired);
    gamepads = List.copyOf(gamepads);
  }

  /** Whether the robot may move. */
  public enum Enabled {
    ENABLED,
    DISABLED
  }

  /** The mode the Driver Station chose. */
  public enum Mode {
    AUTONOMOUS,
    TELEOPERATED,
    UTILITY,
    UNKNOWN
  }

  /** Whether something that is read answered in this loop. */
  public enum Link {
    /** It answered, and its reading is this loop's. */
    ANSWERS,
    /** It is connected, and its reading is an earlier loop's: a status frame did not come. */
    STALE,
    /** It did not answer. */
    LOST
  }

  /** What a switch read. */
  public enum Switched {
    TRIPPED,
    CLEAR
  }

  /** Whether a gate held. */
  public enum Held {
    HOLDS,
    DOES_NOT_HOLD
  }

  /** How an absolute encoder the joint has was doing, as far as the record says. */
  public enum Encoder {
    /** The record says nothing against it. */
    HEALTHY,
    /** Its magnet reads badly: too near, too far, or out of line. */
    MAGNET_BAD,
    /** The robot reads it itself, and it gave no reading. */
    LOST
  }

  /**
   * One joint in one loop.
   *
   * @param label the joint's label, as the model names it
   * @param link whether its motor controller answered
   * @param position where it is
   * @param velocity how fast it moves
   * @param appliedVolts the voltage its motor is given
   * @param goal where it is headed or held, or the number it was asked to run at
   * @param setpoint where its profile has it
   * @param setpointVelocity how fast its profile has it moving
   * @param absoluteAt where its absolute encoder says it is
   * @param encoder how its absolute encoder was doing
   * @param state where it stands in knowing where it is
   * @param fault the text of its fault, empty while it is not faulted
   * @param owner who asked it this loop
   * @param command the name of the command that asked, empty when none did
   * @param goalName the name of what it was asked for, or empty
   * @param at where it is among its named places, or what it runs at
   * @param heldBy what holds it short of what it was asked, in the order it is applied
   * @param lastMove how the last move on it ended
   */
  public record OfJoint(
      String label,
      Link link,
      double position,
      double velocity,
      double appliedVolts,
      double goal,
      double setpoint,
      double setpointVelocity,
      double absoluteAt,
      Encoder encoder,
      JointState state,
      String fault,
      JointOwner owner,
      String command,
      String goalName,
      JointPlace at,
      List<Holder> heldBy,
      LastMove lastMove) {
    public OfJoint {
      heldBy = List.copyOf(heldBy);
    }
  }

  /**
   * How the last move on a joint ended, as the robot logged it: every joint's part of the move, and
   * what is logged beside them. The model's {@code MoveSentences.outcome} makes its sentence.
   *
   * @param name the move's name
   * @param parts each joint's part of the move, in the move's order; empty before any move ended
   * @param why the text of the move's reason, where it has one
   * @param heldAsItEnded what held the move's joints as it timed out
   */
  public record LastMove(
      String name, List<MoveOutcome> parts, String why, List<Holder> heldAsItEnded) {
    /** No move has ended on the joint yet. */
    public static final LastMove NONE = new LastMove("", List.of(), "", List.of());

    public LastMove {
      parts = List.copyOf(parts);
      heldAsItEnded = List.copyOf(heldAsItEnded);
    }

    /** Whether any move has ended on the joint. */
    public boolean hasEnded() {
      return !parts.isEmpty();
    }
  }

  /**
   * One switch or rangefinder in one loop.
   *
   * @param label the sensor's label, as the model names it
   * @param link whether it answered: {@link Link#ANSWERS} or {@link Link#LOST}
   * @param switched what a switch read; for a rangefinder it means nothing
   * @param meters what a rangefinder read; NaN for a switch, and for nothing in range
   */
  public record OfSensor(String label, Link link, Switched switched, double meters) {}

  /**
   * One gate in one loop.
   *
   * @param name the gate's name
   * @param held whether it held
   * @param blocking the terms that kept it from holding; empty while it held, or it has none
   * @param blockingText the text those terms point into: a team's own words, or what was thrown
   */
  public record OfGate(String name, Held held, List<GateTerm> blocking, List<String> blockingText) {
    public OfGate {
      blocking = List.copyOf(blocking);
      blockingText = List.copyOf(blockingText);
    }
  }

  /**
   * One binding that a press or a release started in one loop.
   *
   * @param binding the binding, as the robot's list of its bindings says it: "operator right
   *     trigger: while held: Intake"
   * @param port the Driver Station port of the gamepad it is on: 0 for the driver's, 1 for the
   *     operator's
   * @param control what kind of control on the gamepad started it
   * @param button for a button, its index as WPILib's gamepad numbers its buttons; {@code
   *     Identity.NONE} for every other control
   */
  public record Fired(String binding, int port, BindingFired.Control control, int button) {}

  /**
   * One gamepad in one loop.
   *
   * @param port its port on the Driver Station
   * @param buttons its buttons, one bit each from the lowest: bit 0 is button 0
   * @param axes its axes, each from -1 to 1
   * @param pov its first POV, as WPILib numbers its directions: 0 while it is centered; -1 when the
   *     record has none
   */
  public record OfGamepad(int port, long buttons, List<Double> axes, int pov) {
    public OfGamepad {
      axes = List.copyOf(axes);
    }

    /** Whether a button is down. */
    public boolean isDown(int button) {
      return (buttons >>> button & 1) == 1;
    }
  }

  /**
   * Where the robot was on the field in one loop, as far as the record says: where localization
   * believed it was, where it really was in the simulator, how its wheels stood, and what each
   * camera saw.
   *
   * @param estimate where localization believed the robot was; empty when the record has no pose
   * @param truth where the robot really was, which only a simulator's record has
   * @param modules each swerve module's measured speed and angle, in the drivetrain's order
   * @param cameras each camera of the robot's definition, in its order, with what it saw
   */
  public record OfWorld(
      Optional<OfEstimate> estimate,
      Optional<Pose3d> truth,
      List<OfModule> modules,
      List<OfCamera> cameras) {
    /** A record that says nothing of where the robot was. */
    public static final OfWorld NONE =
        new OfWorld(Optional.empty(), Optional.empty(), List.of(), List.of());

    public OfWorld {
      modules = List.copyOf(modules);
      cameras = List.copyOf(cameras);
    }
  }

  /** What the estimate rested on, as the localizer's own mode names it. */
  public enum Rests {
    /** The localizer does not say. */
    NOT_REPORTED,
    /** Not yet placed or seen: the pose is a placeholder. */
    INITIALIZING,
    /** Recent camera measurements agree with odometry. */
    TRACKING,
    /** No recent camera measurement: odometry alone. */
    DEAD_RECKONING,
    /** The cameras agree with each other and not with the estimate. */
    LOST,
    /** The gyro is lost: heading comes from the wheels. */
    IMU_FAULT;

    /** In the page's words: "dead reckoning". */
    public String words() {
      return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Whether the estimate is not to be trusted as drawn: it is lost, or the gyro is. */
    public boolean isWrong() {
      return this == LOST || this == IMU_FAULT;
    }
  }

  /**
   * Where localization believed the robot was in one loop.
   *
   * @param pose the estimated pose, raised to the ground under it and tilted as the IMU said
   * @param spread how sure the pose was, where the localizer says
   * @param rests what the pose rested on
   * @param tally how the cameras' measurements had fared so far, where the localizer says
   * @param recoveries how often the estimate had jumped to agree with its cameras; -1 where the
   *     localizer does not say
   * @param placements how often the robot had been placed
   * @param placedBecause why it was last placed, as the robot program says it: "placed on Center"
   */
  public record OfEstimate(
      Pose3d pose,
      Optional<Spread> spread,
      Rests rests,
      Optional<Tally> tally,
      int recoveries,
      int placements,
      String placedBecause) {}

  /**
   * How sure a pose is: the covariance of its position and the variance of its heading.
   *
   * @param xx the variance along the field's x, in square meters
   * @param xy the covariance of x and y
   * @param yy the variance along y
   * @param heading the variance of the heading, in square radians; infinite when unknown
   */
  public record Spread(double xx, double xy, double yy, double heading) {
    /**
     * The position's spread along its most uncertain direction, in meters: the square root of the
     * larger eigenvalue of {@code [xx xy; xy yy]}.
     */
    public double largestStdDev() {
      return Math.sqrt((xx + yy) / 2 + Math.hypot((xx - yy) / 2, xy));
    }
  }

  /**
   * How the cameras' measurements had fared since the robot started.
   *
   * @param accepted how many the estimator accepted
   * @param rejected how many it rejected
   * @param visionAgeSeconds how long ago the newest image the estimate rests on was taken; infinite
   *     before any
   */
  public record Tally(int accepted, int rejected, double visionAgeSeconds) {}

  /**
   * One swerve module in one loop.
   *
   * @param metersPerSecond its wheel's measured speed
   * @param radians its measured angle
   */
  public record OfModule(double metersPerSecond, double radians) {}

  /**
   * One camera in one loop: the tags its images saw in this loop, and those it measured a moment
   * before ({@link Sighted#loopsAgo}), newest first.
   *
   * @param name the camera's name, as the robot's definition has it
   * @param sightings its tags, newest first
   * @param dropped how many of this loop's tags the memory had no room for
   */
  public record OfCamera(String name, List<Sighted> sightings, int dropped) {
    public OfCamera {
      sightings = List.copyOf(sightings);
    }
  }

  /**
   * One tag as a camera saw it, with what became of its measurement.
   *
   * @param tag the tag's number
   * @param fate what became of it
   * @param verdict why, by the verdict's number in the robot program's list ({@link SightingWords})
   * @param solver how its image was solved, by the solver's number; -1 for none
   * @param image which of its loop's images of the camera saw it
   * @param loopsAgo how many loops before this one it was seen: 0 for this loop's
   * @param value the verdict's own number; NaN where it has none
   * @param threshold the limit that number was held to; NaN where it has none
   * @param innovationMeters how far the measurement was from the estimate; NaN if not compared
   * @param chiSquared its χ² against the estimate; NaN if not compared
   * @param measuredX the measured pose's x, in meters; NaN for an image that was left out
   * @param measuredY the measured pose's y
   * @param measuredHeading the measured pose's heading, in radians
   */
  public record Sighted(
      int tag,
      Sighting.Fate fate,
      int verdict,
      int solver,
      int image,
      int loopsAgo,
      double value,
      double threshold,
      double innovationMeters,
      double chiSquared,
      double measuredX,
      double measuredY,
      double measuredHeading) {
    /** Whether the image became a measurement the estimator was given: a sight line is drawn. */
    public boolean measured() {
      return fate == Sighting.Fate.ACCEPTED
          || fate == Sighting.Fate.REJECTED
          || fate == Sighting.Fate.GIVEN;
    }
  }

  /** The joint with a label, or null when the record has none. */
  public @org.jspecify.annotations.Nullable OfJoint joint(String label) {
    for (OfJoint joint : joints) {
      if (joint.label().equals(label)) {
        return joint;
      }
    }
    return null;
  }

  /** The camera with a name, or null when the record has none. */
  public @org.jspecify.annotations.Nullable OfCamera camera(String name) {
    for (OfCamera camera : world.cameras()) {
      if (camera.name().equals(name)) {
        return camera;
      }
    }
    return null;
  }

  /** The sensor with a label, or null when the record has none. */
  public @org.jspecify.annotations.Nullable OfSensor sensor(String label) {
    for (OfSensor sensor : sensors) {
      if (sensor.label().equals(label)) {
        return sensor;
      }
    }
    return null;
  }
}
