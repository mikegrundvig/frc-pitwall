package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The last few minutes of the robot, one {@link Moment} for each loop, in a ring of fixed size.
 *
 * <p>Its memory is taken once, as it is made, and never grows: a moment is kept as plain numbers in
 * four arrays, and its texts as small numbers ({@link Words}). When the ring is full, each new loop
 * replaces the oldest. A {@link Moment} is put together only when one is asked for.
 *
 * <p>A loop is known by its index, which counts from 0 at the first loop taken and never repeats,
 * so an index names the same loop for as long as the ring still holds it.
 *
 * <p>Safe from any thread: AdvantageKit's receiver thread writes, and the robot loop and the web
 * server's threads read.
 */
final class SessionMemory {
  /** How many gates it keeps. A robot with more keeps the first ones the record names. */
  static final int GATES = 16;

  /** How many of a gate's blocking terms it keeps. */
  static final int BLOCKING_TERMS = 2;

  /** How many of the bindings that fired in one loop it keeps. */
  static final int FIRED = 4;

  /** How many gamepads it keeps: the driver's and the operator's, on ports 0 and 1. */
  static final int GAMEPADS = 2;

  /** How many of a gamepad's axes it keeps: a standard gamepad has six. */
  static final int AXES = 6;

  // What one joint takes in each array.
  private static final int JOINT_DOUBLES = 3; // position, velocity, goal: what the rules judge
  private static final int JOINT_FLOATS = 4; // applied volts, setpoint, its velocity, absolute
  private static final int JOINT_SHORTS = 5; // flags, state, command, goal's name, outcome
  private static final int GATE_SHORTS = 1 + BLOCKING_TERMS;

  // A joint's flags, and a sensor's.
  private static final int PRESENT = 1;
  private static final int CONNECTED = 2;
  private static final int FRESH = 4;
  private static final int MAGNET_BAD = 8;
  private static final int ENCODER_LOST = 16;
  private static final int TRIPPED = 4;
  // A gate's flags.
  private static final int HOLDS = 2;
  // The loop's flags.
  private static final int ENABLED = 1;
  private static final int MODE_SHIFT = 1;

  private final List<String> jointLabels;
  private final List<String> sensorLabels;
  private final int capacity;

  private final int doublesEach;
  private final int floatsEach;
  private final int shortsEach;
  private final int longsEach;

  private final int sensorDoubles;
  private final int axisFloats;
  private final int sensorShorts;
  private final int gateShorts;
  private final int firedShorts;
  private final int povShorts;
  private final int loopShort;

  private final long[] longs;
  private final double[] doubles;
  private final float[] floats;
  private final short[] shorts;

  private final Words words = new Words();
  private final List<String> gateNames = new ArrayList<>();

  /** How many loops have been taken since the start. */
  private long taken;

  /** The slot being written, between {@link #open} and {@link #close}. */
  private int slot;

  /**
   * @param jointLabels the robot's joints, in the model's order
   * @param sensorLabels the robot's switches and rangefinders
   * @param capacity how many loops it keeps
   */
  SessionMemory(List<String> jointLabels, List<String> sensorLabels, int capacity) {
    this.jointLabels = List.copyOf(jointLabels);
    this.sensorLabels = List.copyOf(sensorLabels);
    this.capacity = capacity;
    int joints = jointLabels.size();
    int sensors = sensorLabels.size();

    sensorDoubles = joints * JOINT_DOUBLES;
    doublesEach = sensorDoubles + sensors;
    axisFloats = joints * JOINT_FLOATS;
    floatsEach = axisFloats + GAMEPADS * AXES;
    sensorShorts = joints * JOINT_SHORTS;
    gateShorts = sensorShorts + sensors;
    firedShorts = gateShorts + GATES * GATE_SHORTS;
    povShorts = firedShorts + FIRED;
    loopShort = povShorts + GAMEPADS;
    shortsEach = loopShort + 1;
    longsEach = 1 + GAMEPADS;

    longs = new long[capacity * longsEach];
    doubles = new double[capacity * doublesEach];
    floats = new float[capacity * floatsEach];
    shorts = new short[capacity * shortsEach];
  }

  // ---- What it costs ----

  /** How many bytes one loop takes. */
  int bytesPerMoment() {
    return longsEach * Long.BYTES
        + doublesEach * Double.BYTES
        + floatsEach * Float.BYTES
        + shortsEach * Short.BYTES;
  }

  /** How many loops it keeps. */
  int capacity() {
    return capacity;
  }

  // ---- Writing one loop: open, put each part, close. All under this object's lock. ----

  /** Starts the next loop, over the oldest when the ring is full, with everything in it absent. */
  void open(long timestampNanos, Moment.Enabled enabled, Moment.Mode mode) {
    slot = (int) (taken % capacity);
    java.util.Arrays.fill(shorts, slot * shortsEach, (slot + 1) * shortsEach, (short) 0);
    longs[slot * longsEach] = timestampNanos;
    int flags = (enabled == Moment.Enabled.ENABLED ? ENABLED : 0) | mode.ordinal() << MODE_SHIFT;
    shorts[slot * shortsEach + loopShort] = (short) flags;
  }

