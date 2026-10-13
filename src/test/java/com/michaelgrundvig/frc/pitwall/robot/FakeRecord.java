package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.BindingFired;
import com.michaelgrundvig.frc.world.record.BindingStart;
import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveOutcome;
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

  /**
   * Sets a key's value: a Boolean, a Double, a Long, a String, one of the model's facts, or an
   * array of one of those.
   */
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
        .put(out + LogKeys.STATE, JointState.READY)
        .put(out + LogKeys.FAULT, "")
        .put(out + LogKeys.OWNER, "IDLE")
        .put(out + LogKeys.COMMAND, "")
        .put(out + LogKeys.GOAL, position)
        .put(out + LogKeys.GOAL_NAME, "")
        .put(out + LogKeys.AT, JointPlace.UNNAMED)
        .put(out + LogKeys.HELD_BY, new Holder[0])
        .put(out + LogKeys.SETPOINT, position)
        .put(out + LogKeys.SETPOINT_VELOCITY, 0.0)
        .put(out + LogKeys.OUTCOME, new MoveOutcome[0])
        .put(out + LogKeys.OUTCOME_NAME, "")
        .put(out + LogKeys.OUTCOME_WHY, "")
        .put(out + LogKeys.OUTCOME_HELD_BY, new Holder[0]);
  }

  /** Logs a sensor as the runtime does. */
  public FakeRecord sensor(String label, boolean connected, boolean tripped, double meters) {
    String in = LogKeys.sensor(label);
    return put(in + LogKeys.SENSOR_CONNECTED, connected)
        .put(in + LogKeys.TRIPPED, tripped)
        .put(in + LogKeys.METERS, meters);
  }

  /** Logs a gate as the runtime does, with what keeps it from holding in a team's own words. */
  public FakeRecord gate(String name, boolean holds, String... blocking) {
    GateTerm[] terms = new GateTerm[blocking.length];
    for (int each = 0; each < blocking.length; each++) {
      terms[each] = GateTerm.text(each);
    }
    return put(LogKeys.GATES + name, holds)
        .put(LogKeys.GATES + name + LogKeys.BLOCKING, terms)
        .put(LogKeys.GATES + name + LogKeys.BLOCKING_TEXT, blocking);
  }

  /**
   * Logs the bindings that fired in a loop as the runtime does: each a fact that points into the
   * list of the robot's bindings, which is logged beside it.
   */
  public FakeRecord fired(String... bindings) {
    BindingFired[] fired = new BindingFired[bindings.length];
    for (int each = 0; each < bindings.length; each++) {
      fired[each] =
          new BindingFired(
              each, 1, BindingFired.Control.RIGHT_TRIGGER, Identity.NONE, BindingStart.WHILE_HELD);
    }
    if (bindings.length > 0) {
      put(LogKeys.BINDINGS, bindings);
    }
    return put(LogKeys.FIRED, fired);
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
  public <R extends Record> R fact(String key, R otherwise) {
    Object value = values.get(key);
    if (value == null || value.getClass() != otherwise.getClass()) {
      return otherwise;
    }
    @SuppressWarnings("unchecked") // it is of the class asked for
    R fact = (R) value;
    return fact;
  }

  @Override
  public <R extends Record> R[] facts(String key, R[] otherwise) {
    Object value = values.get(key);
    if (value == null || value.getClass() != otherwise.getClass()) {
      return otherwise;
    }
    @SuppressWarnings("unchecked") // it is of the class asked for
    R[] facts = (R[]) value;
    return facts;
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
        .fired();
    for (int port = 0; port < 2; port++) {
      record
          .put(LogKeys.gamepad(port) + LogKeys.BUTTONS, 0L)
          .put(LogKeys.gamepad(port) + LogKeys.AXES, new float[6])
          .put(LogKeys.gamepad(port) + LogKeys.POVS, new long[] {-1});
    }
    return record;
  }
}
