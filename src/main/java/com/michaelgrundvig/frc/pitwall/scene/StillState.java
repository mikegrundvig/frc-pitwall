package com.michaelgrundvig.frc.pitwall.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What moves in a scene, kept as it was said for one moment: the frames' poses, the values, the
 * styles and the shapes of the moment. A layer says them to this as it says them to the scene's
 * live state, and nothing is sent anywhere.
 *
 * <p>It is what a scene drawn from the robot's record is made with: the scene as one loop had it,
 * whole, where the live state sends only what changed ({@link Scenes#stateAt}). And it is what a
 * test reads to see what a layer said.
 *
 * <p>Not thread-safe: one thread makes one, fills it and reads it.
 */
public final class StillState implements SceneState {
  private final long nowNanos;
  private final Map<String, double[]> poses = new LinkedHashMap<>();
  private final Map<String, Double> values = new LinkedHashMap<>();
  private final Map<String, String> styles = new LinkedHashMap<>();
  private final Map<String, ShapeList> shapes = new LinkedHashMap<>();
  private String layer = "";

  /**
   * @param nowNanos the moment it is of, on the robot's clock, in nanoseconds
   */
  public StillState(long nowNanos) {
    this.nowNanos = nowNanos;
  }

  /**
   * Says which layer speaks next: the shapes of the moment that follow are that layer's.
   *
   * @return this, to hand to the layer
   */
  public StillState layer(String id) {
    layer = id;
    return this;
  }

  @Override
  public long nowNanos() {
    return nowNanos;
  }

  @Override
  public void pose(
      String frame, double x, double y, double z, double roll, double pitch, double yaw) {
    boolean finite =
        Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(z)
            && Double.isFinite(roll)
            && Double.isFinite(pitch)
            && Double.isFinite(yaw);
    if (finite) {
      poses.put(frame, new double[] {x, y, z, roll, pitch, yaw});
    }
  }

  @Override
  public void value(String name, double value) {
    if (Double.isFinite(value)) {
      values.put(name, value);
    }
  }

  @Override
  public void style(String shape, @Nullable Style style) {
    if (style == null) {
      styles.remove(shape);
    } else {
      styles.put(shape, style.wire());
    }
  }

  @Override
  public Shapes shapes() {
    ShapeList moment = new ShapeList();
    shapes.put(layer, moment);
    return moment;
  }

  // ---- What was said ----

  /**
   * Each frame's pose as it was said: x, y and z in meters, then roll, pitch and yaw in radians.
   */
  public Map<String, double[]> poses() {
    return Collections.unmodifiableMap(poses);
  }

  /** Each value as it was said. */
  public Map<String, Double> values() {
    return Collections.unmodifiableMap(values);
  }

  /** Each restyled shape's style, as the page reads it: "bad dashed". */
  public Map<String, String> styles() {
    return Collections.unmodifiableMap(styles);
  }

  /** Each layer's shapes of the moment, by the layer's id, each shape as the page reads it. */
  public Map<String, List<Map<String, Object>>> shapesOfTheMoment() {
    Map<String, List<Map<String, Object>>> byLayer = new LinkedHashMap<>();
    shapes.forEach(
        (id, moment) -> {
          List<Map<String, Object>> wires = new ArrayList<>();
          for (Shape<?> shape : moment.shapes()) {
            wires.add(Collections.unmodifiableMap(shape.wire()));
          }
          byLayer.put(id, List.copyOf(wires));
        });
    return Collections.unmodifiableMap(byLayer);
  }

  /**
   * All of it as the page reads a scene's state: {@code {"frames": {name: pose}, "values": {name:
   * number}, "styles": {shape: style}, "shapes": {layer: [shape, ...]}}}, with lengths to the
   * millimeter and angles to the tenth of a degree.
   *
   * @param resolutions each value's resolution, by its name; a value without one goes to the
   *     millimeter
   */
  Map<String, Object> wire(Map<String, Double> resolutions) {
    Map<String, Object> frames = new LinkedHashMap<>();
    poses.forEach(
        (name, pose) ->
            frames.put(name, Wire.pose(pose[0], pose[1], pose[2], pose[3], pose[4], pose[5])));
    Map<String, Object> rounded = new LinkedHashMap<>();
    values.forEach(
        (name, value) ->
            rounded.put(
                name, Wire.toResolution(value, resolutions.getOrDefault(name, Wire.METER_STEP))));
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("frames", frames);
    wire.put("values", rounded);
    wire.put("styles", new LinkedHashMap<>(styles));
    wire.put("shapes", shapesOfTheMoment());
    return wire;
  }
}
