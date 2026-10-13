package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.ConditionReading;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.Joint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.wpilib.units.Units;

/**
 * Cause, across the robot, in one moment: which interlocks hold their joint, what each interlock's
 * condition reads, and so how strongly each is to be drawn, on its own joint's card and on the card
 * of what it depends on.
 *
 * <p>What holds a joint is the robot's own fact: each joint logs the things that hold it ({@link
 * Holder}), and an interlock among them is one of the definition's, by its index. Nothing is
 * decided again here.
 *
 * <p>The log does not say what a condition reads while its interlock holds nothing: whether a
 * keep-out's region is closed, or a run-only-when is not met while its joint is not driven. That is
 * read here from the moment's positions and sensors, by the model's own conditions and as the
 * runtime reads them: a joint that does not know where it is, one whose reading is not this loop's,
 * and a sensor that is lost all read as unknown, which restricts.
 */
final class Causes {
  /** How strongly an interlock is drawn. */
  enum Emphasis {
    /** It restricts nothing now: the condition lets the joint run, or the region is open. */
    QUIET,
    /** It restricts its joint now, and its joint is not asked for anything it stops. */
    IN_FORCE,
    /** It holds its joint short of what was asked of it: it is what the joint is waiting for. */
    WAITED_FOR
  }

  private final Map<Interlock, ConditionReading> readings = new IdentityHashMap<>();
  private final Map<Interlock, Emphasis> emphasis = new IdentityHashMap<>();
  private final Map<String, List<Interlock>> holding = new HashMap<>();

  private Causes() {}

  /** Works out cause for one moment. */
  static Causes of(RobotPlan plan, Moment moment) {
    Causes causes = new Causes();
    for (RobotPlan.OfJoint joint : plan.joints()) {
      causes.read(plan, joint, moment);
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

  /** The interlocks the robot says hold a joint, in the order they are applied. */
  List<Interlock> holding(Joint joint) {
    return holding.getOrDefault(joint.label(), List.of());
  }

  private void read(RobotPlan plan, RobotPlan.OfJoint planned, Moment moment) {
    Moment.OfJoint joint = moment.joint(planned.joint().label());
    List<Interlock> held = joint == null ? List.of() : interlocksAmong(plan, joint.heldBy());
    holding.put(planned.joint().label(), held);
    boolean somethingAsks = joint != null && joint.owner() != JointOwner.IDLE;
    for (Interlock interlock : planned.interlocks()) {
      ConditionReading reading = read(interlock.condition(), moment);
      readings.put(interlock, reading);
      emphasis.put(
          interlock, emphasisOf(interlock, reading, held.contains(interlock), somethingAsks));
    }
  }

  /** The interlocks among the things that hold a joint, each the definition's by its index. */
  private static List<Interlock> interlocksAmong(RobotPlan plan, List<Holder> holders) {
    List<Interlock> every = plan.definition().everyInterlock();
    List<Interlock> interlocks = new ArrayList<>();
    for (Holder holder : holders) {
      boolean isOneOfThem =
          holder.kind() == Holder.Kind.INTERLOCK
              && holder.index() >= 0
              && holder.index() < every.size();
      if (isOneOfThem) {
        interlocks.add(every.get(holder.index()));
      }
    }
    return interlocks;
  }

  /**
   * How strongly one interlock is drawn. A run-only-when holds its joint whether or not anything
   * asks the joint to run, so it is waited for only while something does ask. A keep-out holds its
   * joint only when what the joint is asked would take it into the region.
   */
  private static Emphasis emphasisOf(
      Interlock interlock, ConditionReading reading, boolean holds, boolean somethingAsks) {
    return switch (interlock) {
      case Interlock.RunsOnlyWhen runsOnlyWhen -> {
        if (holds) {
          yield somethingAsks ? Emphasis.WAITED_FOR : Emphasis.IN_FORCE;
        }
        yield reading == ConditionReading.HOLDS ? Emphasis.QUIET : Emphasis.IN_FORCE;
      }
      case Interlock.KeepsOut keepsOut -> {
        if (holds) {
          yield Emphasis.WAITED_FOR;
        }
        yield reading == ConditionReading.DOES_NOT_HOLD ? Emphasis.QUIET : Emphasis.IN_FORCE;
      }
    };
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
    boolean ready = joint.state().standing() == JointState.Standing.READY;
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
