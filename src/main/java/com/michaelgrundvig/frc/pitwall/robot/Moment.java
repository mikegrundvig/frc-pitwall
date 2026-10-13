package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
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
 * @param fired the bindings a press or a release started in this loop, each as the robot's list of
 *     its bindings says it
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
