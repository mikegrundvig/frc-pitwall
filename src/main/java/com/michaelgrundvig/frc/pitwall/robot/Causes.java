package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.ConditionReading;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.InterlockEnforcement;
import com.michaelgrundvig.frc.world.robot.InterlockReadings;
import com.michaelgrundvig.frc.world.robot.InterlockVerdict;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.JointAsk;
import com.michaelgrundvig.frc.world.robot.Output;
import com.michaelgrundvig.frc.world.robot.Position;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.wpilib.units.Units;

/**
 * Cause, across the robot, in one moment: what each interlock's condition reads, which interlocks
 * hold their joint, and so how strongly each is to be drawn, on its own joint's card and on the
 * card of what it depends on.
 *
 * <p>Nothing here reads what the robot said held a joint. Each condition is read from the moment's
 * positions and sensors as the runtime reads it, and the model's own {@link InterlockEnforcement}
 * then decides what holds each joint, from what the joint was asked.
 *
 * <p>One moment does not hold what an interlock remembers from the loops before: which side of a
 * keep-out's region the joint was last outside on, and where a hold began. Each decision here
 * therefore starts from the interlocks' first state. A run-only-when is decided exactly so. A
 * keep-out is decided exactly while its joint is outside its region, which is nearly always, and
 * from the nearer edge for a joint found inside it.
 */
final class Causes {
  /** How strongly an interlock is drawn. */
  enum Emphasis {
    /** It restricts nothing now: the condition lets the joint run, or the region is open. */
    QUIET,
    /** It restricts its joint now, and its joint is not asked for anything it stops. */
    IN_FORCE,
    /** It holds its joint short of what a command asked: it is what the joint is waiting for. */
    WAITED_FOR
  }

  private final Map<Interlock, ConditionReading> readings = new IdentityHashMap<>();
  private final Map<Interlock, Emphasis> emphasis = new IdentityHashMap<>();
  private final Map<String, List<Interlock>> holding = new java.util.HashMap<>();

  private Causes() {}

  /** Works out cause for one moment. */
  static Causes of(RobotPlan plan, Moment moment) {
    Causes causes = new Causes();
    for (RobotPlan.OfJoint joint : plan.joints()) {
      causes.decide(joint, moment);
    }
    return causes;
  }

  /** What an interlock's condition reads in this moment. */
  ConditionReading reading(Interlock interlock) {
    return readings.getOrDefault(interlock, ConditionReading.UNKNOWN);
  }

  /** How strongly an interlock is drawn in this moment. */
  Emphasis emphasis(Interlock interlock) {
    return emphasis.getOrDefault(interlock, Emphasis.QUIET);
  }

  /** The interlocks that hold a joint short of what it was asked, in the order they are listed. */
  List<Interlock> holding(Joint joint) {
    return holding.getOrDefault(joint.label(), List.of());
  }

  private void decide(RobotPlan.OfJoint planned, Moment moment) {
    List<Interlock> interlocks = planned.interlocks();
    if (interlocks.isEmpty()) {
      return;
    }
    List<ConditionReading> conditions = new ArrayList<>();
    for (Interlock interlock : interlocks) {
      ConditionReading reading = read(interlock.condition(), moment);
      conditions.add(reading);
      readings.put(interlock, reading);
    }
    List<Interlock> held = heldBy(planned, moment, conditions);
    holding.put(planned.joint().label(), held);
    Moment.OfJoint joint = moment.joint(planned.joint().label());
    boolean aCommandAsks = joint != null && Prose.story(joint).asked() == Prose.Asked.BY_A_COMMAND;
    for (int index = 0; index < interlocks.size(); index++) {
      Interlock interlock = interlocks.get(index);
      boolean holds = held.contains(interlock);
      emphasis.put(interlock, emphasisOf(interlock, conditions.get(index), holds, aCommandAsks));
    }
  }

  /**
   * How strongly one interlock is drawn. A run-only-when restricts its joint whether or not
   * anything asks the joint to run, so it is waited for only while a command does ask. A keep-out
   * holds its joint only when what the joint is asked would take it into the region.
   */
  private static Emphasis emphasisOf(
      Interlock interlock, ConditionReading reading, boolean holds, boolean aCommandAsks) {
    return switch (interlock) {
      case Interlock.RunsOnlyWhen runsOnlyWhen -> {
        if (reading == ConditionReading.HOLDS) {
          yield Emphasis.QUIET;
        }
        yield holds && aCommandAsks ? Emphasis.WAITED_FOR : Emphasis.IN_FORCE;
      }
      case Interlock.KeepsOut keepsOut -> {
        if (reading == ConditionReading.DOES_NOT_HOLD) {
          yield Emphasis.QUIET;
        }
        yield holds ? Emphasis.WAITED_FOR : Emphasis.IN_FORCE;
      }
    };
  }

