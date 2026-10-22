package com.michaelgrundvig.frc.pitwall.world;

import static org.wpilib.units.Units.Meters;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.SceneState;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.robot.Body;
import java.util.List;
import java.util.Optional;
import org.wpilib.math.geometry.Pose3d;

/**
 * Where the robot really is, as a dashed ghost of its bumpers beside the estimate. Only the
 * simulator knows that, so only a simulator's record has it, and only then is this layer there. The
 * gap between the ghost and the estimate is what localization gets wrong.
 */
final class TruthLayer extends WorldLayer {
  private static final String FRAME = "truth";

  /** How far above the ghost's top its arrow out the front is drawn, and how far past its front. */
  private static final double FRONT_ARROW_RISE = 0.02;

  private static final double FRONT_ARROW_REACH = 0.2;

  private final Body body;

  TruthLayer(Body body) {
    this.body = body;
  }

  @Override
  public String id() {
    return "truth";
  }

  @Override
  public String title() {
    return "True pose";
  }

  @Override
  public List<Legend> legend() {
    return List.of(
        Legend.outline(
            Style.TRUTH.dashed(),
            "Where the robot really is, with an arrow out its front: only the simulator knows"));
  }

  @Override
  public void describe(SceneBuilder scene) {
    double length = body.bumperLength().in(Meters);
    double height = body.overallHeight().in(Meters);
    scene.frame(FRAME, SceneBuilder.ROOT);
    scene
        .box("truth.robot", FRAME)
        .size(length, body.bumperWidth().in(Meters), height)
        .at(0, 0, height / 2)
        .outline()
        .style(Style.TRUTH.dashed());
    scene
        .arrow("truth.front", FRAME)
        .from(0, 0, height + FRONT_ARROW_RISE)
        .to(length / 2 + FRONT_ARROW_REACH, 0, height + FRONT_ARROW_RISE)
        .style(Style.TRUTH.dashed());
  }

  /** The ghost stands where the simulator's record has the robot: raised and tilted as it is. */
  @Override
  void draw(Moment moment, SceneState state) {
    Optional<Pose3d> truth = moment.world().truth();
    if (truth.isPresent()) {
      state.pose(FRAME, truth.get());
    }
  }
}
