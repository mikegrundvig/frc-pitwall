package com.michaelgrundvig.frc.pitwall.robot;

/**
 * The log keys the Robot view and the World view read. This is the contract with the runtime: the
 * runtime writes these keys every loop, and Pitwall reads them from each loop's record. A key
 * renamed on one side must be renamed here.
 *
 * <p>Each key is written as the log names it, from its root, with the slash first: {@code
 * /Joints/pivot/Position}. AdvantageKit's record is looked up by exactly these, so reading one
 * builds no text.
 *
 * <p>What the robot did is logged as facts, the model's records ({@code
 * com.michaelgrundvig.frc.world.record}), each written only when it changes. A loop's record still
 * holds every key's latest value, so each is read every loop.
 *
 * <p>AdvantageKit puts a program's inputs under their own keys and its outputs under {@link
 * #OUTPUTS}. In a replay the outputs are under {@code /ReplayOutputs/}, which Pitwall never reads,
 * because its page is not served while replaying.
 */
public final class LogKeys {
  private LogKeys() {}

  /** Where AdvantageKit puts everything a program records as an output. */
  public static final String OUTPUTS = "/RealOutputs/";

  // ---- The Driver Station, as AdvantageKit's LoggedDriverStation logs it ----

  /** Whether the robot is enabled: a boolean. */
  public static final String ENABLED = "/DriverStation/Enabled";

  /** The mode the Driver Station chose, by its name: "AUTONOMOUS", "TELEOPERATED", "UTILITY". */
  public static final String ROBOT_MODE = "/DriverStation/RobotMode";

  /** Whether a Driver Station is attached: a boolean. */
  public static final String DRIVER_STATION_ATTACHED = "/DriverStation/DSAttached";

  /** Whether the robot is stopped by its emergency stop: a boolean. */
  public static final String EMERGENCY_STOP = "/DriverStation/EmergencyStop";

  /** A gamepad's keys, under its port: {@code /DriverStation/Joystick0}. */
  public static String gamepad(int port) {
    return "/DriverStation/Joystick" + port;
  }

  /** Under a gamepad: its buttons, one bit each from the lowest: an integer. */
  public static final String BUTTONS = "/ButtonValues";

  /** Under a gamepad: its axes, each from -1 to 1: floats. */
  public static final String AXES = "/AxisValues";

  /** Under a gamepad: its POVs: integers. */
  public static final String POVS = "/POVValues";

  // ---- A joint: its inputs under /Joints/<label>, its outputs under /RealOutputs/Joints/<label>

  /** A joint's inputs, under its label: {@code /Joints/pivot}. */
  public static String joint(String label) {
    return "/Joints/" + label;
  }

  /** A joint's outputs, under its label: {@code /RealOutputs/Joints/pivot}. */
  public static String jointOutputs(String label) {
    return OUTPUTS + "Joints/" + label;
  }

  /** Under a joint's inputs: whether its motor controller answers: a boolean. */
  public static final String CONNECTED = "/Connected";

  /** Under a joint's inputs: whether its reading is this loop's: a boolean. */
  public static final String FRESH = "/Fresh";

  /** Under a joint's inputs: where it is, in its own units (radians or meters). */
  public static final String POSITION = "/Position";

  /** Under a joint's inputs: how fast it moves, in its own units a second. */
  public static final String VELOCITY = "/Velocity";

  /** Under a joint's inputs: the voltage its motor is given. */
  public static final String APPLIED_VOLTS = "/AppliedVolts";

  /** Under a joint's inputs: whether its absolute encoder's magnet reads badly: a boolean. */
  public static final String MAGNET_BAD = "/MagnetBad";

  /**
   * Under a joint's inputs: its absolute encoder, read by the robot: {@code /Absolute/Connected}.
   */
  public static final String ABSOLUTE_CONNECTED = "/Absolute/Connected";

  /** Under a joint's outputs: where it stands: a {@code JointState}. */
  public static final String STATE = "/State";

  /** Under a joint's outputs: the text of its fault, empty while it is not faulted. */
  public static final String FAULT = "/Fault";

  /** Under a joint's outputs: who asked it this loop: a {@code JointOwner}, by its name. */
  public static final String OWNER = "/Owner";

  /** Under a joint's outputs: the name of the command that asked it, empty when none did. */
  public static final String COMMAND = "/Command";

  /** Under a joint's outputs: where it is headed or held, or the number it was asked to run at. */
  public static final String GOAL = "/Goal";

  /** Under a joint's outputs: the name of what it was asked for ("out"), or empty. */
  public static final String GOAL_NAME = "/GoalName";

  /** Under a joint's outputs: where it is among its places: a {@code JointPlace}. */
  public static final String AT = "/At";

  /** Under a joint's outputs: what holds it short of what it was asked: {@code Holder}s. */
  public static final String HELD_BY = "/HeldBy";

  /** Under a joint's outputs: where its profile has it this loop. */
  public static final String SETPOINT = "/Setpoint";