  /** Puts one joint's numbers and where it stands. */
  void putJoint(
      int joint,
      Moment.Link link,
      double position,
      double velocity,
      double goal,
      double appliedVolts,
      double setpoint,
      double setpointVelocity,
      double absoluteAt,
      Moment.Encoder encoder) {
    int d = slot * doublesEach + joint * JOINT_DOUBLES;
    doubles[d] = position;
    doubles[d + 1] = velocity;
    doubles[d + 2] = goal;
    int f = slot * floatsEach + joint * JOINT_FLOATS;
    floats[f] = (float) appliedVolts;
    floats[f + 1] = (float) setpoint;
    floats[f + 2] = (float) setpointVelocity;
    floats[f + 3] = (float) absoluteAt;
    int flags = PRESENT;
    flags |= link == Moment.Link.LOST ? 0 : CONNECTED;
    flags |= link == Moment.Link.ANSWERS ? FRESH : 0;
    flags |= encoder == Moment.Encoder.MAGNET_BAD ? MAGNET_BAD : 0;
    flags |= encoder == Moment.Encoder.LOST ? ENCODER_LOST : 0;
    shorts[slot * shortsEach + joint * JOINT_SHORTS] = (short) flags;
  }

  /** Puts one joint's texts, as the runtime logs them. */
  void putJointTexts(int joint, String state, String command, String goalName, String outcome) {
    int s = slot * shortsEach + joint * JOINT_SHORTS;
    shorts[s + 1] = words.numberOf(state);
    shorts[s + 2] = words.numberOf(command);
    shorts[s + 3] = words.numberOf(goalName);
    shorts[s + 4] = words.numberOf(outcome);
  }

  /** Puts one sensor's reading. */
  void putSensor(int sensor, Moment.Link link, Moment.Switched switched, double meters) {
    doubles[slot * doublesEach + sensorDoubles + sensor] = meters;
    int flags = PRESENT;
    flags |= link == Moment.Link.LOST ? 0 : CONNECTED;
    flags |= switched == Moment.Switched.TRIPPED ? TRIPPED : 0;
    shorts[slot * shortsEach + sensorShorts + sensor] = (short) flags;
  }

  /**
   * The place a gate is kept in, given one if the gate is new; -1 for a gate past the {@link
   * #GATES} it keeps.
   */
  int gate(String name) {
    int known = gateNames.indexOf(name);
    if (known >= 0 || gateNames.size() >= GATES) {
      return known;
    }
    gateNames.add(name);
    return gateNames.size() - 1;
  }

  /** Puts one gate: whether it holds, and the first of the terms that keep it from holding. */
  void putGate(int gate, Moment.Held held, String[] blocking) {
    int s = slot * shortsEach + gateShorts + gate * GATE_SHORTS;
    shorts[s] = (short) (PRESENT | (held == Moment.Held.HOLDS ? HOLDS : 0));
    for (int term = 0; term < BLOCKING_TERMS && term < blocking.length; term++) {
      shorts[s + 1 + term] = words.numberOf(blocking[term]);
    }
  }

  /** Puts the bindings that fired in this loop: the first {@link #FIRED} of them. */
  void putFired(String[] fired) {
    int s = slot * shortsEach + firedShorts;
    for (int each = 0; each < FIRED && each < fired.length; each++) {
      shorts[s + each] = words.numberOf(fired[each]);
    }
  }

  /** Puts one gamepad: its buttons, its first {@link #AXES} axes, and its first POV. */
  void putGamepad(int gamepad, long buttons, float[] axes, int pov) {
    longs[slot * longsEach + 1 + gamepad] = buttons;
    int f = slot * floatsEach + axisFloats + gamepad * AXES;
    for (int axis = 0; axis < AXES; axis++) {
      floats[f + axis] = axis < axes.length ? axes[axis] : 0;
    }
    shorts[slot * shortsEach + povShorts + gamepad] = (short) pov;
  }

  /** Ends the loop being written: it is now the newest. */
  void close() {
    taken++;
  }

  // ---- Reading ----

  /** How many loops have been taken since the start: one more than the newest loop's index. */
  synchronized long taken() {
    return taken;
  }

  /** The index of the oldest loop it still holds; equal to {@link #taken} while it holds none. */
  synchronized long oldest() {
    return Math.max(0, taken - capacity);
  }

  /** How many texts it has kept, of the {@link Words#MOST} it keeps. */
  synchronized int texts() {
    return words.size();
  }

  /** The loop with an index, or null when it holds none: not taken yet, or replaced since. */
  synchronized @Nullable Moment at(long index) {
    if (index < oldest() || index >= taken) {
      return null;
    }
    return read((int) (index % capacity));
  }

