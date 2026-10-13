package com.michaelgrundvig.frc.pitwall.scene;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A group of shapes in a scene that the page can show or hide as one: the field, the robot, its
 * cameras' sight lines. A layer is data, not drawing: it says what's there, in frames, and the page
 * draws it.
 *
 * <ul>
 *   <li>{@link #describe} says what stays: the frames it draws in and its shapes. It's called when
 *       the scene is added, and again whenever the layer's {@link #configuration()} changes (a
 *       camera recalibrated), never on a schedule.
 *   <li>{@link #update} says what moves, in the robot loop, every loop while a page shows the
 *       scene, and never while none does. Keep it to a handful of numbers.
 *   <li>{@link #inspect} answers a click on one of its shapes, on a web server thread. Most layers
 *       don't need it: a shape made with {@link Shape#inspect} answers for itself.
 *   <li>{@link #recorded} says the same of an earlier loop, for a layer drawn from the robot's
 *       record.
 * </ul>
 *
 * <p>A layer that throws costs only itself: its last description, or its last update, stands, and
 * what it threw is printed once.
 */
public interface Layer {
  /** Its id: a name, unique in its scene. */
  String id();

  /** What the page calls it, beside its switch: "Field", "Robot". */
  String title();

  /** Says what stays: its frames and shapes. */
  void describe(SceneBuilder scene);

  /** Says what moves: frames' poses, values, styles, its shapes of the moment. */
  default void update(SceneState state) {}

  /**
   * What a click on one of its shapes says, when the shape doesn't say for itself: called on a web
   * server thread, so it reads only what the robot loop has published whole.
   *
   * @return empty when it has nothing to say about that shape
   */
  default Optional<Inspection> inspect(String shapeId) {
    return Optional.empty();
  }

  /**
   * What its description is drawn from: when this changes (by {@code equals}), it's described
   * again. Read in the robot loop while a page is open. The same, always, unless overridden.
   */
  default Object configuration() {
    return "";
  }

  /**
   * The layer as an earlier loop of the robot's session had it, for a layer drawn from the robot's
   * record. The page can then show the scene at any loop the record holds. A layer drawn from live
   * objects has none: it is the present only, and the page says so.
   */
  default Optional<Recorded> recorded() {
    return Optional.empty();
  }

  /** A layer as the robot's record has it, loop by loop. Asked on a web server thread. */
  interface Recorded {
    /** Nothing in the layer moves, so it is the same in every loop: a field's walls. */
    Recorded STILL = (loop, state) -> true;

    /**
     * Says what moved as a loop had it: what {@link Layer#update} said in that loop, all of it.
     *
     * @param loop the loop's index in the robot's session
     * @return false when the record does not hold the loop
     */
    boolean update(long loop, SceneState state);

    /**
     * What a click on one of the layer's shapes says of a loop. Empty for a shape that says the
     * same in every loop, which then answers as it was described.
     */
    default Optional<Inspection> inspect(long loop, String shapeId) {
      return Optional.empty();
    }
  }

  /** A layer that only describes: shapes that never move, such as a field's walls. */
  static Layer of(String id, String title, Consumer<SceneBuilder> describe) {
    return new Layer() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public String title() {
        return title;
      }

      @Override
      public void describe(SceneBuilder scene) {
        describe.accept(scene);
      }
    };
  }
}
