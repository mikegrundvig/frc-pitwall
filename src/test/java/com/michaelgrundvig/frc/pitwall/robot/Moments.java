package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointPlaces;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
import com.michaelgrundvig.frc.world.record.MoveProgress;
import com.michaelgrundvig.frc.world.record.MoveReason;
import com.michaelgrundvig.frc.world.record.MoveResult;
import com.michaelgrundvig.frc.world.record.MoveTarget;
import com.michaelgrundvig.frc.world.record.MoveTask;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moments built by hand for the Robot view's tests: the test robot enabled in teleop with every
 * joint ready, idle and answering where it rests, every sensor clear, and then whatever a test
 * changes, one joint or sensor at a time. Each joint's facts are as the runtime would log them:
 * where it is among its places is worked out by the model, and what holds it is what a test says
 * the robot logged.
 */
final class Moments {
  /** The test robot's interlocks, by their index in its definition's {@code everyInterlock()}. */
  static final Holder ROLLER_RULE = Holder.interlock(0);

  static final Holder ARM_KEEP_OUT = Holder.interlock(1);
  static final Holder CLAW_RULE = Holder.interlock(2);

  private static final RobotDefinition ROBOT = TestRobot.definition();

  /** One joint as a test has set it so far. */
  private static final class OfJoint {
    private Moment.Link link = Moment.Link.ANSWERS;
    private double position;
    private double velocity;
    private double goal;
    private JointState state = JointState.READY;
    private String fault = "";
    private Moment.Encoder encoder = Moment.Encoder.HEALTHY;
    private JointOwner owner = JointOwner.IDLE;
    private String command = "";
    private String goalName = "";
    private List<Holder> heldBy = List.of();
    private Moment.LastMove lastMove = Moment.LastMove.NONE;
  }

  private Moment.Enabled enabled = Moment.Enabled.ENABLED;
  private Moment.Mode mode = Moment.Mode.TELEOPERATED;
  private final Map<String, OfJoint> joints = new LinkedHashMap<>();
  private final Map<String, Moment.OfSensor> sensors = new LinkedHashMap<>();
  private final List<Moment.OfGate> gates = new ArrayList<>();
  private final List<String> fired = new ArrayList<>();

