package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What changed in one loop, compared with the loop before it, among the rows the Robot view shows:
 * what the page marks while its cursor stands on a loop, so that going loop by loop shows a cause
 * and then its effect. It is a function of the robot's definition and two moments ({@link
 * #between}).
 *
 * <p>A row is marked when what it says changed, and not when a number only moved: a joint on its
 * way from one place to another changes where it is every loop, and its row is marked in the loop
 * it leaves a place or reaches one.
 *
 * @param robot whether the robot was enabled or disabled, or its mode changed
 * @param joints the rows that changed on each joint's card, by the joint's label; a joint with none
 *     is left out
 * @param sensors the sensors whose reading changed, by their labels
 * @param gates the gates that began or stopped holding, or whose blocking terms changed, by name
 */
public record Changed(
    boolean robot, Map<String, Set<Row>> joints, List<String> sensors, List<String> gates) {
  public Changed {
    joints = Collections.unmodifiableMap(new LinkedHashMap<>(joints));
    sensors = List.copyOf(sensors);
    gates = List.copyOf(gates);
  }

  /** A row of a joint's card. */
  public enum Row {
    /** Where it is among its places, or the output it runs at. */
    AT,
    /** What it was told. */
    TOLD,
    /** Who owns it. */
    OWNED_BY,
    /** How the last move on it ended. */
    LAST_MOVE,
    /** What holds or restricts it, or whether it is held or only restricted. */
    HELD_BY,
    /** Where it stands: homing, ready, faulted. */
    STATE,
    /** Whether its motor controller answers. */
    MOTOR_CONTROLLER,
    /** How its absolute encoder is doing. */
    ABSOLUTE_ENCODER,
    /** What one of its limit switches reads. */
    LIMIT_SWITCHES
  }

  /** Whether nothing the view shows changed. */
  public boolean isNothing() {
    return !robot && joints.isEmpty() && sensors.isEmpty() && gates.isEmpty();
  }

  /** The rows that changed on a joint's card; none for a joint nothing changed on. */
  public Set<Row> joint(String label) {
    return joints.getOrDefault(label, Set.of());
  }

  /** What changed from one moment to the next. */
  static Changed between(RobotPlan plan, Moment before, Moment now) {
    boolean robot = before.enabled() != now.enabled() || before.mode() != now.mode();
    Map<String, Set<Row>> joints = new LinkedHashMap<>();
    for (RobotPlan.OfJoint planned : plan.joints()) {
      String label = planned.joint().label();
      Moment.OfJoint was = before.joint(label);
      Moment.OfJoint is = now.joint(label);
      if (was == null || is == null) {
        continue;
      }
      Set<Row> rows = rowsChanged(plan, planned, before, was, now, is);
      if (!rows.isEmpty()) {
        joints.put(label, rows);
      }
    }
    List<String> sensors = new ArrayList<>();
    for (Moment.OfSensor is : now.sensors()) {
      if (readsDifferently(before.sensor(is.label()), is)) {
        sensors.add(is.label());
      }
    }
    List<String> gates = new ArrayList<>();
    for (Moment.OfGate is : now.gates()) {
      Moment.OfGate was = gateNamed(before, is.name());
      if (was != null && !was.equals(is)) {
        gates.add(is.name());
      }
    }
    return new Changed(robot, joints, sensors, gates);
  }

  private static Set<Row> rowsChanged(
      RobotPlan plan,
      RobotPlan.OfJoint planned,
      Moment before,
      Moment.OfJoint was,
      Moment now,
      Moment.OfJoint is) {
    Joint joint = planned.joint();
    RobotView.Drawn drawn = JointCards.drawn(joint);
    Set<Row> rows = EnumSet.noneOf(Row.class);
    if (!was.at().equals(is.at())) {
      rows.add(Row.AT);
    }
    if (!JointCards.told(planned, drawn, was).equals(JointCards.told(planned, drawn, is))) {
      rows.add(Row.TOLD);
    }
    String ownedBefore = JointCards.ownedBy(joint, before, was);
    if (!ownedBefore.equals(JointCards.ownedBy(joint, now, is))) {
      rows.add(Row.OWNED_BY);
    }
    if (!was.lastMove().equals(is.lastMove())) {
      rows.add(Row.LAST_MOVE);
    }
    boolean heldDifferently =
        !was.heldBy().equals(is.heldBy()) || JointCards.hold(was) != JointCards.hold(is);
    if (heldDifferently) {
      rows.add(Row.HELD_BY);
    }
    if (!was.state().equals(is.state()) || !was.fault().equals(is.fault())) {
      rows.add(Row.STATE);
    }
    if (was.link() != is.link()) {
      rows.add(Row.MOTOR_CONTROLLER);
    }
    if (was.encoder() != is.encoder()) {
      rows.add(Row.ABSOLUTE_ENCODER);
    }
    if (aLimitSwitchChanged(plan, joint, before, now)) {
      rows.add(Row.LIMIT_SWITCHES);
    }
    return rows;
  }

  /** Whether one of a joint's limit switches reads differently. */
  private static boolean aLimitSwitchChanged(
      RobotPlan plan, Joint joint, Moment before, Moment now) {
    for (Sensor.Switch.Limit.End end : Sensor.Switch.Limit.End.values()) {
      Sensor.Switch limit = plan.definition().math().limitSwitch(joint, end);
      boolean changed =
          limit != null
              && readsDifferently(before.sensor(limit.label()), now.sensor(limit.label()));
      if (changed) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a sensor reads differently in words: lost or found, tripped or cleared. A rangefinder's
   * distance moving is a number moving, and no change.
   */
  private static boolean readsDifferently(
      Moment.@Nullable OfSensor was, Moment.@Nullable OfSensor is) {
    if (was == null || is == null) {
      return !Objects.equals(was, is);
    }
    return was.link() != is.link() || was.switched() != is.switched();
  }

  private static Moment.@Nullable OfGate gateNamed(Moment moment, String name) {
    for (Moment.OfGate gate : moment.gates()) {
      if (gate.name().equals(name)) {
        return gate;
      }
    }
    return null;
  }
}
