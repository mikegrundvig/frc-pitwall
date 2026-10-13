package com.michaelgrundvig.frc.pitwall.world;

import static org.wpilib.units.Units.Meters;

import com.michaelgrundvig.frc.pitwall.scene.Layer;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Each camera's view, as far as {@link #VIEW_METERS}. The layer is off until switched on, since
 * four views crowd the robot they belong to. One camera's view is also shown while that camera is
 * clicked, because {@link RobotLayer}'s camera reveals its own. The views are drawn in the cameras'
 * frames, which the robot's layer adds.
 */
final class CameraViews implements Layer {
  /**
   * How far a camera's view is drawn, at most. A camera reads tags out to several meters, and four
   * views drawn that far cover the field and the robot with it. Drawn this far, a view still shows
   * which way its camera points and how wide it sees.
   */
  static final double VIEW_METERS = 3;

  private final List<CameraConfig> cameras;
  private final Function<String, Optional<String>> cameraAction;
  private final SimulatedParts simulated;

  CameraViews(
      List<CameraConfig> cameras,
      Function<String, Optional<String>> cameraAction,
      SimulatedParts simulated) {
    this.simulated = simulated;
    this.cameras = List.copyOf(cameras);
    this.cameraAction = cameraAction;
  }

  /** The id of the shape that is a camera's full view. */
  static String view(CameraConfig camera) {
    return WorldWords.cameraFrame(camera.id()) + ".view";
  }

  /** A view is drawn in its camera's frame, which the robot's layer moves: nothing here does. */
  @Override
  public Optional<Recorded> recorded() {
    return Optional.of(Recorded.STILL);
  }

  @Override
  public String id() {
    return "camera-views";
  }

  @Override
  public String title() {
    return "Camera views";
  }

  @Override
  public List<Legend> legend() {
    return List.of(
        Legend.outline(
            Style.MUTED, "What a camera sees, drawn out to 3 m: it reads tags farther than that"));
  }

  @Override
  public void describe(SceneBuilder scene) {
    for (CameraConfig camera : cameras) {
      RobotLayer.FieldOfView sees = RobotLayer.fieldOfView(camera.sensor());
      scene
          .frustum(view(camera), WorldWords.cameraFrame(camera.id()))
          .fov(sees.acrossRadians(), sees.upAndDownRadians())
          .range(0, Math.min(VIEW_METERS, camera.sensor().sightRange().in(Meters)))
          .inspect(() -> WorldWords.camera(camera, cameraAction, simulated));
    }
  }
}
