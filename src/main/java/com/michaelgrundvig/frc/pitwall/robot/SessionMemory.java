package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.record.GateTerm;
import com.michaelgrundvig.frc.world.record.Holder;
import com.michaelgrundvig.frc.world.record.Identity;
import com.michaelgrundvig.frc.world.record.JointOwner;
import com.michaelgrundvig.frc.world.record.JointPlace;
import com.michaelgrundvig.frc.world.record.JointState;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The last few minutes of the robot, one {@link Moment} for each loop, in a ring of fixed size.
 *
 * <p>Its memory is taken once, as it is made, and never grows: a moment is kept as plain numbers in
 * four arrays: a fact's fields each in their place, and its texts and its moves' outcomes as small
 * numbers ({@link Words}, {@link Moves}). When the ring is full, each new loop replaces the oldest.
 * A {@link Moment} is put together only when one is asked for.
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
  private static final int JOINT_FLOATS = 5; // applied volts, setpoint, its velocity, absolute,

  // and how long its homing may take

  /** How many of the things that hold a joint in one loop it keeps. */
  static final int HOLDERS = 3;

  // A joint's shorts: its flags, its state, three texts, where it is, what holds it, its last move.
  private static final int STATE = 1;
  private static final int FAULT = 2;
  private static final int COMMAND = 3;
  private static final int GOAL_NAME = 4;
  private static final int WHERE = 5;
  private static final int PLACE = 6;
  private static final int OTHER = 7;
  private static final int HELD_BY = 8;
  private static final int LAST_MOVE = HELD_BY + HOLDERS;
  private static final int JOINT_SHORTS = LAST_MOVE + 1;

  /** A gate's shorts: its flags, and for each term its kind and what it points at. */
  private static final int GATE_SHORTS = 1 + 2 * BLOCKING_TERMS;

  /** How many indices a holder's or a term's place has room for, beside its kind. */
  private static final int INDEX_BITS = 12;

  private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;

  // A joint's flags, and a sensor's.
  private static final int PRESENT = 1;
  private static final int CONNECTED = 2;
  private static final int FRESH = 4;
  private static final int MAGNET_BAD = 8;
  private static final int ENCODER_LOST = 16;
  private static final int OWNER_SHIFT = 5;
  // A joint's state: where it stands, what its homing pushes toward, and why it is faulted.
  private static final int TOWARD_SHIFT = 2;
  private static final int FAULT_SHIFT = 5;
  private static final int TRIPPED = 4;
  // A gate's flags.
  private static final int HOLDS = 2;
  // The loop's flags.
  private static final int ENABLED = 1;
  private static final int MODE_SHIFT = 1;

  // Each enum's values, taken once: asking an enum for them makes a new array every time.
  private static final Moment.Mode[] MODES = Moment.Mode.values();
  private static final JointOwner[] OWNERS = JointOwner.values();
  private static final JointState.Standing[] STANDINGS = JointState.Standing.values();

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
  private final Moves moves = new Moves();
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

  /** Puts one joint's numbers, and whether it and its encoder answer. */
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

  /**
   * Puts what one joint's facts say: where it stands, who asked it, where it is among its places,
   * and the first {@link #HOLDERS} of the things that hold it. Call it after {@link #putJoint}.
   */
  void putJointFacts(
      int joint,
      JointState state,
      String fault,
      JointOwner owner,
      String command,
      String goalName,
      JointPlace at,
      Holder[] heldBy) {
    int s = slot * shortsEach + joint * JOINT_SHORTS;
    shorts[s] |= (short) (owner.ordinal() << OWNER_SHIFT);
    shorts[s + STATE] =
        (short)
            (state.standing().ordinal()
                | state.toward().ordinal() << TOWARD_SHIFT
                | state.fault().ordinal() << FAULT_SHIFT);
    floats[slot * floatsEach + joint * JOINT_FLOATS + 4] = (float) state.secondsAllowed();
    shorts[s + FAULT] = words.numberOf(fault);
    shorts[s + COMMAND] = words.numberOf(command);
    shorts[s + GOAL_NAME] = words.numberOf(goalName);
    shorts[s + WHERE] = (short) at.where().ordinal();
    shorts[s + PLACE] = (short) at.place();
    shorts[s + OTHER] = (short) at.other();
    for (int each = 0; each < HOLDERS && each < heldBy.length; each++) {
      shorts[s + HELD_BY + each] = packed(heldBy[each].kind().ordinal(), heldBy[each].index());
    }
  }

  /** Puts how the last move on one joint ended, by the number it is known by ({@link #move}). */
  void putJointsLastMove(int joint, short move) {
    shorts[slot * shortsEach + joint * JOINT_SHORTS + LAST_MOVE] = move;
  }

  /** The number an outcome of a move is known by, given one if it is new. */
  short move(Moment.LastMove move) {
    return moves.numberOf(move);
  }

  /**
   * A kind and an index in one short, with zero left to mean nothing is there: the kind's number
   * and one, above the index. An index of none ({@code -1}) is kept as the largest there is room
   * for.
   */
  private static short packed(int kind, int index) {
    return (short) ((kind + 1) << INDEX_BITS | (index & INDEX_MASK));
  }

  private static int kindOf(short packed) {
    return (packed >>> INDEX_BITS & 0xF) - 1;
  }

  private static int indexOf(short packed) {
    int index = packed & INDEX_MASK;
    return index == INDEX_MASK ? Identity.NONE : index;
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

  /**
   * Puts one gate: whether it holds, and the first {@link #BLOCKING_TERMS} of the terms that keep
   * it from holding. A term that points into the text logged beside the terms is kept with its
   * text, so the text itself need not be.
   */
  void putGate(int gate, Moment.Held held, GateTerm[] blocking, String[] text) {
    int s = slot * shortsEach + gateShorts + gate * GATE_SHORTS;
    shorts[s] = (short) (PRESENT | (held == Moment.Held.HOLDS ? HOLDS : 0));
    for (int each = 0; each < BLOCKING_TERMS && each < blocking.length; each++) {
      GateTerm term = blocking[each];
      shorts[s + 1 + 2 * each] = (short) (term.kind().ordinal() + 1);
      shorts[s + 2 + 2 * each] =
          hasText(term.kind()) ? words.numberOf(textAt(text, term.index())) : (short) term.index();
    }
  }

  /** Whether a term of this kind points into the text logged beside the terms. */
  private static boolean hasText(GateTerm.Kind kind) {
    return kind == GateTerm.Kind.TEXT || kind == GateTerm.Kind.THREW;
  }

  private static String textAt(String[] text, int index) {
    return index >= 0 && index < text.length ? text[index] : "";
  }

  /** Puts the bindings that fired in this loop: the first {@link #FIRED} of so many. */
  void putFired(String[] fired, int howMany) {
    int s = slot * shortsEach + firedShorts;
    for (int each = 0; each < FIRED && each < howMany; each++) {
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

  // ---- One fact of one loop it holds, read where it is kept and making nothing: what the
  // session's events are found from, every loop. Call these under this object's lock. ----

  private int shortOf(long index, int place) {
    return shorts[(int) (index % capacity) * shortsEach + place];
  }

  private int jointShort(long index, int joint, int place) {
    return shortOf(index, joint * JOINT_SHORTS + place);
  }

  /** How many joints it keeps a place for. */
  int joints() {
    return jointLabels.size();
  }

  /** How many sensors it keeps a place for. */
  int sensors() {
    return sensorLabels.size();
  }

  /** How many gates it has found so far. */
  int gates() {
    return gateNames.size();
  }

  Moment.Enabled enabledIn(long index) {
    boolean enabled = (shortOf(index, loopShort) & ENABLED) != 0;
    return enabled ? Moment.Enabled.ENABLED : Moment.Enabled.DISABLED;
  }

  Moment.Mode modeIn(long index) {
    return MODES[shortOf(index, loopShort) >> MODE_SHIFT];
  }

  /** Whether a loop's record had a joint. */
  boolean hasJoint(long index, int joint) {
    return (jointShort(index, joint, 0) & PRESENT) != 0;
  }

  /** Whether a joint's motor controller answered, fresh or not. */
  boolean jointAnswers(long index, int joint) {
    return (jointShort(index, joint, 0) & CONNECTED) != 0;
  }

  /** How a joint's absolute encoder was doing, as two bits that are equal while it is the same. */
  int encoderBits(long index, int joint) {
    return jointShort(index, joint, 0) & (MAGNET_BAD | ENCODER_LOST);
  }

  JointOwner ownerIn(long index, int joint) {
    return OWNERS[jointShort(index, joint, 0) >> OWNER_SHIFT & 3];
  }

  /**
   * Where a joint stood, with what its homing pushed toward and why it was faulted, as a number.
   */
  int stateBits(long index, int joint) {
    return jointShort(index, joint, STATE);
  }

  JointState.Standing standingIn(long index, int joint) {
    return STANDINGS[stateBits(index, joint) & 3];
  }

  /** The number the name of the command that asked a joint is known by. */
  int commandNumber(long index, int joint) {
    return jointShort(index, joint, COMMAND);
  }

  /** The number the name of what a joint was asked for is known by; zero for no name. */
  int goalNameNumber(long index, int joint) {
    return jointShort(index, joint, GOAL_NAME);
  }

  /** Whether anything held a joint. */
  boolean isHeld(long index, int joint) {
    return jointShort(index, joint, HELD_BY) != 0;
  }

  /** Whether the same things held a joint in two loops, in the same order. */
  boolean sameHolders(long one, long other, int joint) {
    for (int each = 0; each < HOLDERS; each++) {
      if (jointShort(one, joint, HELD_BY + each) != jointShort(other, joint, HELD_BY + each)) {
        return false;
      }
    }
    return true;
  }

  /** The number the outcome of the last move on a joint is known by; zero before any ended. */
  short lastMoveNumber(long index, int joint) {
    return (short) jointShort(index, joint, LAST_MOVE);
  }

  /** The outcome of a move a number stands for. */
  Moment.LastMove moveOf(short number) {
    return moves.moveOf(number);
  }

  /** Whether a loop's record had a sensor. */
  boolean hasSensor(long index, int sensor) {
    return (shortOf(index, sensorShorts + sensor) & PRESENT) != 0;
  }

  boolean sensorAnswers(long index, int sensor) {
    return (shortOf(index, sensorShorts + sensor) & CONNECTED) != 0;
  }

  boolean sensorTripped(long index, int sensor) {
    return (shortOf(index, sensorShorts + sensor) & TRIPPED) != 0;
  }

  /** Whether a loop's record had a gate, by the place it is kept in. */
  boolean hasGate(long index, int gate) {
    return (shortOf(index, gateShorts + gate * GATE_SHORTS) & PRESENT) != 0;
  }

  boolean gateHolds(long index, int gate) {
    return (shortOf(index, gateShorts + gate * GATE_SHORTS) & HOLDS) != 0;
  }

  /** How many bindings fired in a loop, of the {@link #FIRED} it keeps. */
  int firedIn(long index) {
    int fired = 0;
    while (fired < FIRED && shortOf(index, firedShorts + fired) != 0) {
      fired++;
    }
    return fired;
  }

  /** A joint's label, by its place. */
  String jointLabel(int joint) {
    return jointLabels.get(joint);
  }

  /** A sensor's label, by its place. */
  String sensorLabel(int sensor) {
    return sensorLabels.get(sensor);
  }

  /** A gate's name, by its place. */
  String gateName(int gate) {
    return gateNames.get(gate);
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
        gates.add(readGate(gate, g));
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

  private Moment.OfGate readGate(int gate, int g) {
    List<GateTerm> terms = new ArrayList<>();
    List<String> text = new ArrayList<>();
    for (int each = 0; each < BLOCKING_TERMS && shorts[g + 1 + 2 * each] != 0; each++) {
      GateTerm.Kind kind = GateTerm.Kind.values()[shorts[g + 1 + 2 * each] - 1];
      short pointsAt = shorts[g + 2 + 2 * each];
      if (hasText(kind)) {
        terms.add(new GateTerm(kind, text.size()));
        text.add(words.textOf(pointsAt));
      } else {
        terms.add(new GateTerm(kind, pointsAt));
      }
    }
    Moment.Held held = (shorts[g] & HOLDS) != 0 ? Moment.Held.HOLDS : Moment.Held.DOES_NOT_HOLD;
    return new Moment.OfGate(gateNames.get(gate), held, terms, text);
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
    int state = shorts[s + STATE];
    List<Holder> heldBy = new ArrayList<>();
    for (int each = 0; each < HOLDERS && shorts[s + HELD_BY + each] != 0; each++) {
      short holder = shorts[s + HELD_BY + each];
      heldBy.add(new Holder(Holder.Kind.values()[kindOf(holder)], indexOf(holder)));
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
        new JointState(
            JointState.Standing.values()[state & 3],
            JointState.Toward.values()[state >> TOWARD_SHIFT & 7],
            floats[f + 4],
            JointState.Fault.values()[state >> FAULT_SHIFT]),
        words.textOf(shorts[s + FAULT]),
        JointOwner.values()[flags >> OWNER_SHIFT & 3],
        words.textOf(shorts[s + COMMAND]),
        words.textOf(shorts[s + GOAL_NAME]),
        new JointPlace(
            JointPlace.Where.values()[shorts[s + WHERE]], shorts[s + PLACE], shorts[s + OTHER]),
        heldBy,
        moves.moveOf(shorts[s + LAST_MOVE]));
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
