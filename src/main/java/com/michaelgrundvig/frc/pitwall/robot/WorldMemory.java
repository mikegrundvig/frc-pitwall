package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Quaternion;
import org.wpilib.math.geometry.Rotation3d;

/**
 * The part of the session memory that holds where the robot was: for each loop the estimated pose,
 * how sure it was and what it rested on, the true pose in the simulator, the wheels, and each
 * camera's tags of that loop with what became of them. The World view is drawn from it.
 *
 * <p>Like the rest of the session memory it is taken once and never grows: plain numbers in two
 * arrays, a loop in the same place as in the memory that owns it. A camera's tags past the first
 * {@link #SIGHTINGS} of a loop are counted and not kept.
 *
 * <p>Not thread-safe: the session memory that owns it reads and writes it under its own lock.
 */
final class WorldMemory {
  /** How many of a camera's tags in one loop it keeps. */
  static final int SIGHTINGS = 4;

  /** How many swerve modules it keeps: a swerve drivetrain has four. */
  static final int MODULES = 4;

  /**
   * How long a tag a camera measured still counts as seen: a camera hands over an image every loop
   * or two, and a sight line that came and went with each would flicker.
   */
  static final long HELD_NANOS = 300_000_000L;

  /** The most loops looked back over for tags still held, however short the loops are. */
  private static final int MOST_LOOPS_HELD = 64;

  /** How many numbers a pose in 3D is: x, y, z, and its rotation's w, x, y, z. */
  static final int POSE_NUMBERS = 7;

  /** How many numbers a spread is: xx, xy, yy, and the heading's variance. */
  static final int SPREAD_NUMBERS = 4;

  // A loop's floats: the estimate, the truth, the modules, and then each camera's sightings.
  private static final int POSE = 0;
  private static final int SPREAD = POSE + POSE_NUMBERS;
  private static final int VISION_AGE = SPREAD + SPREAD_NUMBERS;
  private static final int ACCEPTED = VISION_AGE + 1;
  private static final int REJECTED = ACCEPTED + 1;
  private static final int RECOVERIES = REJECTED + 1;
  private static final int PLACEMENTS = RECOVERIES + 1;
  private static final int TRUTH = PLACEMENTS + 1;
  private static final int MODULE_FLOATS = TRUTH + POSE_NUMBERS;
  private static final int CAMERA_FLOATS = MODULE_FLOATS + 2 * MODULES;

  // A loop's shorts: its flags with what the estimate rests on, why the robot was placed, how many
  // modules it has, and then each camera's sightings.
  private static final int FLAGS = 0;
  private static final int PLACED_BECAUSE = 1;
  private static final int MODULES_KEPT = 2;
  private static final int CAMERA_SHORTS = 3;

  // A camera's shorts: how many tags were dropped, then three for each sighting: the tag's number,
  // its flags, and its verdict with its solver.
  private static final int SIGHTING_SHORTS = 3;
  private static final int CAMERA_SHORTS_EACH = 1 + SIGHTINGS * SIGHTING_SHORTS;
  private static final int CAMERA_FLOATS_EACH = SIGHTINGS * Sighting.NUMBERS;

  // The loop's flags.
  private static final int HAS_POSE = 1;
  private static final int HAS_SPREAD = 2;
  private static final int HAS_TALLY = 4;
  private static final int HAS_TRUTH = 8;
  private static final int RESTS_SHIFT = 4;

  // A sighting's flags: that it is there, its fate, and which of the loop's images saw it.
  private static final int PRESENT = 1;
  private static final int FATE_SHIFT = 1;
  private static final int FATE_MASK = 7;
  private static final int IMAGE_SHIFT = 4;
  private static final int SOLVER_SHIFT = 8;
  private static final int VERDICT_MASK = 0xFF;

  private static final Moment.Rests[] RESTS = Moment.Rests.values();
  private static final Sighting.Fate[] FATES = Sighting.Fate.values();

  private final List<String> cameraNames;
  private final int floatsEach;
  private final int shortsEach;
  private final float[] floats;
  private final short[] shorts;

