package com.michaelgrundvig.frc.pitwall.robot;

import java.util.function.Consumer;

/**
 * One loop's record, as the robot program logged it: every key with its value at the end of that
 * loop. AdvantageKit's table is behind it on a robot; a test hands over a map.
 *
 * <p>Each read names a key as the log names it, from its root with the slash first ({@link
 * LogKeys}), and what to answer when the record has no such key, or holds a value of another type
 * there. A read makes nothing new: an array comes back as the record holds it, and must not be
 * changed or kept.
 */
public interface LoopRecord {
  /** No texts: what a read of a list of texts answers for a key the record does not have. */
  String[] NO_TEXTS = new String[0];

  /** No numbers: what a read of numbers answers for a key the record does not have. */
  float[] NO_FLOATS = new float[0];

  /** No whole numbers: what a read of whole numbers answers for a key the record does not have. */
  long[] NO_WHOLES = new long[0];

  /**
   * When the loop was, on the robot's clock, in nanoseconds: what AdvantageKit stamps the loop's
   * record with, which is WPILib's {@code RobotController.getTime()}.
   */
  long timestampNanos();

  boolean flag(String key, boolean otherwise);

  double number(String key, double otherwise);

  long whole(String key, long otherwise);

  String text(String key, String otherwise);

  /** A list of texts, or {@link #NO_TEXTS}. */
  String[] texts(String key);

  /** A list of numbers, or {@link #NO_FLOATS}. */
  float[] floats(String key);

  /** A list of whole numbers, or {@link #NO_WHOLES}. */
  long[] wholes(String key);

  /** Whether the record has a key. */
  boolean has(String key);

  /**
   * How many keys the record has. A record with more keys than the last loop's may have something
   * new in it, so the keys are walked only then.
   */
  int keyCount();

  /** Hands over every key the record has, one at a time. */
  void forEachKey(Consumer<String> each);
}
