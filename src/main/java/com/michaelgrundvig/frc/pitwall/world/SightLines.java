package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.robot.Sighting;
import com.michaelgrundvig.frc.pitwall.robot.SightingSentences;
import com.michaelgrundvig.frc.pitwall.robot.SightingWords;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.SceneState;
import com.michaelgrundvig.frc.pitwall.scene.Shapes;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.wpilib.math.geometry.Pose3d;

/**
 * Each camera's sight lines to the tags its measurement used. A line is solid where localization
 * accepted the tag's measurement, and dashed where it refused it. A click on one says why, in the
 * robot program's own words for the verdict, and offers the camera's calibration.
 *
 * <p>The lines of a loop are the tags each camera measured in it, and those it measured a moment
 * before and has not measured since ({@link Moment.OfCamera}), so a line doesn't flicker between
 * images.
 */
final class SightLines extends WorldLayer {
  /**
   * One camera's sight line to one tag in one loop.
   *
   * @param id the line's id on the page, which says its camera, its tag and how it's drawn
   * @param camera the camera
   * @param sighted the tag as the camera saw it, with what became of it
   * @param imageTags every tag its image saw, in the image's order
   */
  private record Line(
      String id, CameraConfig camera, Moment.Sighted sighted, List<Integer> imageTags) {}

  private final Supplier<FieldModel> field;
  private final List<CameraConfig> cameras;
  private final SightingWords words;
  private final Function<String, Optional<String>> cameraAction;

  /** The lines last drawn live, by id: a click on one says the latest of it. */
  private final Map<String, Line> latest = new ConcurrentHashMap<>();

  /** What a click on each line drawn live says, made once for each line so it stays the same. */
  private final Map<String, Supplier<Inspection>> said = new ConcurrentHashMap<>();

  /** The ids of the lines last drawn live, in order. */
  private List<String> drawnLive = List.of();

  SightLines(
      Supplier<FieldModel> field,
      List<CameraConfig> cameras,
      SightingWords words,
      Function<String, Optional<String>> cameraAction) {
    this.field = field;
    this.cameras = List.copyOf(cameras);
    this.words = words;
    this.cameraAction = cameraAction;
  }

  @Override
  public String id() {
    return "sight-lines";
  }

  @Override
  public String title() {
    return "Sight lines";
  }

  @Override
  public List<Legend> legend() {
    return List.of(
        Legend.line(Style.OK, "A camera's measurement of a tag that the estimator accepted"),
        Legend.line(Style.BAD.dashed(), "One it refused: click the line for why"),
        Legend.line(Style.MUTED, "One given to a localizer that does not say what became of it"));
  }

  /** Its lines run between the robot's cameras and the field's tags, so it adds no frames. */
  @Override
  public void describe(SceneBuilder scene) {}

  /** Draws a loop's lines, each saying what the loop had of it. */
  @Override
  void draw(Moment moment, SceneState state) {
    Shapes shapes = state.shapes();
    for (Line line : lines(moment)) {
      add(shapes, line, () -> inspection(line));
    }
  }

  /**
   * Draws the newest loop's lines. While the lines are the same ones as in the loop before, nothing
   * is drawn again, and each line is only handed its latest measurement, for a click to say.
   */
  @Override
  void drawLive(Moment moment, SceneState state) {
    List<Line> lines = lines(moment);
    List<String> ids = new ArrayList<>(lines.size());
    for (Line line : lines) {
      latest.put(line.id(), line);
      ids.add(line.id());
    }
    if (ids.equals(drawnLive)) {
      return;
    }
    drawnLive = ids;
    // Asking the state for its shapes starts this layer's shapes over, so it is asked only now.
    Shapes shapes = state.shapes();
    for (Line line : lines) {
      add(shapes, line, said.computeIfAbsent(line.id(), id -> () -> latestOf(id, line)));
    }
  }

  /** What a click says of a line drawn live: the latest measurement along it. */
  private Inspection latestOf(String id, Line asFirstDrawn) {
    return inspection(latest.getOrDefault(id, asFirstDrawn));
  }

  private static void add(Shapes shapes, Line line, Supplier<Inspection> inspection) {
    shapes
        .line(line.id(), SceneBuilder.ROOT)
        .between(
            WorldWords.cameraFrame(line.camera().id()), WorldWords.tagFrame(line.sighted().tag()))
        .style(style(line.sighted().fate()))
        .inspect(inspection);
  }

