package com.michaelgrundvig.frc.pitwall.world;

import static org.wpilib.units.Units.Degrees;
import static org.wpilib.units.Units.Meters;

import com.michaelgrundvig.frc.pitwall.robot.SightingSentences;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.world.field.Alliance;
import com.michaelgrundvig.frc.world.robot.Body;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.ImuMount;
import com.michaelgrundvig.frc.world.robot.Sensor;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.units.measure.Distance;

/**
 * What the World view says in words of the robot's parts and of its numbers: a click on a camera,
 * an IMU or a sensor, and a distance or a χ² as the details show them.
 */
final class WorldWords {
  private WorldWords() {}

  /** A camera's frame on the page, by its id ({@link CameraConfig#id()}). */
  static String cameraFrame(String cameraId) {
    return "camera." + cameraId;
  }

  /**
   * A start's name on the page, with its alliance's: {@code Blue left} for blue's {@code Left}. A
   * name that starts with a capital word stays as written ({@code Blue AMP side}).
   */
  static String startName(Alliance alliance, String placeName) {
    boolean asWritten = placeName.length() > 1 && Character.isUpperCase(placeName.charAt(1));
    String rest =
        asWritten
            ? placeName
            : placeName.substring(0, 1).toLowerCase(Locale.ROOT) + placeName.substring(1);
    return capitalized(alliance.name()) + " " + rest;
  }

  /** A constant's name as a word: {@code BLUE} is {@code Blue}. */
  static String capitalized(String constant) {
    return constant.substring(0, 1) + constant.substring(1).toLowerCase(Locale.ROOT);
  }

  /** A tag's frame on the page. */
  static String tagFrame(int id) {
    return "tag." + id;
  }

  /**
   * What a click on a camera, or on its view, says: where it's mounted, how it's turned, what it
   * sees, and the action that calibrates its mount, if the page has one.
   */
  static Inspection camera(
      CameraConfig camera,
      Function<String, Optional<String>> cameraAction,
      SimulatedParts simulated) {
    CameraConfig.Sensor sensor = camera.sensor();
    Inspection said =
        Inspection.of("Camera " + camera.name())
            .detail("Mounted", mountedAt(camera.forward(), camera.left(), camera.up()))
            .detail(
                "Turned",
                String.format(
                    Locale.ROOT,
                    "yaw %.1f°, tilted up %.1f°, roll %.1f°",
                    camera.yaw().in(Degrees),
                    camera.tiltUp().in(Degrees),
                    camera.roll().in(Degrees)))
            .detail(
                "Sees",
                String.format(
                    Locale.ROOT,
                    "%.0f° across its diagonal, %d × %d, out to %.0f m",
                    sensor.diagonalFieldOfView().in(Degrees),
                    sensor.widthPixels(),
                    sensor.heightPixels(),
                    sensor.sightRange().in(Meters)))
            .detail("From", "the robot's configuration, in Robots.java")
            .component(simulated.camera(camera.name()));
    Optional<String> calibrate = cameraAction.apply(camera.name());
    if (calibrate.isEmpty()) {
      return said;
    }
    return said.action(calibrate.get());
  }

  /**
   * What a click on an IMU says: where it's mounted, how it's turned, and whether its accelerometer
   * has been checked against the robot's axes.
   */
  static Inspection imu(String title, ImuMount imu, Optional<String> simulated) {
    String accelerometer = "not yet checked (docs/calibration.md)";
    if (imu.accelerometerChecked()) {
      accelerometer = "checked: reads along the robot's axes";
    }
    return Inspection.of(title)
        .detail("Mounted", mountedAt(imu.forward(), imu.left(), imu.up()))
        .detail(
            "Turned",
            String.format(
                Locale.ROOT,
                "roll %.1f°, pitch %.1f°, yaw %.1f° from level with its x forward",
                Math.toDegrees(imu.orientation().getX()),
                Math.toDegrees(imu.orientation().getY()),
                Math.toDegrees(imu.orientation().getZ())))
        .detail("Accelerometer", accelerometer)
        .detail("From", "the robot's configuration, in Robots.java")
        .component(simulated);
  }

  /**
   * What a click on the robot's bumpers says: how big it is. In the simulator the panel ends in
   * what can happen to the robot as a whole, and in the faults of the backup IMU, which has no
   * shape of its own to click.
   */
  static Inspection robot(Body body, SimulatedParts simulated) {
    return Inspection.of("Robot")
        .detail(
            "Frame",
            String.format(
                Locale.ROOT,
                "%.3f m long, %.3f m wide, %.3f m tall",
                body.frameLength().in(Meters),
                body.frameWidth().in(Meters),
                body.height().in(Meters)))
        .detail(
            "With bumpers",
            String.format(
                Locale.ROOT,
                "%.3f m long, %.3f m wide",
                body.bumperLength().in(Meters),
                body.bumperWidth().in(Meters)))
        .detail("From", "the robot's configuration, in Robots.java")
        .component(simulated.robot())
        .component(simulated.backupImu());
  }

