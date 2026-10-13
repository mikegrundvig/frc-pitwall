package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotView.Health;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.SensorKind;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.SensorMark;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.SensorRow;
import com.michaelgrundvig.frc.world.config.Rules;
import com.michaelgrundvig.frc.world.robot.Condition;
import com.michaelgrundvig.frc.world.robot.Interlock;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.wpilib.units.Units;

/**
 * Makes the rows of the robot's switches and rangefinders for one moment. A joint's limit switch is
 * on its joint's card, and has a row here too only when an interlock's condition reads it.
 */
final class SensorRows {
  private SensorRows() {}

  static List<SensorRow> rows(RobotPlan plan, Moment moment, Causes causes) {
    List<SensorRow> rows = new ArrayList<>();
    for (RobotPlan.OfSensor planned : plan.sensors()) {
      SensorKind kind = kindOf(planned.sensor());
      if (kind == SensorKind.LIMIT_SWITCH && planned.dependents().isEmpty()) {
        continue;
      }
      rows.add(row(planned, kind, moment.sensor(Parts.labelOf(planned.sensor())), causes));
    }
    return rows;
  }

  private static SensorRow row(
      RobotPlan.OfSensor planned, SensorKind kind, Moment.@Nullable OfSensor now, Causes causes) {
    Sensor.Placed sensor = planned.sensor();
    List<SensorMark> marks = new ArrayList<>();
    for (Interlock interlock : planned.dependents()) {
      marks.add(
          new SensorMark(
              thresholdOf(interlock.condition()),
              interlock.joint().label(),
              JointCards.label(interlock),
              interlock.toString(),
              causes.emphasis(interlock),
              causes.reading(interlock)));
    }
    boolean answers = now != null && now.link() != Moment.Link.LOST;
    boolean ranges = kind == SensorKind.RANGEFINDER;
    return new SensorRow(
        Parts.labelOf(sensor),
        kind,
        sensor.part().label(),
        answers ? Health.FINE : Health.NEEDS_ATTENTION,
        reading(kind, now),
        ranges && answers && now != null ? now.meters() : Double.NaN,
        sensor instanceof Sensor.Rangefinder finder ? finder.range().in(Units.Meters) : Double.NaN,
        marks);
  }

  private static SensorKind kindOf(Sensor.Placed sensor) {
    return switch (sensor) {
      case Sensor.Rangefinder rangefinder -> SensorKind.RANGEFINDER;
      case Sensor.Switch onSwitch ->
          switch (onSwitch.kind()) {
            case Sensor.Switch.Limit limit -> SensorKind.LIMIT_SWITCH;
            case Sensor.Switch.Beam beam -> SensorKind.BEAM;
            case Sensor.Switch.Contact contact -> SensorKind.CONTACT;
          };
    };
  }

  /** What a sensor reads, in words. A rangefinder with nothing in range reads no number. */
  private static String reading(SensorKind kind, Moment.@Nullable OfSensor now) {
    if (now == null) {
      return "not in the record";
    }
    if (now.link() == Moment.Link.LOST) {
      return "lost";
    }
    if (kind != SensorKind.RANGEFINDER) {
      return now.switched() == Moment.Switched.TRIPPED ? "tripped" : "clear";
    }
    if (!Double.isFinite(now.meters())) {
      return "nothing in range";
    }
    return Rules.shown(Math.rint(now.meters() * 1000) / 1000) + " m";
  }

  /** The distance a condition on a rangefinder compares with, in meters; NaN for a switch's. */
  private static double thresholdOf(Condition condition) {
    return switch (condition) {
      case Condition.Nearer nearer -> nearer.distance().in(Units.Meters);
      case Condition.Farther farther -> farther.distance().in(Units.Meters);
      default -> Double.NaN;
    };
  }
}
