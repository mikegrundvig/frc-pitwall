package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.Layer;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.Shape;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.field.Crossing;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.field.Surface;
import com.michaelgrundvig.frc.world.field.Tag;
import com.michaelgrundvig.frc.world.physical.TriangleMesh;
import com.michaelgrundvig.frc.world.physical.Vector3;
import com.michaelgrundvig.frc.world.robot.Body;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.wpilib.math.geometry.Pose3d;

/**
 * The field as the field model has it: its carpet and walls, its raised ground (bumps, ramps,
 * platforms), and its AprilTags where the layout puts them, each drawn so the side it is read from
 * shows. Their numbers are a layer of their own ({@link #tagNumbers}), and so are what stands on
 * the field ({@link #elements}) and where a robot may start ({@link #starts}). Nothing here moves.
 */
final class FieldLayer implements Layer {
  /** The side of an AprilTag's printed square, in meters. FRC's 36h11 tags are 6.5 in. */
  private static final double TAG_SIZE = 0.1651;

  /**
   * How thick each side of a tag is drawn, its face and its back: thick enough that a tag seen from
   * above, across the whole field, is still a short line and not a hair.
   */
  private static final double TAG_SIDE_THICKNESS = 0.03;

  /** How far the tick stands out of a tag's face, from the face's middle. */
  private static final double TAG_TICK_OUT = 0.14;

  /** How far in front of its face a tag's number is drawn. */
  private static final double TAG_NUMBER_OUT = 0.25;

  private final Supplier<FieldModel> active;
  private final Body body;
  private final SimulatedParts simulated;

  /**
   * @param active the field the robot believes it plays on, which is the selected venue's once
   *     there is one
   * @param body the robot's, whose bumpers stand it on each start and outline it there
   * @param simulated which of the simulator's parts the robot is, for setting it down on a start
   */
  FieldLayer(Supplier<FieldModel> active, Body body, SimulatedParts simulated) {
    this.active = active;
    this.body = body;
    this.simulated = simulated;
  }

  /**
   * The field in use. When it is a new one, because a venue was selected, Pitwall has the layer
   * describe itself again.
   */
  @Override
  public Object configuration() {
    return active.get();
  }

  /** Nothing in it moves, so it is the same in every loop of the robot's record. */
  @Override
  public Optional<Recorded> recorded() {
    return Optional.of(Recorded.STILL);
  }

  @Override
  public String id() {
    return "field";
  }

  @Override
  public String title() {
    return "Field";
  }

  @Override
  public void describe(SceneBuilder scene) {
    FieldModel field = active.get();
    describeCarpetAndWalls(scene, field.length(), field.width());
    List<Surface> surfaces = field.terrain().surfaces();
    for (int i = 0; i < surfaces.size(); i++) {
      surface(scene, "surface." + i, surfaces.get(i));
    }
    for (Tag tag : field.tags().values()) {
      describeTag(scene, tag.id(), tag.pose());
    }
  }

  /** The carpet as a filled rectangle from the field's origin, and the walls as its outline. */
  private static void describeCarpetAndWalls(SceneBuilder scene, double length, double width) {
    scene
        .polygon("carpet", SceneBuilder.ROOT)
        .point(0, 0, 0)
        .point(length, 0, 0)
        .point(length, width, 0)
        .point(0, width, 0);
    scene
        .polyline("walls", SceneBuilder.ROOT)
        .point(0, 0, 0)
        .point(length, 0, 0)
        .point(length, width, 0)
        .point(0, width, 0)
        .closed();
  }

  /**
   * A tag in a frame of its own at its pose, drawn so the side it is read from shows. Its face is a
   * lit slab on the side its pose looks out of, with a tick standing out of its middle, and its
   * back is a faint slab behind. From above that is a short thick line with a tick on its readable
   * side; from around, a bright front and a dim back.
   */
  private static void describeTag(SceneBuilder scene, int id, Pose3d pose) {
    String frame = WorldWords.tagFrame(id);
    scene.frame(frame, SceneBuilder.ROOT, pose);
    // The tag's x points out of its face, so its square stands in the y-z plane: the face's slab is
    // just in front of that plane and the back's just behind it.
    scene
        .box(frame + ".square", frame)
        .size(TAG_SIDE_THICKNESS, TAG_SIZE, TAG_SIZE)
        .at(TAG_SIDE_THICKNESS / 2, 0, 0)
        .style(Style.FACE)
        .inspect(() -> tag(id, pose));
    scene
        .box(frame + ".back", frame)
        .size(TAG_SIDE_THICKNESS, TAG_SIZE, TAG_SIZE)
        .at(-TAG_SIDE_THICKNESS / 2, 0, 0);
    scene
        .line(frame + ".facing", frame)
        .from(TAG_SIDE_THICKNESS, 0, 0)
        .to(TAG_TICK_OUT, 0, 0)
        .style(Style.FACE);
  }

