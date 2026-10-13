package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a robot that the session keeps a place for in every loop, named as the runtime's log
 * names them: its joints, and its switches and rangefinders.
 */
final class Parts {
  private Parts() {}

  /** The labels of the robot's joints, in the order the model lists them. */
  static List<String> jointLabels(RobotDefinition definition) {
    return definition.joints().stream().map(Joint::label).toList();
  }

  /** The robot's switches and rangefinders: each joint's own, then the robot's. */
  static List<Sensor.Placed> sensors(RobotDefinition definition) {
    List<Sensor.Placed> placed = new ArrayList<>();
    for (Sensor sensor : definition.math().sensors()) {
      if (sensor instanceof Sensor.Placed each) {
        placed.add(each);
      }
    }
    return placed;
  }

  /** The labels of the robot's switches and rangefinders, in the same order. */
  static List<String> sensorLabels(RobotDefinition definition) {
    return sensors(definition).stream().map(Parts::labelOf).toList();
  }

  /** A sensor's label. */
  static String labelOf(Sensor sensor) {
    return switch (sensor) {
      case Sensor.Encoder encoder -> encoder.label();
      case Sensor.Switch onSwitch -> onSwitch.label();
      case Sensor.Rangefinder rangefinder -> rangefinder.label();
    };
  }
}