  /**
   * What a click on a wheel says: which module it is and where it stands on the robot.
   *
   * @param name the module's name on the page, such as {@code front-left}
   * @param index its place in the drivetrain's order
   */
  static Inspection module(
      String name, int index, Translation2d at, double wheelRadius, SimulatedParts simulated) {
    return Inspection.of("Module " + name)
        .detail(
            "At",
            String.format(
                Locale.ROOT, "%.3f m forward, %.3f m left of the center", at.getX(), at.getY()))
        .detail("Wheel", String.format(Locale.ROOT, "%.1f mm radius", wheelRadius * 1000))
        .detail("From", "the robot's configuration, in Robots.java")
        .component(simulated.module(index));
  }

  /** Where something is mounted on the robot, from the robot's center on the floor. */
  private static String mountedAt(Distance forward, Distance left, Distance up) {
    return String.format(
        Locale.ROOT,
        "%.3f m forward, %.3f m left, %.3f m up",
        forward.in(Meters),
        left.in(Meters),
        up.in(Meters));
  }

  /** A switch's or a rangefinder's details: what it is, and where it's mounted. */
  static Inspection sensor(Sensor.Placed sensor) {
    return Inspection.of(sensor.label())
        .detail("Is", whatItIs(sensor))
        .detail("Mounted", whereItIsMounted(sensor))
        .detail("From", "the robot's definition, in Robots.java");
  }

  private static String whatItIs(Sensor.Placed sensor) {
    return switch (sensor) {
      case Sensor.Switch onSwitch -> whatKindOfSwitch(onSwitch.kind());
      case Sensor.Rangefinder rangefinder ->
          String.format(
              Locale.ROOT,
              "a rangefinder: to %.2f m, in a %.1f° cone",
              rangefinder.range().in(Meters),
              rangefinder.fieldOfView().in(Degrees));
    };
  }

  private static String whatKindOfSwitch(Sensor.Switch.Kind kind) {
    return switch (kind) {
      case Sensor.Switch.Limit limit -> limitSwitch(limit);
      case Sensor.Switch.Beam beam -> "a beam break";
      case Sensor.Switch.Contact contact -> "a switch";
    };
  }

  /** A limit switch: which joint's, at which end, and where along the joint it trips, if known. */
  private static String limitSwitch(Sensor.Switch.Limit limit) {
    String end = "upper";
    if (limit.end() == Sensor.Switch.Limit.End.LOWER) {
      end = "lower";
    }
    String tripsAt = ", where unknown";
    if (limit.position().isPresent()) {
      tripsAt = String.format(Locale.ROOT, ", at %.3f", limit.position().get());
    }
    return String.format(
        Locale.ROOT, "a limit switch at %s's %s end%s", limit.joint().label(), end, tripsAt);
  }

  /** Which part a sensor rides on, and where on it, if the robot's definition says. */
  private static String whereItIsMounted(Sensor.Placed sensor) {
    String part = sensor.part().label();
    if (sensor.pose().isEmpty()) {
      return "on " + part + ", where unknown: drawn at its origin";
    }
    Pose3d pose = sensor.pose().get();
    return String.format(
        Locale.ROOT,
        "on %s, %.3f m forward, %.3f m left, %.3f m up of its origin",
        part,
        pose.getX(),
        pose.getY(),
        pose.getZ());
  }

  /** A distance to two places of meters, for details, or that it wasn't measured. */
  static String meters(double meters) {
    return SightingSentences.meters(meters);
  }

  /**
   * A distance that's often small, such as a camera's innovation. Under 10 cm it is given to the
   * millimeter, because two places of meters would show a few millimeters as 0.00 m.
   */
  static String offset(double meters) {
    boolean small = Double.isFinite(meters) && Math.abs(meters) < 0.1;
    if (!small) {
      return meters(meters);
    }
    return String.format(Locale.ROOT, "%.0f mm", meters * 1000);
  }

  /**
   * A χ² to three figures, or to the whole number from 100 up, so that a small one isn't shown as
   * 0.0.
   */
  static String chiSquared(double value) {
    double size = Math.abs(value);
    String format = "%.3f";
    if (size >= 100) {
      format = "%.0f";
    } else if (size >= 10) {
      format = "%.1f";
    } else if (size >= 1) {
      format = "%.2f";
    }
    return String.format(Locale.ROOT, format, value);
  }
}