  /**
   * The index of the loop whose time is nearest a time in nanoseconds, or -1 while it holds none.
   * The loops are in the order of their times, so it is found by halving.
   */
  synchronized long nearest(long timestampNanos) {
    if (taken == 0) {
      return -1;
    }
    long low = oldest();
    long high = taken - 1;
    while (low < high) {
      long middle = (low + high) >>> 1;
      if (timeOf(middle) < timestampNanos) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    boolean earlierIsNearer =
        low > oldest() && timestampNanos - timeOf(low - 1) <= timeOf(low) - timestampNanos;
    return earlierIsNearer ? low - 1 : low;
  }

  /**
   * The index of the latest loop, at or before one given and no more than a number of loops before
   * it, in which a binding fired; -1 when none did.
   */
  synchronized long lastFiring(long upTo, int within) {
    long from = Math.max(oldest(), upTo - within);
    for (long index = Math.min(upTo, taken - 1); index >= from; index--) {
      int s = (int) (index % capacity) * shortsEach + firedShorts;
      if (shorts[s] != 0) {
        return index;
      }
    }
    return -1;
  }

  /** When a loop it holds was, in nanoseconds. */
  synchronized long timeOf(long index) {
    return longs[(int) (index % capacity) * longsEach];
  }

  private Moment read(int from) {
    int s = from * shortsEach;
    int loop = shorts[s + loopShort];
    List<Moment.OfJoint> joints = new ArrayList<>();
    for (int joint = 0; joint < jointLabels.size(); joint++) {
      int flags = shorts[s + joint * JOINT_SHORTS];
      if ((flags & PRESENT) != 0) {
        joints.add(readJoint(from, joint, flags));
      }
    }
    List<Moment.OfSensor> sensors = new ArrayList<>();
    for (int sensor = 0; sensor < sensorLabels.size(); sensor++) {
      int flags = shorts[s + sensorShorts + sensor];
      if ((flags & PRESENT) != 0) {
        sensors.add(
            new Moment.OfSensor(
                sensorLabels.get(sensor),
                (flags & CONNECTED) != 0 ? Moment.Link.ANSWERS : Moment.Link.LOST,
                (flags & TRIPPED) != 0 ? Moment.Switched.TRIPPED : Moment.Switched.CLEAR,
                doubles[from * doublesEach + sensorDoubles + sensor]));
      }
    }
    List<Moment.OfGate> gates = new ArrayList<>();
    for (int gate = 0; gate < gateNames.size(); gate++) {
      int g = s + gateShorts + gate * GATE_SHORTS;
      if ((shorts[g] & PRESENT) != 0) {
        gates.add(
            new Moment.OfGate(
                gateNames.get(gate),
                (shorts[g] & HOLDS) != 0 ? Moment.Held.HOLDS : Moment.Held.DOES_NOT_HOLD,
                texts(g + 1, BLOCKING_TERMS)));
      }
    }
    List<Moment.OfGamepad> gamepads = new ArrayList<>();
    for (int gamepad = 0; gamepad < GAMEPADS; gamepad++) {
      List<Double> axes = new ArrayList<>();
      for (int axis = 0; axis < AXES; axis++) {
        axes.add((double) floats[from * floatsEach + axisFloats + gamepad * AXES + axis]);
      }
      gamepads.add(
          new Moment.OfGamepad(
              gamepad,
              longs[from * longsEach + 1 + gamepad],
              axes,
              shorts[s + povShorts + gamepad]));
    }
    return new Moment(
        longs[from * longsEach],
        (loop & ENABLED) != 0 ? Moment.Enabled.ENABLED : Moment.Enabled.DISABLED,
        Moment.Mode.values()[loop >> MODE_SHIFT],
        joints,
        sensors,
        gates,
        texts(s + firedShorts, FIRED),
        gamepads);
  }

  private Moment.OfJoint readJoint(int from, int joint, int flags) {
    int d = from * doublesEach + joint * JOINT_DOUBLES;
    int f = from * floatsEach + joint * JOINT_FLOATS;
    int s = from * shortsEach + joint * JOINT_SHORTS;
    Moment.Link link = Moment.Link.LOST;
    if ((flags & CONNECTED) != 0) {
      link = (flags & FRESH) != 0 ? Moment.Link.ANSWERS : Moment.Link.STALE;
    }
    Moment.Encoder encoder = Moment.Encoder.HEALTHY;
    if ((flags & ENCODER_LOST) != 0) {
      encoder = Moment.Encoder.LOST;
    } else if ((flags & MAGNET_BAD) != 0) {
      encoder = Moment.Encoder.MAGNET_BAD;
    }
    return new Moment.OfJoint(
        jointLabels.get(joint),
        link,
        doubles[d],
        doubles[d + 1],
        floats[f],
        doubles[d + 2],
        floats[f + 1],
        floats[f + 2],
        floats[f + 3],
        encoder,
        words.textOf(shorts[s + 1]),
        words.textOf(shorts[s + 2]),
        words.textOf(shorts[s + 3]),
        words.textOf(shorts[s + 4]));
  }

  /** The texts kept in some places in a row, up to the first empty one. */
  private List<String> texts(int from, int most) {
    List<String> texts = new ArrayList<>();
    for (int each = 0; each < most && shorts[from + each] != 0; each++) {
      texts.add(words.textOf(shorts[from + each]));
    }
    return texts;
  }
}
