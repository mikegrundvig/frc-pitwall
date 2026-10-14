package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.robot.SightingWords;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.Scene;
import com.michaelgrundvig.frc.pitwall.scene.StillState;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The robot's world on the page's World view, drawn from the robot's record: where localization
 * believed the robot was and how sure it was, what each camera saw and what became of it, and in
 * the simulator where the robot really was. It has a layer for each of:
 *
 * <ul>
 *   <li>the field the robot plays on, what stands on it, its tags' numbers, and where a robot may
 *       start;
 *   <li>the robot where the estimate has it, with its wheels, cameras and gyro as its definition
 *       has them, and what rides on a joint where the joint was;
 *   <li>the wheels' drive arrows, and the switches and rangefinders;
 *   <li>each camera's name and its view, which are off until switched on or the camera is clicked;
 *   <li>how sure the estimate was, as an ellipse;
 *   <li>each camera's sight lines to the tags it measured, solid where the estimator accepted the
 *       measurement and dashed where it refused it;
 *   <li>in the simulator, the simulator's truth beside the estimate, as a dashed ghost.
 * </ul>
 *
 * <p>What moves is a function of the robot's definition, the field and one loop's {@link Moment}
 * ({@link #draw}), so the view is the same thing at any loop: the newest, live, or the one the
 * page's cursor is on. A click on a sight line says why its measurement was accepted or refused in
 * that loop, a click on a camera says how it's mounted, and a click on the ellipse how sure the
 * estimate was. In the simulator a click on a camera, the gyro, a wheel or the bumpers also ends in
 * what can be made to fail there ({@link SimulatedParts}). Each layer says what its marks mean, for
 * the page's legend.
 *
 * <p>The view draws the code's own beliefs. A camera's wedge pointing the wrong way is a typo in
 * {@code Robots.java}, found at a glance.
 */
public final class WorldScene {
  /** The scene's id: the page's address for it is {@code #world/world}. */
  public static final String ID = "world";

  /** Whether the robot's record says where the robot really is. */
  public enum Truth {
    /** A simulator's record: it logs the true pose, and the view draws it beside the estimate. */
    IN_THE_RECORD,
    /** A real robot's: nobody knows the true pose, and the view has no layer for it. */
    NOT_KNOWN
  }

  private final FieldLayer field;
  private final RobotLayer robot;
  private final CameraViews views;

  /** The layers that move, in the scene's order. */
  private final List<WorldLayer> moving = new ArrayList<>();

  private WorldScene(
      RobotDefinition definition,
      Supplier<FieldModel> onField,
      Truth truth,
      SightingWords words,
      Function<String, Optional<String>> cameraAction,
      SimulatedParts simulated) {
    field = new FieldLayer(onField, definition.body(), simulated);
    robot = new RobotLayer(definition, cameraAction, simulated);
    views = new CameraViews(definition.cameras(), cameraAction, simulated);
    moving.add(robot);
    moving.add(new Uncertainty());
    moving.add(new SightLines(onField, definition.cameras(), words, cameraAction));
    if (truth == Truth.IN_THE_RECORD) {
      moving.add(new TruthLayer(definition.body()));
    }
  }

  /**
   * The world of one robot on one field.
   *
   * @param robot what the robot is: its frame, wheels, cameras and IMUs, and where each is mounted
   * @param field the field the robot believes it plays on, which is the selected venue's once there
   *     is one
   * @param truth whether the record says where the robot really is, as a simulator's does
   * @param words the robot program's own words for what became of a camera's image
   * @param cameraAction the Pitwall action that calibrates a camera's mount, by the camera's name.
   *     A camera's details and a sight line's details end in it.
   */
  public static WorldScene of(
      RobotDefinition robot,
      Supplier<FieldModel> field,
      Truth truth,
      SightingWords words,
      Function<String, Optional<String>> cameraAction) {
    return new WorldScene(robot, field, truth, words, cameraAction, SimulatedParts.NONE);
  }

  /**
   * The world of one simulated robot on one field: as {@link #of(RobotDefinition, Supplier, Truth,
   * SightingWords, Function)}, and a click on a camera, the gyro, a wheel or the bumpers also ends
   * in the faults of the simulator's part it is.
   *
   * @param simulated which of the simulator's parts each part of the robot is
   */
  public static WorldScene of(
      RobotDefinition robot,
      Supplier<FieldModel> field,
      Truth truth,
      SightingWords words,
      Function<String, Optional<String>> cameraAction,
      SimulatedParts simulated) {
    return new WorldScene(robot, field, truth, words, cameraAction, simulated);
  }

  /**
   * What moves in the view, as one loop had it: every frame's pose, every value and style, and
   * every layer's shapes of the moment. It reads nothing but the moment, the robot's definition and
   * the field.
   */
  public StillState draw(Moment moment) {
    StillState state = new StillState(moment.timestampNanos());
    for (WorldLayer layer : moving) {
      layer.draw(moment, state.layer(layer.id()));
    }
    return state;
  }

  /**
   * What a click on a shape says of one loop, for a shape whose answer is the loop's: a sight line,
   * the ellipse. Empty for a shape that says the same in every loop, such as a tag or a camera.
   */
  public Optional<Inspection> inspect(Moment moment, String shapeId) {
    for (WorldLayer layer : moving) {
      Optional<Inspection> said = layer.inspect(moment, shapeId);
      if (said.isPresent()) {
        return said;
      }
    }
    return Optional.empty();
  }

  /**
   * The world as a scene for Pitwall, drawn from a robot's session: {@code
   * pitwall.scenes().add(world.scene(session))}. Live it shows the session's newest loop, and the
   * page can ask for any loop the session holds.
   */
  public Scene scene(RobotSession session) {
    Newest newest = new Newest(session);
    Scene scene =
        Scene.named(ID, "World")
            .layer(field)
            .layer(field.elements())
            .layer(field.tagNumbers())
            .layer(field.starts());
    for (WorldLayer layer : moving) {
      layer.follow(newest);
      scene.layer(layer);
      if (layer == robot) {
        // What is drawn in the frames the robot's layer adds, each a layer the page can switch.
        // A camera's name and view are off until asked for: four of each crowd the robot.
        scene
            .layer(robot.wheelArrows())
            .layer(robot.sensors())
            .layer(robot.cameraNames(), Scene.Visibility.OFF)
            .layer(views, Scene.Visibility.OFF);
      }
    }
    return scene.focus(RobotLayer.FRAME);
  }
}