  /**
   * The interlocks that hold a joint in this moment, as the model decides it. None holds a joint
   * the robot does not drive in this loop: one the record does not have, any joint while the robot
   * is disabled, and a joint that holds positions while it does not know where it is.
   */
  private static List<Interlock> heldBy(
      RobotPlan.OfJoint planned, Moment moment, List<ConditionReading> conditions) {
    Moment.OfJoint joint = moment.joint(planned.joint().label());
    InterlockEnforcement enforcement = planned.enforcement();
    if (joint == null || enforcement == null || moment.enabled() == Moment.Enabled.DISABLED) {
      return List.of();
    }
    Prose.Story story = Prose.story(joint);
    boolean knowsWhere = story.standing() == Prose.Standing.READY;
    if (planned.joint().holdsPositions() && !knowsWhere) {
      return List.of();
    }
    if (!Double.isFinite(joint.position()) || !Double.isFinite(joint.velocity())) {
      return List.of();
    }
    InterlockReadings readings =
        new InterlockReadings(joint.position(), joint.velocity(), 0, conditions);
    InterlockVerdict verdict =
        enforcement.decide(
            enforcement.initialState(), asked(planned.joint(), joint, story), readings);
    return verdict.heldBy();
  }

  /**
   * What a joint was asked in a loop, put together from what the record says of it. A goal with a
   * name is the joint's own place or output of that name, as the model has it. A goal with no name
   * is the logged number: a position for a joint that holds positions, else a speed.
   */
  static JointAsk asked(Joint joint, Moment.OfJoint moment, Prose.Story story) {
    Position place = placeNamed(joint, story.goalName());
    if (place != null) {
      return new JointAsk.ToPosition(place.value(), JointAsk.FULL_CRUISE);
    }
    Output output = outputNamed(joint, story.goalName());
    if (output != null) {
      return asked(output);
    }
    if (!Double.isFinite(moment.goal())) {
      return JointAsk.NEUTRAL;
    }
    if (joint.holdsPositions()) {
      return new JointAsk.ToPosition(moment.goal(), JointAsk.FULL_CRUISE);
    }
    if (story.asked() == Prose.Asked.BY_NOTHING) {
      return JointAsk.NEUTRAL;
    }
    return new JointAsk.AtVelocity(moment.goal());
  }

  /** Running at a named output, as an ask. */
  static JointAsk asked(Output output) {
    if (!output.runs()) {
      return JointAsk.NEUTRAL;
    }
    return switch (output.kind()) {
      case VELOCITY -> new JointAsk.AtVelocity(output.value());
      case VOLTAGE -> new JointAsk.AtVoltage(output.value());
      case DUTY_CYCLE -> new JointAsk.AtDutyCycle(output.value());
    };
  }

  static @Nullable Position placeNamed(Joint joint, String name) {
    for (Position position : joint.positions()) {
      if (position.name().equals(name)) {
        return position;
      }
    }
    return null;
  }

  static @Nullable Output outputNamed(Joint joint, String name) {
    for (Output output : joint.outputs()) {
      if (output.name().equals(name)) {
        return output;
      }
    }
    return null;
  }

  /**
   * What one condition reads in a moment, as the runtime reads it. It is unknown when what it is
   * about cannot be read: a joint the record does not have, one that does not know where it is, one
   * whose reading is not this loop's, or a sensor that is lost.
   */
  static ConditionReading read(Condition condition, Moment moment) {
    switch (condition) {
      case Condition.OnJoint onJoint -> {
        Moment.OfJoint other = moment.joint(onJoint.joint().label());
        boolean isAPlace = !(onJoint instanceof Condition.At at && at.isOutput());
        if (other == null || !isAPlace || !knowsWhere(other)) {
          return ConditionReading.UNKNOWN;
        }
        return ConditionReading.of(onJoint.holdsAt(other.position()));
      }
      case Condition.UpToSpeed upToSpeed -> {
        Moment.OfJoint other = moment.joint(upToSpeed.joint().label());
        if (other == null || other.link() != Moment.Link.ANSWERS) {
          return ConditionReading.UNKNOWN;
        }
        return ConditionReading.of(upToSpeed.holdsAt(other.velocity()));
      }
      case Condition.OnSensor onSensor -> {
        Moment.OfSensor sensor = moment.sensor(Parts.labelOf(onSensor.sensor()));
        if (sensor == null || sensor.link() == Moment.Link.LOST) {
          return ConditionReading.UNKNOWN;
        }
        return ConditionReading.of(holds(onSensor, sensor));
      }
    }
  }

  private static boolean knowsWhere(Moment.OfJoint joint) {
    boolean ready = Prose.story(joint).standing() == Prose.Standing.READY;
    return ready && joint.link() == Moment.Link.ANSWERS;
  }

  private static boolean holds(Condition.OnSensor condition, Moment.OfSensor sensor) {
    boolean tripped = sensor.switched() == Moment.Switched.TRIPPED;
    return switch (condition) {
      case Condition.Tripped isTripped -> tripped;
      case Condition.Clear isClear -> !tripped;
      case Condition.Nearer nearer -> sensor.meters() < nearer.distance().in(Units.Meters);
      case Condition.Farther farther -> sensor.meters() > farther.distance().in(Units.Meters);
    };
  }
}
