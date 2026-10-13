package com.michaelgrundvig.frc.pitwall.robot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A loop's record made by hand: the keys the runtime logs, each with a value, as a test sets them.
 * It stays as it was from one loop to the next, as the robot's log does, so a test changes only
 * what changes.
 */
public final class FakeRecord implements LoopRecord {
  private final Map<String, Object> values = new LinkedHashMap<>();
  private long timestampNanos;

  /** Sets a key's value: a Boolean, a Double, a Long, a String, or an array of one of those. */
  public FakeRecord put(String key, Object value) {
    values.put(key, value);
    return this;
  }

  /** Sets when the loop is. */
  public FakeRecord at(long timestampNanos) {
    this.timestampNanos = timestampNanos;
    return this;
  }

  /**
   * Logs a joint as the runtime does, ready and idle: its inputs under {@code Joints/<label>} and
   * its outputs under {@code RealOutputs/Joints/<label>}.
   */
  public FakeRecord joint(String label, double position, double velocity) {
    String in = LogKeys.joint(label);
    String out = LogKeys.jointOutputs(label);
    return put(in + LogKeys.CONNECTED, true)
        .put(in + LogKeys.FRESH, true)
        .put(in + LogKeys.POSITION, position)
        .put(in + LogKeys.VELOCITY, velocity)
        .put(in + LogKeys.APPLIED_VOLTS, 0.0)
        .put(in + LogKeys.MAGNET_BAD, false)
        .put(out + LogKeys.STATE, "READY")
        .put(out + LogKeys.COMMAND, "idle")
        .put(out + LogKeys.GOAL, position)
        .put(out + LogKeys.GOAL_NAME, "")
        .put(out + LogKeys.SETPOINT, position)
        .put(out + LogKeys.SETPOINT_VELOCITY, 0.0)
        .put(out + LogKeys.OUTCOME, "");
  }

  /** Logs a sensor as the runtime does. */
  public FakeRecord sensor(String label, boolean connected, boolean tripped, double meters) {
    String in = LogKeys.sensor(label);
    return put(in + LogKeys.SENSOR_CONNECTED, connected)
        .put(in + LogKeys.TRIPPED, tripped)
        .put(in + LogKeys.METERS, meters);
  }

  /** Logs a gate as the runtime does. */
  public FakeRecord gate(String name, boolean holds, String... blocking) {
    return put(LogKeys.GATES + name, holds).put(LogKeys.GATES + name + LogKeys.BLOCKING, blocking);
  }

  @Override
  public long timestampNanos() {
    return timestampNanos;
  }

  @Override
  public boolean has(String key) {
    return values.containsKey(key);
  }

  @Override
  public boolean flag(String key, boolean otherwise) {
    return values.get(key) instanceof Boolean flag ? flag : otherwise;
  }

  @Override
  public double number(String key, double otherwise) {
    return values.get(key) instanceof Number number ? number.doubleValue() : otherwise;
  }

  @Override
  public long whole(String key, long otherwise) {
    return values.get(key) instanceof Long whole ? whole : otherwise;
  }

  @Override
  public String text(String key, String otherwise) {
    return values.get(key) instanceof String text ? text : otherwise;
  }

  @Override
  public String[] texts(String key) {
    return values.get(key) instanceof String[] texts ? texts : NO_TEXTS;
  }

  @Override
  public float[] floats(String key) {
    return values.get(key) instanceof float[] floats ? floats : NO_FLOATS;
  }

  @Override
  public long[] wholes(String key) {
    return values.get(key) instanceof long[] wholes ? wholes : NO_WHOLES;
  }

  @Override
  public int keyCount() {
    return values.size();
  }

  @Override
  public void forEachKey(Consumer<String> each) {
    values.keySet().forEach(each);
  }

  /**
   * The test robot's log in a loop at rest: enabled in teleop, every joint ready and idle where it
   * rests, both sensors answering, and two gamepads with nothing pressed.
   */
  public static FakeRecord ofTheTestRobotAtRest() {
    FakeRecord record = new FakeRecord();
    record
        .put(LogKeys.ENABLED, true)
        .put(LogKeys.ROBOT_MODE, "TELEOPERATED")
        .joint("pivot", Math.toRadians(1.5), 0)
        .joint("roller", 0, 0)
        .joint("elevator", 0, 0)
        .joint("arm", Math.toRadians(-45), 0)
        .joint("shooter", 0, 0)
        .joint("claw", 0, 0)
        .sensor("claw beam", true, false, Double.NaN)
        .sensor("hopper", true, false, 0.8)
        .put(LogKeys.FIRED, new String[0]);
    for (int port = 0; port < 2; port++) {
      record
          .put(LogKeys.gamepad(port) + LogKeys.BUTTONS, 0L)
          .put(LogKeys.gamepad(port) + LogKeys.AXES, new float[6])
          .put(LogKeys.gamepad(port) + LogKeys.POVS, new long[] {-1});
    }
    return record;
  }
}