  /**
   * @param cameraNames the robot's cameras, in its definition's order
   * @param capacity how many loops it keeps
   */
  WorldMemory(List<String> cameraNames, int capacity) {
    this.cameraNames = List.copyOf(cameraNames);
    floatsEach = CAMERA_FLOATS + cameraNames.size() * CAMERA_FLOATS_EACH;
    shortsEach = CAMERA_SHORTS + cameraNames.size() * CAMERA_SHORTS_EACH;
    floats = new float[capacity * floatsEach];
    shorts = new short[capacity * shortsEach];
  }

  /** How many bytes one loop takes. */
  int bytesPerMoment() {
    return floatsEach * Float.BYTES + shortsEach * Short.BYTES;
  }

  /** How many cameras it keeps a place for. */
  int cameras() {
    return cameraNames.size();
  }

  /** A camera's name, by its place. */
  String cameraName(int camera) {
    return cameraNames.get(camera);
  }

  // ---- Writing one loop, in the slot the memory that owns it opened ----

  /** Starts a loop in a slot, with everything in it absent. */
  void open(int slot) {
    Arrays.fill(shorts, slot * shortsEach, (slot + 1) * shortsEach, (short) 0);
  }

  /** Puts the estimated pose: its {@link #POSE_NUMBERS} numbers. */
  void putPose(int slot, double[] numbers) {
    putNumbers(slot, POSE, numbers, POSE_NUMBERS);
    shorts[slot * shortsEach + FLAGS] |= HAS_POSE;
  }

  /** Puts how sure the estimate is: its {@link #SPREAD_NUMBERS} numbers. */
  void putSpread(int slot, double[] numbers) {
    putNumbers(slot, SPREAD, numbers, SPREAD_NUMBERS);
    shorts[slot * shortsEach + FLAGS] |= HAS_SPREAD;
  }

  /** Puts what the estimate rests on. */
  void putRests(int slot, Moment.Rests rests) {
    shorts[slot * shortsEach + FLAGS] |= (short) (rests.ordinal() << RESTS_SHIFT);
  }

  /** Puts how the cameras' measurements have fared. */
  void putTally(int slot, long accepted, long rejected, double visionAgeSeconds) {
    int f = slot * floatsEach;
    floats[f + ACCEPTED] = accepted;
    floats[f + REJECTED] = rejected;
    floats[f + VISION_AGE] = (float) visionAgeSeconds;
    shorts[slot * shortsEach + FLAGS] |= HAS_TALLY;
  }

  /**
   * Puts how often the estimate has recovered and the robot has been placed, and why it was last.
   *
   * @param recoveries how often it has recovered; -1 where the localizer does not say
   * @param placedBecause the number the reason is known by among the memory's texts
   */
  void putCounts(int slot, long recoveries, long placements, short placedBecause) {
    floats[slot * floatsEach + RECOVERIES] = recoveries;
    floats[slot * floatsEach + PLACEMENTS] = placements;
    shorts[slot * shortsEach + PLACED_BECAUSE] = placedBecause;
  }

  /** Puts where the robot really is, in the simulator: its {@link #POSE_NUMBERS} numbers. */
  void putTruth(int slot, double[] numbers) {
    putNumbers(slot, TRUTH, numbers, POSE_NUMBERS);
    shorts[slot * shortsEach + FLAGS] |= HAS_TRUTH;
  }

  /**
   * Puts the swerve modules, the first {@link #MODULES} of them: for each its speed and its angle.
   *
   * @param howMany how many modules the numbers hold
   */
  void putModules(int slot, double[] numbers, int howMany) {
    int kept = Math.min(howMany, MODULES);
    putNumbers(slot, MODULE_FLOATS, numbers, 2 * kept);
    shorts[slot * shortsEach + MODULES_KEPT] = (short) kept;
  }