  /**
   * A loop's lines: for each camera, in the robot's order, the newest measurement of each tag the
   * field has. An image that was left out before it became a measurement has no line, and a tag the
   * field doesn't have has nowhere to draw one to.
   */
  private List<Line> lines(Moment moment) {
    List<Line> lines = new ArrayList<>();
    FieldModel on = field.get();
    for (CameraConfig camera : cameras) {
      Moment.OfCamera seen = moment.camera(camera.name());
      if (seen == null) {
        continue;
      }
      List<Integer> drawn = new ArrayList<>();
      // The sightings are newest first, so the first of a tag is its newest.
      for (Moment.Sighted sighted : seen.sightings()) {
        boolean drawable =
            sighted.measured()
                && !drawn.contains(sighted.tag())
                && on.tag(sighted.tag()).isPresent();
        if (drawable) {
          drawn.add(sighted.tag());
          lines.add(new Line(id(camera, sighted), camera, sighted, imageTags(seen, sighted)));
        }
      }
    }
    return lines;
  }

  /** A line's id: its camera, its tag, and how its measurement fared. */
  private static String id(CameraConfig camera, Moment.Sighted sighted) {
    String ending =
        switch (sighted.fate()) {
          case ACCEPTED -> ".ok";
          case GIVEN -> ".given";
          case REJECTED, LEFT_OUT, HELD_BACK -> ".bad";
        };
    return "ray." + camera.id() + "." + sighted.tag() + ending;
  }

  /**
   * How a line is drawn. A measurement given to a localizer that does not report what became of it
   * is drawn muted, as neither accepted nor rejected.
   */
  private static Style style(Sighting.Fate fate) {
    return switch (fate) {
      case ACCEPTED -> Style.OK;
      case GIVEN -> Style.MUTED;
      case REJECTED, LEFT_OUT, HELD_BACK -> Style.BAD.dashed();
    };
  }

  /** Every tag the image of a sighting saw, in the image's order. */
  private static List<Integer> imageTags(Moment.OfCamera seen, Moment.Sighted of) {
    List<Integer> tags = new ArrayList<>();
    for (Moment.Sighted each : seen.sightings()) {
      if (each.loopsAgo() == of.loopsAgo() && each.image() == of.image()) {
        // Newest first is the image's order backward.
        tags.addFirst(each.tag());
      }
    }
    return tags;
  }

  // ---- What a click says ----

  @Override
  Optional<Inspection> inspect(Moment moment, String shapeId) {
    for (Line line : lines(moment)) {
      if (line.id().equals(shapeId)) {
        return Optional.of(inspection(line));
      }
    }
    return Optional.empty();
  }

  /** What a click on a line says: the tag's verdict and its numbers. */
  private Inspection inspection(Line line) {
    Moment.Sighted sighted = line.sighted();
    String camera = line.camera().name();
    Inspection said =
        Inspection.of(SightingSentences.title(camera, sighted.tag()))
            .detail("Verdict", SightingSentences.verdict(sighted, words));
    if (sighted.fate() != Sighting.Fate.GIVEN) {
      // A measurement that was only given was compared with nothing: no innovation, and no χ².
      said = said.detail("Innovation", innovation(sighted)).detail("χ²", chiSquared(sighted));
    }
    said =
        said.detail("Distance", distanceToTheTag(sighted))
            .detail(
                "Measured pose",
                String.format(
                    Locale.ROOT,
                    "x %.2f m, y %.2f m, heading %.1f°",
                    sighted.measuredX(),
                    sighted.measuredY(),
                    Math.toDegrees(sighted.measuredHeading())))
            .detail("Solved", howItWasSolved(line));
    Optional<String> calibrate = cameraAction.apply(camera);
    boolean offersCalibration = calibrate.isPresent() && sighted.fate() != Sighting.Fate.GIVEN;
    return offersCalibration ? said.action(calibrate.get()) : said;
  }

  /**
   * How far the measurement was from the estimate. A sighting carries a number that isn't one for a
   * measurement that was refused before it was compared.
   */
  private static String innovation(Moment.Sighted sighted) {
    if (!Double.isFinite(sighted.innovationMeters())) {
      return "not compared";
    }
    return WorldWords.offset(sighted.innovationMeters())
        + " from the estimate, before this measurement moved it";
  }

  /** The measurement's χ² against the estimate, which likewise isn't a number if not compared. */
  private static String chiSquared(Moment.Sighted sighted) {
    if (!Double.isFinite(sighted.chiSquared())) {
      return "not compared";
    }
    return WorldWords.chiSquared(sighted.chiSquared());
  }

  private String distanceToTheTag(Moment.Sighted sighted) {
    Optional<Pose3d> onTheField = field.get().tag(sighted.tag());
    if (onTheField.isEmpty()) {
      return "the field has no such tag";
    }
    double meters =
        Math.hypot(
            sighted.measuredX() - onTheField.get().getX(),
            sighted.measuredY() - onTheField.get().getY());
    return WorldWords.meters(meters) + " from the measured pose to the tag";
  }

  /** How the image was solved, and from which tags: "multi tag from tags 25, 26". */
  private String howItWasSolved(Line line) {
    String solver =
        words.solver(line.sighted().solver()).toLowerCase(Locale.ROOT).replace('_', ' ');
    String tags = line.imageTags().stream().map(String::valueOf).collect(Collectors.joining(", "));
    return solver + " from tags " + tags;
  }
}
