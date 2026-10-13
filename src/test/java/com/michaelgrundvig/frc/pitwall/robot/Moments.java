package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moments built by hand for the Robot view's tests: the test robot enabled in teleop with every
 * joint ready, idle and answering where it rests, every sensor clear, and then whatever a test
 * changes, one joint or sensor at a time.
 */
final class Moments {
  private Moment.Enabled enabled = Moment.Enabled.ENABLED;
  private final Map<String, Moment.OfJoint> joints = new LinkedHashMap<>();
  private final Map<String, Moment.OfSensor> sensors = new LinkedHashMap<>();
  private final List<Moment.OfGate> gates = new ArrayList<>();
  private final List<String> fired = new ArrayList<>();

  private Moments() {
    at("pivot", 1.5);
    running("roller", 0);
    atMeters("elevator", 0);
    at("arm", -45);
    running("shooter", 0);
    running("claw", 0);
    sensors.put("claw beam", clear("claw beam"));
    sensors.put(
        "hopper", new Moment.OfSensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.8));
  }

  /** The test robot at rest, enabled. */
  static Moments atRest() {
    return new Moments();
  }

  private static Moment.OfSensor clear(String label) {
    return new Moment.OfSensor(label, Moment.Link.ANSWERS, Moment.Switched.CLEAR, Double.NaN);
  }

  Moments disabled() {
    enabled = Moment.Enabled.DISABLED;
    return this;
  }

  /** A joint that turns, idle and holding at an angle in degrees. */
  Moments at(String label, double degrees) {
    return put(idle(label, Math.toRadians(degrees), 0, Math.toRadians(degrees)));
  }

  /** A joint that slides, idle and holding at a length in meters. */
  Moments atMeters(String label, double meters) {
    return put(idle(label, meters, 0, meters));
  }

  /** A joint run at speeds, idle and turning at a speed in rpm. */
  Moments running(String label, double rpm) {
    return put(idle(label, 0, rpm * 2 * Math.PI / 60, 0));
  }

  private static Moment.OfJoint idle(String label, double position, double velocity, double goal) {
    return new Moment.OfJoint(
        label,
        Moment.Link.ANSWERS,
        position,
        velocity,
        0,
        goal,
        position,
        0,
        Double.NaN,
        Moment.Encoder.HEALTHY,
        "READY",
        "idle",
        "",
        "");
  }

  /**
   * A command asks a joint for something by name: a place it moves to, or an output it runs at.
   *
   * @param goal where the joint is headed or held, in degrees, or the number it was asked to run at
   *     in rpm: what the runtime logs as its goal
   */
  Moments asked(String label, String command, String goalName, double goal) {
    Moment.OfJoint was = joint(label);
    boolean turnsOrRuns = !label.equals("elevator");
    boolean runs = List.of("roller", "shooter", "claw").contains(label);
    double inUnits = goal;
    if (runs) {
      inUnits = goal * 2 * Math.PI / 60;
    } else if (turnsOrRuns) {
      inUnits = Math.toRadians(goal);
    }
    return put(
        new Moment.OfJoint(
            label,
            was.link(),
            was.position(),
            was.velocity(),
            was.appliedVolts(),
            inUnits,
            was.setpoint(),
            was.setpointVelocity(),
            was.absoluteAt(),
            was.encoder(),
            was.state(),
            command,
            goalName,
            was.outcome()));
  }

  /** A joint's motor controller answers as given, and its state reads as given. */
  Moments standing(String label, Moment.Link link, String state) {
    Moment.OfJoint was = joint(label);
    return put(
        new Moment.OfJoint(
            label,
            link,
            was.position(),
            was.velocity(),
            was.appliedVolts(),
            was.goal(),
            was.setpoint(),
            was.setpointVelocity(),
            was.absoluteAt(),
            was.encoder(),
            state,
            was.command(),
            was.goalName(),
            was.outcome()));
  }

  /** How the last move on a joint ended, as the runtime words it. */
  Moments lastMove(String label, String outcome) {
    Moment.OfJoint was = joint(label);
    return put(
        new Moment.OfJoint(
            label,
            was.link(),
            was.position(),
            was.velocity(),
            was.appliedVolts(),
            was.goal(),
            was.setpoint(),
            was.setpointVelocity(),
            was.absoluteAt(),
            was.encoder(),
            was.state(),
            was.command(),
            was.goalName(),
            outcome));
  }

  /** The record has nothing of a joint: the robot program does not drive it. */
  Moments without(String label) {
    joints.remove(label);
    return this;
  }

  Moments sensor(String label, Moment.Link link, Moment.Switched switched, double meters) {
    sensors.put(label, new Moment.OfSensor(label, link, switched, meters));
    return this;
  }

  Moments gate(String name, Moment.Held held, String... blocking) {
    gates.add(new Moment.OfGate(name, held, List.of(blocking)));
    return this;
  }

  Moments fired(String binding) {
    fired.add(binding);
    return this;
  }

  private Moment.OfJoint joint(String label) {
    Moment.OfJoint joint = joints.get(label);
    if (joint == null) {
      throw new IllegalArgumentException("the moment has no joint " + label);
    }
    return joint;
  }

  private Moments put(Moment.OfJoint joint) {
    joints.put(joint.label(), joint);
    return this;
  }

  Moment build() {
    return new Moment(
        1_000_000_000L,
        enabled,
        Moment.Mode.TELEOPERATED,
        List.copyOf(joints.values()),
        List.copyOf(sensors.values()),
        gates,
        fired,
        List.of(new Moment.OfGamepad(0, 0, List.of(0.0, 0.0), -1)));
  }

  /** The test robot's view of this moment. */
  RobotView view() {
    return RobotView.of(TestRobot.definition(), RobotSession.LOOP_SECONDS, build());
  }
}
