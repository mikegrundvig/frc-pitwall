package com.michaelgrundvig.frc.pitwall.world;

import static org.wpilib.units.Units.Meters;

import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.field.Alliance;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.field.Place;
import com.michaelgrundvig.frc.world.robot.Body;
import java.util.List;
import java.util.Locale;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Pose3d;

/**
 * A field's starting positions as shapes, for both alliances: each is the robot's bumpers as a
 * faint dashed outline on the floor at the start's pose, with an arrow for the way the robot faces
 * and the start's name beside it. A click on one says its name, alliance and pose, and in the
 * simulator offers to set the robot down there.
 */
final class StartShapes {
  /** How far short of the front bumper the arrow ends, so that its head stays in the outline. */
  private static final double ARROW_SHORT_OF_FRONT = 0.05;

  /**
   * How far a name stands from the outline's side, to the name's middle. A name is one line of
   * text, about a quarter of a meter tall at the zoom of a whole field, so this leaves a gap beside
   * the outline. The robot standing on the start then never lies over its own name.
   */
  private static final double NAME_BESIDE = 0.5;

  private StartShapes() {}

  /**
   * Adds every start of both alliances, each in a frame of its own at its pose: {@code
   * start.<alliance>.<index>}, with its {@code .outline}, {@code .facing} and {@code .name}.
   */
  static void describe(SceneBuilder scene, Body body, FieldModel field, SimulatedParts simulated) {
    double halfLength = body.bumperLength().in(Meters) / 2;
    double halfWidth = body.bumperWidth().in(Meters) / 2;
    List<Place> starts = field.starts();
    for (Alliance alliance : Alliance.values()) {
      for (int index = 0; index < starts.size(); index++) {
        Place place = starts.get(index);
        Pose2d pose = place.on(field, alliance);
        String frame = "start." + alliance.name().toLowerCase(Locale.ROOT) + "." + index;
        String name = WorldWords.startName(alliance, place.name());
        scene.frame(frame, SceneBuilder.ROOT, new Pose3d(pose));
        scene
            .polygon(frame + ".outline", frame)
            .point(halfLength, halfWidth, 0)
            .point(-halfLength, halfWidth, 0)
            .point(-halfLength, -halfWidth, 0)
            .point(halfLength, -halfWidth, 0)
            .style(Style.MUTED.dashed())
            .inspect(() -> inspection(alliance, place.name(), name, pose, simulated));
        // The arrow isn't clickable: a click on a start selects its outline, which then shows it.
        scene
            .arrow(frame + ".facing", frame)
            .from(0, 0, 0)
            .to(halfLength - ARROW_SHORT_OF_FRONT, 0, 0)
            .style(Style.MUTED.dashed());
        double beside = halfWidth + NAME_BESIDE;
        scene
            .label(frame + ".name", frame)
            .text(name)
            .at(0, towardTheMiddle(field, pose) * beside, 0);
      }
    }
  }

  /**
   * Which side of a start, in its own frame, faces the middle of the field: +1 for its left, -1 for
   * its right. The name goes there, so the names of the starts at the field's edges stay on the
   * field. A start on the middle line puts it to its right.
   */
  private static int towardTheMiddle(FieldModel field, Pose2d pose) {
    double acrossToMiddle = field.width() / 2 - pose.getY();
    double onItsLeft = pose.getRotation().getCos() * acrossToMiddle;
    return onItsLeft > 0 ? 1 : -1;
  }

  /**
   * What a click on a start says: its name, whose it is, and where it puts the robot and which way
   * it faces. In the simulator it ends in setting the robot down there.
   */
  private static Inspection inspection(
      Alliance alliance, String placeName, String name, Pose2d pose, SimulatedParts simulated) {
    return Inspection.of(name + " start")
        .detail("Name", placeName)
        .detail("Alliance", WorldWords.capitalized(alliance.name()))
        .detail("Where", String.format(Locale.ROOT, "x %.3f m, y %.3f m", pose.getX(), pose.getY()))
        .detail(
            "Heading", String.format(Locale.ROOT, "%.1f°", pose.getRotation().getDegrees() + 0.0))
        .detail("From", "the field model: where a robot of this size starts")
        .offer(simulated.placingTheRobotAt(pose));
  }
}