  /** Under a joint's outputs: how fast its profile has it moving this loop. */
  public static final String SETPOINT_VELOCITY = "/SetpointVelocity";

  /**
   * Under a joint's outputs: how the last move on it ended, a {@code MoveOutcome} for each joint
   * the move moved; empty before any has.
   */
  public static final String OUTCOME = "/Outcome";

  /** Under a joint's outputs: the name of that move. */
  public static final String OUTCOME_NAME = "/OutcomeName";

  /** Under a joint's outputs: the text of that move's reason, where it has one. */
  public static final String OUTCOME_WHY = "/OutcomeWhy";

  /** Under a joint's outputs: what held the move's joints as it timed out: {@code Holder}s. */
  public static final String OUTCOME_HELD_BY = "/OutcomeHeldBy";

  /** Under a joint's outputs: where its absolute encoder says it is, in its own units. */
  public static final String ABSOLUTE_AT = "/AbsoluteAt";

  // ---- A sensor: its inputs under /Sensors/<label> ----

  /** A sensor's inputs, under its label: {@code /Sensors/claw beam}. */
  public static String sensor(String label) {
    return "/Sensors/" + label;
  }

  /** Under a sensor: whether it answers: a boolean. */
  public static final String SENSOR_CONNECTED = "/Connected";

  /** Under a sensor: a switch's reading, whether it is tripped: a boolean. */
  public static final String TRIPPED = "/Tripped";

  /** Under a sensor: a rangefinder's reading, in meters. */
  public static final String METERS = "/Meters";

  // ---- Gates and bindings ----

  /** Where every gate logs whether it holds, each under its name: a boolean. */
  public static final String GATES = OUTPUTS + "Gates/";

  /** Under a gate: the terms that keep it from holding: {@code GateTerm}s. */
  public static final String BLOCKING = "/Blocking";

  /** Under a gate: the text its terms point into: strings. */
  public static final String BLOCKING_TEXT = "/BlockingText";

  /** The bindings a press or a release started this loop: {@code BindingFired}s. */
  public static final String FIRED = OUTPUTS + "Controls/Fired";

  /** Every standing binding in its own words, which a fired binding points into: strings. */
  public static final String BINDINGS = OUTPUTS + "Controls/Bindings";

  // ---- Where the robot is: localization, the drivetrain, the cameras, and the simulator ----

  /**
   * The estimated pose, raised to the ground under it and tilted as the IMU says: a {@code Pose3d},
   * which is seven numbers (x, y, z, and its rotation's w, x, y, z).
   */
  public static final String ESTIMATED_POSE = OUTPUTS + "Localization/Pose3d";

  /**
   * How sure the estimate is, where the localizer says: the position's covariance and the heading's
   * variance, four numbers (xx, xy, yy, heading) in square meters and square radians.
   */
  public static final String COVARIANCE = OUTPUTS + "Localization/Covariance";

  /** What the estimate rests on, by its name ("TRACKING"), where the localizer says. */
  public static final String RESTS_ON = OUTPUTS + "Localization/Mode";

  /** How many camera measurements the estimator has accepted: a whole number. */
  public static final String MEASUREMENTS_ACCEPTED = OUTPUTS + "Localization/Accepted";

  /** How many camera measurements the estimator has rejected: a whole number. */
  public static final String MEASUREMENTS_REJECTED = OUTPUTS + "Localization/Rejected";

  /** How long ago the newest image the estimate rests on was taken, in seconds. */
  public static final String VISION_AGE = OUTPUTS + "Localization/VisionAgeSeconds";

  /** How often the estimate has jumped to agree with its cameras: a whole number. */
  public static final String RECOVERIES = OUTPUTS + "Localization/Recoveries";

  /** How often the robot has been placed: a whole number. */
  public static final String PLACEMENTS = OUTPUTS + "Localization/Placements";

  /** Why the robot was last placed, as the robot program says it: "placed on Center". */
  public static final String PLACED_BECAUSE = OUTPUTS + "Localization/PlacedBecause";

  /**
   * Each swerve module's measured speed and angle, in the drivetrain's order: {@code
   * SwerveModuleVelocity}s, each two numbers (meters a second, radians).
   */
  public static final String MODULES = OUTPUTS + "Drive/ModuleVelocities";

  /** In the simulator, where the robot really is: a {@code Pose3d}, seven numbers. */
  public static final String TRUE_POSE = OUTPUTS + "Sim/TruePose3d";

  /** A camera's outputs, under its name: {@code /RealOutputs/Vision/front-left}. */
  public static String camera(String name) {
    return OUTPUTS + "Vision/" + name;
  }

  /**
   * Under a camera's outputs: the tags of its latest images and what became of each: {@link
   * Sighting}s, written when they change.
   */
  public static final String SIGHTINGS = "/Sightings";

  /**
   * Under a camera's outputs: the measurements of this loop's sightings, {@link Sighting#NUMBERS}
   * numbers for each in the sightings' order, and empty in a loop without an image.
   */
  public static final String SIGHTING_NUMBERS = "/SightingNumbers";
}