  /**
   * Puts one camera's tags of this loop, the first {@link #SIGHTINGS} of them, and counts the rest.
   *
   * @param facts the tags, with what became of each
   * @param numbers {@link Sighting#NUMBERS} numbers for each, in their order
   * @param howMany how many tags the loop has
   */
  void putSightings(int slot, int camera, Sighting[] facts, double[] numbers, int howMany) {
    int s = slot * shortsEach + CAMERA_SHORTS + camera * CAMERA_SHORTS_EACH;
    int f = slot * floatsEach + CAMERA_FLOATS + camera * CAMERA_FLOATS_EACH;
    int kept = Math.min(howMany, SIGHTINGS);
    shorts[s] = (short) (howMany - kept);
    for (int each = 0; each < kept; each++) {
      Sighting fact = facts[each];
      int at = s + 1 + each * SIGHTING_SHORTS;
      shorts[at] = (short) fact.tag();
      shorts[at + 1] =
          (short) (PRESENT | fact.fate().ordinal() << FATE_SHIFT | fact.image() << IMAGE_SHIFT);
      shorts[at + 2] =
          (short) ((fact.verdict() & VERDICT_MASK) | (fact.solver() + 1) << SOLVER_SHIFT);
      for (int number = 0; number < Sighting.NUMBERS; number++) {
        floats[f + each * Sighting.NUMBERS + number] =
            (float) numbers[each * Sighting.NUMBERS + number];
      }
    }
  }

  private void putNumbers(int slot, int place, double[] numbers, int howMany) {
    int f = slot * floatsEach + place;
    for (int each = 0; each < howMany; each++) {
      floats[f + each] = (float) numbers[each];
    }
  }

  // ---- One fact of one loop, read where it is kept and making nothing: what the session's events
  // are found from, every loop ----

  boolean hasPose(int slot) {
    return (shorts[slot * shortsEach + FLAGS] & HAS_POSE) != 0;
  }

  Moment.Rests restsIn(int slot) {
    return RESTS[shorts[slot * shortsEach + FLAGS] >> RESTS_SHIFT];
  }

  /** How often the estimate had recovered; -1 where the localizer does not say. */
  int recoveriesIn(int slot) {
    return (int) floats[slot * floatsEach + RECOVERIES];
  }

  int placementsIn(int slot) {
    return (int) floats[slot * floatsEach + PLACEMENTS];
  }

  /** How many of a camera's tags of a loop it keeps. */
  int sightingsIn(int slot, int camera) {
    int s = slot * shortsEach + CAMERA_SHORTS + camera * CAMERA_SHORTS_EACH;
    int kept = 0;
    while (kept < SIGHTINGS && (shorts[s + 1 + kept * SIGHTING_SHORTS + 1] & PRESENT) != 0) {
      kept++;
    }
    return kept;
  }

  /**
   * Which of a camera's tags of a loop was the first refused: by the estimator, or before its image
   * became a measurement. -1 when none was.
   */
  int firstRefusedIn(int slot, int camera) {
    int s = slot * shortsEach + CAMERA_SHORTS + camera * CAMERA_SHORTS_EACH;
    for (int each = 0; each < SIGHTINGS; each++) {
      int flags = shorts[s + 1 + each * SIGHTING_SHORTS + 1];
      if ((flags & PRESENT) == 0) {
        return -1;
      }
      Sighting.Fate fate = FATES[flags >> FATE_SHIFT & FATE_MASK];
      if (fate == Sighting.Fate.REJECTED || fate == Sighting.Fate.LEFT_OUT) {
        return each;
      }
    }
    return -1;
  }

  // ---- Reading one loop whole ----

  /**
   * Where the robot was in a loop.
   *
   * @param index the loop, which the memory holds
   * @param memory the memory that owns this, for the loops it holds and when each was
   */
  Moment.OfWorld read(long index, SessionMemory memory) {
    int slot = memory.slotOf(index);
    int f = slot * floatsEach;
    int flags = shorts[slot * shortsEach + FLAGS];
    Optional<Moment.OfEstimate> estimate = Optional.empty();
    if ((flags & HAS_POSE) != 0) {
      estimate = Optional.of(readEstimate(slot, flags, memory));
    }
    Optional<Pose3d> truth = Optional.empty();
    if ((flags & HAS_TRUTH) != 0) {
      truth = Optional.of(readPose(f + TRUTH));
    }
    List<Moment.OfModule> modules = new ArrayList<>();
    for (int module = 0; module < shorts[slot * shortsEach + MODULES_KEPT]; module++) {
      int at = f + MODULE_FLOATS + 2 * module;
      modules.add(new Moment.OfModule(floats[at], floats[at + 1]));
    }
    List<Moment.OfCamera> cameras = new ArrayList<>();
    for (int camera = 0; camera < cameraNames.size(); camera++) {
      cameras.add(readCamera(index, camera, memory));
    }
    return new Moment.OfWorld(estimate, truth, modules, cameras);
  }

