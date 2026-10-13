package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.RobotMath;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything about a robot that the Robot view needs and that never changes while the robot runs,
 * worked out once from its definition: its joints in the model's order, each with its free speed,
 * the interlocks on it, and the interlocks elsewhere that depend on it.
 *
 * <p>It is a function of the definition alone, so a view made with it is still a function of the
 * definition and one moment.
 */
final class RobotPlan {
  /**
   * One joint, with what the view needs of it.
   *
   * @param joint the joint
   * @param freeSpeed how fast it can go with nothing loading it, in its units a second, or NaN for
   *     a joint no motors of its own drive
   * @param interlocks the interlocks that restrict it, in the order the robot applies them
   * @param dependents the interlocks on other joints whose conditions are about this joint
   */
  record OfJoint(
      Joint joint, double freeSpeed, List<Interlock> interlocks, List<Interlock> dependents) {}

  /**
   * One switch or rangefinder, with the interlocks whose conditions read it.
   *
   * @param sensor the sensor
   * @param dependents the interlocks whose conditions read it
   */
  record OfSensor(Sensor.Placed sensor, List<Interlock> dependents) {}

  private final RobotDefinition definition;
  private final List<OfJoint> joints = new ArrayList<>();
  private final List<OfSensor> sensors = new ArrayList<>();

  private RobotPlan(RobotDefinition definition) {
    this.definition = definition;
    RobotMath math = definition.math();
    List<Interlock> every = definition.everyInterlock();
    for (Joint joint : definition.joints()) {
      List<Interlock> own = every.stream().filter(each -> each.joint().equals(joint)).toList();
      List<Interlock> dependents =
          every.stream().filter(each -> isAbout(each.condition(), joint)).toList();
      joints.add(new OfJoint(joint, freeSpeedOf(math, joint), own, dependents));
    }
    for (Sensor.Placed placed : Parts.sensors(definition)) {
      List<Interlock> dependents =
          every.stream().filter(each -> reads(each.condition(), placed)).toList();
      sensors.add(new OfSensor(placed, dependents));
    }
  }

  static RobotPlan of(RobotDefinition definition) {
    return new RobotPlan(definition);
  }

  RobotDefinition definition() {
    return definition;
  }

  /** The joints, in the order the model lists them. */
  List<OfJoint> joints() {
    return joints;
  }

  /** The switches and rangefinders: each joint's own, then the robot's. */
  List<OfSensor> sensors() {
    return sensors;
  }

  /** A joint's free speed, for one that motors of its own drive under a control. */
  private static double freeSpeedOf(RobotMath math, Joint joint) {
    boolean driven = joint.driven().control().isPresent() && math.motorsAlone(joint);
    return driven ? math.freeSpeed(joint) : Double.NaN;
  }

  /** Whether a condition is about a joint: where it is, or how fast it runs. */
  private static boolean isAbout(Condition condition, Joint joint) {
    return switch (condition) {
      case Condition.OnJoint onJoint -> onJoint.joint().equals(joint);
      case Condition.UpToSpeed upToSpeed -> upToSpeed.joint().equals(joint);
      case Condition.OnSensor onSensor -> false;
    };
  }

  /** Whether a condition reads a sensor. */
  private static boolean reads(Condition condition, Sensor.Placed sensor) {
    return condition instanceof Condition.OnSensor onSensor && onSensor.sensor().equals(sensor);
  }
}
