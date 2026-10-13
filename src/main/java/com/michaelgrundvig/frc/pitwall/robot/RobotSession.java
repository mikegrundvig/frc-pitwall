package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The robot's session, as Pitwall remembers it: the robot's definition, and the last few minutes of
 * its log, one {@link Moment} for each loop. The page's Robot view is drawn from it.
 *
 * <p>A robot program makes one before its log starts ({@link #of}), hands AdvantageKit a receiver
 * for it ({@code AdvantageKitLog.receiver(session)}, before {@code Logger.start()}), and hands it
 * to Pitwall as Pitwall starts ({@code Pitwall.start(name, session)}).
 *
 * <p>Its memory is taken once, as it is made, and never grows: {@link #capacity()} loops of {@link
 * #bytesPerMoment()} bytes each. When it is full, each new loop replaces the oldest.
 *
 * <p>A loop is known by its index, which counts from 0 at the first loop taken. Safe from any
 * thread: one thread takes the loops ({@link #take}), and any reads them.
 */
public final class RobotSession {
  /** How long a robot's loop lasts unless it is told otherwise, in seconds: 50 a second. */
  public static final double LOOP_SECONDS = 0.02;

  /** How much of the session is remembered unless it is told otherwise, in seconds: 5 minutes. */
  public static final double REMEMBERED_SECONDS = 300;

  /**
   * How long a binding that fired stays on the page, in loops: about a second, so a person sees a
   * press that lasted one loop.
   */
  static final int FIRED_SHOWN_LOOPS = 50;

  private final RobotDefinition definition;
  private final SessionMemory memory;
  private final Extraction extraction;

  /** How long taking the loops has taken, in all and at the most, in nanoseconds. */
  private final AtomicLong takingNanos = new AtomicLong();

  private final AtomicLong slowestNanos = new AtomicLong();

  private RobotSession(RobotDefinition definition, double loopSeconds, double rememberedSeconds) {
    this.definition = definition;
    int capacity = (int) Math.max(1, Math.round(rememberedSeconds / loopSeconds));
    List<String> joints = Parts.jointLabels(definition);
    List<String> sensors = Parts.sensorLabels(definition);
    memory = new SessionMemory(joints, sensors, capacity);
    extraction = new Extraction(memory, joints, sensors);
  }

  /** A session of a robot whose loop runs 50 times a second, remembering five minutes. */
  public static RobotSession of(RobotDefinition definition) {
    return of(definition, LOOP_SECONDS, REMEMBERED_SECONDS);
  }

  /**
   * @param loopSeconds how long one of the robot's loops lasts, in seconds
   * @param rememberedSeconds how much of the session to remember, in seconds
   */
  public static RobotSession of(
      RobotDefinition definition, double loopSeconds, double rememberedSeconds) {
    return new RobotSession(definition, loopSeconds, rememberedSeconds);
  }

  /** The robot's definition. */
  public RobotDefinition definition() {
    return definition;
  }

  /**
   * Takes one loop's record. Call it from one thread, off the robot loop: AdvantageKit's receiver
   * thread does.
   */
  public void take(LoopRecord record) {
    long started = System.nanoTime();
    extraction.take(record);
    long took = System.nanoTime() - started;
    takingNanos.addAndGet(took);
    if (took > slowestNanos.get()) {
      slowestNanos.set(took);
    }
  }

  // ---- What it remembers ----

  /** How many loops it has taken since it was made: one more than the newest loop's index. */
  public long taken() {
    return memory.taken();
  }

  /** The index of the oldest loop it still holds; equal to {@link #taken()} while it holds none. */
  public long oldest() {
    return memory.oldest();
  }

  /** The loop with an index, or empty when it holds none: not taken yet, or replaced since. */
  public Optional<Moment> moment(long index) {
    return Optional.ofNullable(memory.at(index));
  }

  /** The newest loop, or empty before the first. */
  public Optional<Moment> newest() {
    return moment(memory.taken() - 1);
  }

  /** The index of the loop whose time is nearest a time on the robot's clock, or -1 with none. */
  public long nearest(long timestampNanos) {
    return memory.nearest(timestampNanos);
  }

  /**
   * The latest firing of a binding at or before a loop, and no more than about a second before it:
   * what the page keeps showing after the press, since the firing itself lasts one loop.
   */
  public Optional<Moment> lastFiring(long index) {
    long fired = memory.lastFiring(index, FIRED_SHOWN_LOOPS);
    return fired < 0 ? Optional.empty() : moment(fired);
  }

  // ---- What it costs ----

  /** How many loops it remembers. */
  public int capacity() {
    return memory.capacity();
  }

  /** How many bytes one loop takes in its memory. */
  public int bytesPerMoment() {
    return memory.bytesPerMoment();
  }

  /** How many bytes its memory of loops takes: taken once, as it is made. */
  public long bytes() {
    return (long) memory.bytesPerMoment() * memory.capacity();
  }

  /** How long taking a loop takes on average, in microseconds; 0 before the first. */
  public double meanTakeMicros() {
    long loops = memory.taken();
    return loops == 0 ? 0 : takingNanos.get() / 1e3 / loops;
  }

  /** The longest taking a loop has taken, in microseconds. */
  public double slowestTakeMicros() {
    return slowestNanos.get() / 1e3;
  }

  /** How many gates the record has that the memory has no place for. */
  public int gatesNotKept() {
    return extraction.gatesNotKept();
  }

  /** How many different texts it has kept, of the {@link #mostTexts()} it keeps. */
  public int texts() {
    return memory.texts();
  }

  /** The most different texts it keeps. */
  public int mostTexts() {
    return Words.MOST;
  }
}