  private Moment.OfEstimate readEstimate(int slot, int flags, SessionMemory memory) {
    int f = slot * floatsEach;
    Optional<Moment.Spread> spread = Optional.empty();
    if ((flags & HAS_SPREAD) != 0) {
      int at = f + SPREAD;
      spread =
          Optional.of(
              new Moment.Spread(floats[at], floats[at + 1], floats[at + 2], floats[at + 3]));
    }
    Optional<Moment.Tally> tally = Optional.empty();
    if ((flags & HAS_TALLY) != 0) {
      tally =
          Optional.of(
              new Moment.Tally(
                  (int) floats[f + ACCEPTED], (int) floats[f + REJECTED], floats[f + VISION_AGE]));
    }
    return new Moment.OfEstimate(
        readPose(f + POSE),
        spread,
        RESTS[flags >> RESTS_SHIFT],
        tally,
        (int) floats[f + RECOVERIES],
        (int) floats[f + PLACEMENTS],
        memory.textOf(shorts[slot * shortsEach + PLACED_BECAUSE]));
  }

  private Pose3d readPose(int at) {
    Quaternion turned =
        new Quaternion(floats[at + 3], floats[at + 4], floats[at + 5], floats[at + 6]);
    return new Pose3d(floats[at], floats[at + 1], floats[at + 2], new Rotation3d(turned));
  }

  /**
   * One camera's tags as of a loop, newest first: every tag of the loop's own images, and each tag
   * it measured in the {@link #HELD_NANOS} before and has not measured since.
   */
  private Moment.OfCamera readCamera(long index, int camera, SessionMemory memory) {
    List<Moment.Sighted> sightings = new ArrayList<>();
    long now = memory.timeOf(index);
    long earliest = Math.max(memory.oldest(), index - MOST_LOOPS_HELD);
    for (long loop = index; loop >= earliest && now - memory.timeOf(loop) <= HELD_NANOS; loop--) {
      int slot = memory.slotOf(loop);
      for (int each = sightingsIn(slot, camera) - 1; each >= 0; each--) {
        Moment.Sighted sighted = readSighting(slot, camera, each, (int) (index - loop));
        boolean thisLoops = loop == index;
        if (thisLoops || (sighted.measured() && !measuresTag(sightings, sighted.tag()))) {
          sightings.add(sighted);
        }
      }
    }
    int dropped =
        shorts[memory.slotOf(index) * shortsEach + CAMERA_SHORTS + camera * CAMERA_SHORTS_EACH];
    return new Moment.OfCamera(cameraNames.get(camera), sightings, dropped);
  }

  private static boolean measuresTag(List<Moment.Sighted> sightings, int tag) {
    for (Moment.Sighted sighted : sightings) {
      if (sighted.tag() == tag && sighted.measured()) {
        return true;
      }
    }
    return false;
  }

  private Moment.Sighted readSighting(int slot, int camera, int which, int loopsAgo) {
    int s =
        slot * shortsEach
            + CAMERA_SHORTS
            + camera * CAMERA_SHORTS_EACH
            + 1
            + which * SIGHTING_SHORTS;
    int f =
        slot * floatsEach + CAMERA_FLOATS + camera * CAMERA_FLOATS_EACH + which * Sighting.NUMBERS;
    int flags = shorts[s + 1];
    int why = shorts[s + 2];
    return new Moment.Sighted(
        shorts[s],
        FATES[flags >> FATE_SHIFT & FATE_MASK],
        why & VERDICT_MASK,
        (why >> SOLVER_SHIFT & 0x7F) - 1,
        flags >> IMAGE_SHIFT & 0xF,
        loopsAgo,
        floats[f + Sighting.VALUE],
        floats[f + Sighting.THRESHOLD],
        floats[f + Sighting.INNOVATION_METERS],
        floats[f + Sighting.CHI_SQUARED],
        floats[f + Sighting.MEASURED_X],
        floats[f + Sighting.MEASURED_Y],
        floats[f + Sighting.MEASURED_HEADING]);
  }
}
