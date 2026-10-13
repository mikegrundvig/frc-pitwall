package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
  private final SightingWords words;
  private final SessionMemory memory;
  private final Extraction extraction;

  /** How long taking the loops has taken, in all and at the most, in nanoseconds. */
  private final AtomicLong takingNanos = new AtomicLong();

  private final AtomicLong slowestNanos = new AtomicLong();

  /** What the view needs of the definition, worked out once. */
  private final RobotPlan plan;

  private final RobotEvents events;

  /** When the first loop taken was, on the robot's clock; read from other threads. */
  private volatile long startedNanos = Long.MIN_VALUE;

  private RobotSession(
      RobotDefinition definition,
      double loopSeconds,
      double rememberedSeconds,
      SightingWords words) {
    this.definition = definition;
    this.words = words;
    plan = RobotPlan.of(definition);
    int capacity = (int) Math.max(1, Math.round(rememberedSeconds / loopSeconds));
    List<String> joints = Parts.jointLabels(definition);
    List<String> sensors = Parts.sensorLabels(definition);
    List<String> cameras = definition.cameras().stream().map(CameraConfig::name).toList();
    memory = new SessionMemory(joints, sensors, cameras, capacity);
    events = new RobotEvents(plan, memory, RobotEvents.KEPT);
    extraction = new Extraction(memory, events, joints, sensors, cameras);
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
    return of(definition, loopSeconds, rememberedSeconds, SightingWords.NONE);
  }

  /**
   * @param loopSeconds how long one of the robot's loops lasts, in seconds
   * @param rememberedSeconds how much of the session to remember, in seconds
   * @param words the robot program's own words for what became of a camera's image, which its log
   *     holds as numbers
   */
  public static RobotSession of(
      RobotDefinition definition,
      double loopSeconds,
      double rememberedSeconds,
      SightingWords words) {
    return new RobotSession(definition, loopSeconds, rememberedSeconds, words);
  }

  /** The robot program's own words for what became of a camera's image. */
  public SightingWords words() {
    return words;
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
    if (startedNanos == Long.MIN_VALUE) {
      startedNanos = record.timestampNanos();
    }
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

  /**
   * When the loop with an index was, on the robot's clock, in nanoseconds; empty when it holds no
   * such loop.
   */
  public OptionalLong timeOf(long index) {
    synchronized (memory) {
      boolean held = index >= memory.oldest() && index < memory.taken();
      return held ? OptionalLong.of(memory.timeOf(index)) : OptionalLong.empty();
    }
  }

  /** The newest loop, or empty before the first. */
  public Optional<Moment> newest() {
    return moment(memory.taken() - 1);
  }

  /** The index of the loop whose time is nearest a time on the robot's clock, or -1 with none. */
  public long nearest(long timestampNanos) {
    return memory.nearest(timestampNanos);
  }

  /** The robot as the loop with an index had it, or empty when it holds no such loop. */
  public Optional<RobotView> view(long index) {
    return moment(index).map(moment -> RobotView.of(plan, moment));
  }

  /**
   * What changed in the loop with an index, compared with the loop before it, among the rows the
   * view shows; empty when the session holds no such loop, or not the loop before it.
   */
  public Optional<Changed> changed(long index) {
    Optional<Moment> before = moment(index - 1);
    Optional<Moment> now = moment(index);
    if (before.isEmpty() || now.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Changed.between(plan, before.get(), now.get()));
  }

  /**
   * The latest firing of a binding at or before a loop, and no more than about a second before it:
   * what the page keeps showing after the press, since the firing itself lasts one loop.
   */
  public Optional<Moment> lastFiring(long index) {
    long fired = memory.lastFiring(index, FIRED_SHOWN_LOOPS);
    return fired < 0 ? Optional.empty() : moment(fired);
  }

  /**
   * The session's events: the loops in which something changed, each with what changed in words.
   */
  public RobotEvents events() {
    return events;
  }

  /**
   * When the session began: the time of the first loop it took, on the robot's clock, in
   * nanoseconds. Empty before the first.
   */
  public OptionalLong startedNanos() {
    long started = startedNanos;
    return started == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(started);
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
