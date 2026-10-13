package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.InterlockEnforcement;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.MechanismControl;
import com.michaelgrundvig.frc.world.robot.PlaceNames;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.RobotMath;
import com.michaelgrundvig.frc.world.robot.Sensor;
import com.michaelgrundvig.frc.world.robot.Travel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Everything about a robot that the Robot view needs and that never changes while the robot runs,
 * worked out once from its definition: its joints in the model's order, each with its travel, the
 * interlocks on it, the model's decision for them, and the interlocks elsewhere that depend on it.
 *
 * <p>It is a function of the definition alone, so a view made with it is still a function of the
 * definition and one moment.
 */
final class RobotPlan {
  /**
   * How near a named place a joint that turns must be to be said to be at it, when its control
   * gives no band: the template's 2°, as {@link MechanismControl}'s bands say.
   */
  private static final double DEFAULT_ANGLE_BAND = Math.toRadians(2);

  /** The same for a joint that slides: the template's 1 cm. */
  private static final double DEFAULT_LENGTH_BAND = 0.01;

  /**
   * One joint, with what the view needs of it.
   *
   * @param joint the joint
   * @param travel where it is driven and how fast it can go, or null for a joint no motors of its
   *     own drive
   * @param interlocks the interlocks that restrict it, in the order the robot applies them
   * @param enforcement the model's decision for those interlocks, or null for a joint with none
   * @param dependents the interlocks on other joints whose conditions are about this joint
   * @param places its named places, as words for where it is
   */
  record OfJoint(
      Joint joint,
      @Nullable Travel travel,
      List<Interlock> interlocks,
      @Nullable InterlockEnforcement enforcement,
      List<Interlock> dependents,
      PlaceNames places) {}

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

  private RobotPlan(RobotDefinition definition, double loopSeconds) {
    this.definition = definition;
    RobotMath math = definition.math();
    List<Interlock> every = definition.everyInterlock();
    for (Joint joint : definition.joints()) {
      List<Interlock> own = every.stream().filter(each -> each.joint().equals(joint)).toList();
      List<Interlock> dependents =
          every.stream().filter(each -> isAbout(each.condition(), joint)).toList();
      Travel travel = travelOf(math, joint);
      joints.add(
          new OfJoint(
              joint,
              travel,
              own,
              enforcementOf(travel, own, loopSeconds),
              dependents,
              new PlaceNames(joint, arrivalBand(joint))));
    }
    for (Sensor.Placed placed : Parts.sensors(definition)) {
      List<Interlock> dependents =
          every.stream().filter(each -> reads(each.condition(), placed)).toList();
      sensors.add(new OfSensor(placed, dependents));
    }
  }

  /**
   * @param loopSeconds how long one of the robot's loops lasts, in seconds, which the model's
   *     keep-outs need
   */
  static RobotPlan of(RobotDefinition definition, double loopSeconds) {
    return new RobotPlan(definition, loopSeconds);
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

  /** The model's decision for a joint's interlocks, for a driven joint that has any. */
  private static @Nullable InterlockEnforcement enforcementOf(
      @Nullable Travel travel, List<Interlock> interlocks, double loopSeconds) {
    if (travel == null || interlocks.isEmpty()) {
      return null;
    }
    return new InterlockEnforcement(travel, interlocks, loopSeconds);
  }

  /** A joint's travel, for one that motors of its own drive under a control. */
  private static @Nullable Travel travelOf(RobotMath math, Joint joint) {
    Optional<MechanismControl> control = joint.driven().control();
    if (control.isEmpty() || !math.motorsAlone(joint)) {
      return null;
    }
    return Travel.of(math, joint, control.get());
  }

  /**
   * How near a place a joint must be to be said to be at it: its control's band, or the default.
   */
  private static double arrivalBand(Joint joint) {
    Optional<MechanismControl.Bands> bands =
        joint.driven().control().flatMap(MechanismControl::bands);
    if (bands.isPresent() && joint.holdsPositions()) {
      return bands.get().enter();
    }
    return joint instanceof Joint.Revolute ? DEFAULT_ANGLE_BAND : DEFAULT_LENGTH_BAND;
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