  private Moments() {
    for (Joint joint : ROBOT.joints()) {
      joints.put(joint.label(), new OfJoint());
    }
    at("pivot", 1.5);
    at("arm", -45);
    sensors.put(
        "claw beam",
        new Moment.OfSensor("claw beam", Moment.Link.ANSWERS, Moment.Switched.CLEAR, Double.NaN));
    sensors.put(
        "hopper", new Moment.OfSensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.8));
  }

  /** The test robot at rest, enabled. */
  static Moments atRest() {
    return new Moments();
  }

  Moments disabled() {
    enabled = Moment.Enabled.DISABLED;
    return this;
  }

  Moments enabled() {
    enabled = Moment.Enabled.ENABLED;
    return this;
  }

  Moments mode(Moment.Mode chosen) {
    mode = chosen;
    return this;
  }

  /** A joint that turns, holding at an angle in degrees. */
  Moments at(String label, double degrees) {
    joint(label).position = Math.toRadians(degrees);
    joint(label).goal = Math.toRadians(degrees);
    return this;
  }

  /** A joint that slides, holding at a length in meters. */
  Moments atMeters(String label, double meters) {
    joint(label).position = meters;
    joint(label).goal = meters;
    return this;
  }

  /** A joint run at speeds, turning at a speed in rpm. */
  Moments running(String label, double rpm) {
    joint(label).velocity = rpm * 2 * Math.PI / 60;
    return this;
  }

  /**
   * A command asks a joint for something by name: a place it moves to, or an output it runs at.
   *
   * @param goal where the joint is headed or held, in degrees, or the number it was asked to run at
   *     in rpm: what the runtime logs as its goal
   */
  Moments asked(String label, String command, String goalName, double goal) {
    OfJoint joint = joint(label);
    joint.owner = JointOwner.COMMAND;
    joint.command = command;
    joint.goalName = goalName;
    boolean runs = !jointNamed(label).holdsPositions();
    joint.goal = runs ? goal * 2 * Math.PI / 60 : Math.toRadians(goal);
    return this;
  }

  /** Nothing asks anything of a joint any more: it is idle, where it is. */
  Moments idle(String label) {
    OfJoint joint = joint(label);
    joint.owner = JointOwner.IDLE;
    joint.command = "";
    joint.goalName = "";
    return this;
  }

  /** What the robot logged as holding a joint. */
  Moments heldBy(String label, Holder... holders) {
    joint(label).heldBy = List.of(holders);
    return this;
  }

  /** A joint's motor controller answers as given, and the joint stands as given. */
  Moments standing(String label, Moment.Link link, JointState state) {
    joint(label).link = link;
    joint(label).state = state;
    return this;
  }

  /** A joint is faulted for a reason, with the text of its fault as the robot logged it. */
  Moments faulted(String label, JointState.Fault reason, String fault) {
    joint(label).state = JointState.faulted(reason);
    joint(label).fault = fault;
    return this;
  }

  /** How a joint's absolute encoder is doing. */
  Moments encoder(String label, Moment.Encoder encoder) {
    joint(label).encoder = encoder;
    return this;
  }

  /** The last move on a joint arrived at one of its places, in so many seconds. */
  Moments arrivedAt(String label, String place, double seconds) {
    return ended(label, place, MoveResult.ARRIVED, seconds);
  }

  /**
   * The last move on a joint, to one of its places, ended as given after so many seconds, with the
   * joint where the moment has it.
   */
  Moments ended(String label, String place, MoveResult result, double seconds) {
    joint(label).lastMove =
        new Moment.LastMove(
            label + " → " + place, List.of(part(label, place, result, seconds)), "", List.of());
    return this;
  }

  /** The last move on two joints, each to one of its places, arrived in so many seconds. */
  Moments arrivedTogether(
      String name, String one, String onesPlace, String other, String othersPlace, double seconds) {
    Moment.LastMove move =
        new Moment.LastMove(
            name,
            List.of(
                part(one, onesPlace, MoveResult.ARRIVED, seconds),
                part(other, othersPlace, MoveResult.ARRIVED, seconds)),
            "",
            List.of());
    joint(one).lastMove = move;
    joint(other).lastMove = move;
    return this;
  }

  /** One joint's part of a move to one of its places. */
  private MoveOutcome part(String label, String place, MoveResult result, double seconds) {
    Joint joint = jointNamed(label);
    int index = 0;
    while (!joint.positions().get(index).name().equals(place)) {
      index++;
    }
    double target = joint.positions().get(index).value();
    boolean arrived = result == MoveResult.ARRIVED;
    return new MoveOutcome(
        result,
        MoveTask.GO_TO,
        MoveProgress.BEGUN,
        seconds,
        ROBOT.joints().indexOf(joint),
        Identity.NONE,
        MoveTarget.PLACE,
        index,
        target,
        arrived ? target : joint(label).position,
        0,
        arrived ? seconds : Double.NaN,
        MoveReason.NONE,
        Identity.NONE);
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

  /** A gate, with what keeps it from holding in a team's own words. */
  Moments gate(String name, Moment.Held held, String... blocking) {
    List<GateTerm> terms = new ArrayList<>();
    for (int each = 0; each < blocking.length; each++) {
      terms.add(GateTerm.text(each));
    }
    gates.removeIf(gate -> gate.name().equals(name));
    gates.add(new Moment.OfGate(name, held, terms, List.of(blocking)));
    return this;
  }

  Moments fired(String binding) {
    fired.add(binding);
    return this;
  }

  /** No binding fires: a firing lasts one loop. */
  Moments firedNothing() {
    fired.clear();
    return this;
  }

  private OfJoint joint(String label) {
    OfJoint joint = joints.get(label);
    if (joint == null) {
      throw new IllegalArgumentException("the moment has no joint " + label);
    }
    return joint;
  }

  private static Joint jointNamed(String label) {
    return ROBOT.joints().stream()
        .filter(joint -> joint.label().equals(label))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Where a joint is among its places, as the runtime logs it: by the model, within 2° or 1 cm of a
   * place; unknown while it does not know; and for a joint that only runs, what it runs at.
   */
  private static JointPlace placeOf(Joint joint, OfJoint now) {
    if (!joint.holdsPositions()) {
      for (int output = 0; output < joint.outputs().size(); output++) {
        if (joint.outputs().get(output).name().equals(now.goalName)) {
          return JointPlace.running(output);
        }
      }
      return JointPlace.STOPPED;
    }
    if (now.state.standing() != JointState.Standing.READY) {
      return JointPlace.UNKNOWN;
    }
    double band = joint instanceof Joint.Revolute ? Math.toRadians(2) : 0.01;
    return new JointPlaces(joint, band).at(now.position);
  }

  Moment build() {
    List<Moment.OfJoint> built = new ArrayList<>();
    for (Map.Entry<String, OfJoint> entry : joints.entrySet()) {
      OfJoint now = entry.getValue();
      built.add(
          new Moment.OfJoint(
              entry.getKey(),
              now.link,
              now.position,
              now.velocity,
              0,
              now.goal,
              now.position,
              0,
              Double.NaN,
              now.encoder,
              now.state,
              now.fault,
              now.owner,
              now.command,
              now.goalName,
              placeOf(jointNamed(entry.getKey()), now),
              now.heldBy,
              now.lastMove));
    }
    return new Moment(
        1_000_000_000L,
        enabled,
        mode,
        built,
        List.copyOf(sensors.values()),
        gates,
        fired,
        List.of(new Moment.OfGamepad(0, 0, List.of(0.0, 0.0), -1)));
  }

  /** The test robot's view of this moment. */
  RobotView view() {
    return RobotView.of(ROBOT, build());
  }
}