  /**
   * What stands on the field, a layer of its own so the page can hide it: each element's blocks as
   * solids ({@link ElementShapes}).
   */
  StillLayer elements() {
    return new StillLayer(
        "elements",
        "Field elements",
        scene -> ElementShapes.describe(scene, active.get().elements()),
        List.of(
            Legend.fill(
                Style.STRUCTURE,
                "Something that stands on the field, which a robot runs into: a hub, a tower,"
                    + " a wall's face. Click one for its name and size")),
        active::get);
  }

  /**
   * Where a robot may start, for both alliances, a layer of its own so the page can hide it: the
   * robot's bumpers as an outline on the floor at each start, with its name ({@link StartShapes}).
   * A field whose game has no starts has none to draw.
   */
  StillLayer starts() {
    return new StillLayer(
        "starts",
        "Starting positions",
        scene -> StartShapes.describe(scene, body, active.get(), simulated),
        List.of(
            Legend.outline(
                Style.MUTED.dashed(),
                "Where the robot may start, its bumpers' size, with an arrow out of its front."
                    + " Click one for its pose")),
        active::get);
  }

  /**
   * The tags' numbers, a layer of their own so the page can hide them: each in its tag's frame, in
   * front of the tag's face, so that the numbers of tags back to back, or side by side on one
   * panel, keep apart.
   */
  StillLayer tagNumbers() {
    return new StillLayer(
        "tag-numbers",
        "Tag numbers",
        scene -> {
          for (Tag tag : active.get().tags().values()) {
            String frame = WorldWords.tagFrame(tag.id());
            scene
                .label(frame + ".id", frame)
                .text(Integer.toString(tag.id()))
                .at(TAG_NUMBER_OUT, 0, TAG_SIZE);
          }
        },
        List.of(Legend.text(Style.MUTED, "An AprilTag's number")),
        active::get);
  }

  @Override
  public List<Legend> legend() {
    return List.of(
        Legend.outline(Style.MUTED, "The field's walls, around its carpet"),
        Legend.fill(
            Style.MUTED,
            "Raised ground the robot drives over: a bump, a ramp or a platform."
                + " Click one for its numbers"),
        Legend.fill(
            Style.FACE,
            "An AprilTag's face, where the layout has it. The tick stands out of the side it is"
                + " read from, and its back is faint. Click one for its numbers"));
  }

  /** Raised ground as its triangles: a bump's ridge, a ramp, or a platform and its ramps. */
  private static void surface(SceneBuilder scene, String id, Surface surface) {
    TriangleMesh mesh = surface.mesh();
    Shape.Mesh shape = scene.mesh(id, SceneBuilder.ROOT);
    for (Vector3 corner : mesh.corners()) {
      shape.vertex(corner.x(), corner.y(), corner.z());
    }
    for (TriangleMesh.Triangle triangle : mesh.triangles()) {
      shape.triangle(triangle.a(), triangle.b(), triangle.c());
    }
    shape.inspect(() -> inspection(surface));
  }

  /**
   * What a click on raised ground says: how high it stands, how steep its slopes are, and how long
   * each line is that it can be crossed at.
   */
  private static Inspection inspection(Surface surface) {
    double height = surface.mesh().corners().stream().mapToDouble(Vector3::z).max().orElse(0);
    Inspection inspection =
        Inspection.of(surface.name()).detail("Height", WorldWords.meters(height));
    boolean sloped = surface.steepestSlopeRadians() > 0;
    if (sloped) {
      inspection =
          inspection.detail(
              "Ramps",
              String.format(Locale.ROOT, "%.1f°", Math.toDegrees(surface.steepestSlopeRadians())));
    }
    for (Crossing crossing : surface.crossings()) {
      String kind = "Top";
      if (crossing.kind() == Crossing.Kind.CREST) {
        kind = "Crest";
      }
      inspection = inspection.detail(kind, WorldWords.meters(crossing.length()) + " long");
    }
    return inspection;
  }

  /** What a click on a tag says: where the layout has it, and which way it faces. */
  private static Inspection tag(int id, Pose3d pose) {
    return Inspection.of("AprilTag " + id)
        .detail(
            "Where",
            String.format(
                Locale.ROOT,
                "x %.3f m, y %.3f m, %.3f m up",
                pose.getX(),
                pose.getY(),
                pose.getZ()))
        .detail(
            "Facing",
            String.format(Locale.ROOT, "%.1f°", Math.toDegrees(pose.getRotation().getZ())))
        .detail("From", "the field's layout, as the robot program has it");
  }
}
