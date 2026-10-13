package com.michaelgrundvig.frc.pitwall.robot;

import java.util.List;

/**
 * One loop of the robot, as much of it as the Robot view shows: compact, typed, and never changed
 * once made. The view is a function of the robot's definition and one of these.
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
 * @param fired the bindings a press or a release started in this loop, each in its own words
 * @param gamepads the gamepads, by port from 0
 */
public record Moment(
    long timestampNanos,
    Enabled enabled,
    Mode mode,
    List<OfJoint> joints,
    List<OfSensor> sensors,
    List<OfGate> gates,
    List<String> fired,
    List<OfGamepad> gamepads) {
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
   * @param state where it stands, as the runtime's {@code State} says it
   * @param command who asked it this loop, as the runtime's {@code Command} says it
   * @param goalName the name of what it was asked for, or empty
   * @param outcome how the last move on it ended, as the runtime's {@code Outcome} says it
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
      String state,
      String command,
      String goalName,
      String outcome) {}

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
   * @param blocking the terms that kept it from holding, in words; empty while it held
   */
  public record OfGate(String name, Held held, List<String> blocking) {
    public OfGate {
      blocking = List.copyOf(blocking);
    }
  }

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

  /** The joint with a label, or null when the record has none. */
  public @org.jspecify.annotations.Nullable OfJoint joint(String label) {
    for (OfJoint joint : joints) {
      if (joint.label().equals(label)) {
        return joint;
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
