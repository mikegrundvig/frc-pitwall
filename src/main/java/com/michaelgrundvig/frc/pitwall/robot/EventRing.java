package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;

/**
 * The session's events as they are kept: each as two numbers, in a ring of fixed size. An event is
 * the loop it happened in, what kind it is, which joint, sensor, gate or binding it is about (by
 * its place in the session memory), and whether it is a problem. Its words are made only when it is
 * asked for, from the loop's moment, so finding an event makes nothing new.
 *
 * <p>Its memory is taken once, as it is made: 12 bytes an event. When it is full, each new event
 * replaces the oldest. An event is known by its number, which counts from 0 and never repeats, and
 * the events are in the order of their loops.
 *
 * <p>Not thread-safe: the session reads and writes it under its memory's lock.
 */
final class EventRing {
  private static final int KIND_BITS = 8;
  private static final int KIND_MASK = (1 << KIND_BITS) - 1;
  private static final int ABOUT_MASK = 0xFFFF;
  private static final int PROBLEM = 1 << 30;

  private static final Kind[] KINDS = Kind.values();

  private final long[] loops;
  private final int[] whats;

  /** How many events have been added since the start. */
  private long added;

  /**
   * @param capacity how many events it keeps
   */
  EventRing(int capacity) {
    loops = new long[capacity];
    whats = new int[capacity];
  }

  /** Adds an event, over the oldest when the ring is full. */
  void add(long loop, Kind kind, int about, Severity severity) {
    int place = (int) (added % loops.length);
    loops[place] = loop;
    whats[place] =
        kind.ordinal()
            | (about & ABOUT_MASK) << KIND_BITS
            | (severity == Severity.PROBLEM ? PROBLEM : 0);
    added++;
  }

  /** How many events have been added since the start: one more than the newest event's number. */
  long added() {
    return added;
  }

  /** The number of the oldest event it still holds; equal to {@link #added} while it holds none. */
  long oldest() {
    return Math.max(0, added - loops.length);
  }

  long loopOf(long number) {
    return loops[(int) (number % loops.length)];
  }

  Kind kindOf(long number) {
    return KINDS[whats[(int) (number % loops.length)] & KIND_MASK];
  }

  /** Which joint, sensor, gate or binding an event is about, by its place in the session memory. */
  int aboutOf(long number) {
    return whats[(int) (number % loops.length)] >>> KIND_BITS & ABOUT_MASK;
  }

  Severity severityOf(long number) {
    boolean problem = (whats[(int) (number % loops.length)] & PROBLEM) != 0;
    return problem ? Severity.PROBLEM : Severity.ROUTINE;
  }

  /**
   * The number of the first event it holds that happened in a loop or after it; {@link #added} when
   * every event it holds is earlier. The events are in the order of their loops, so it is found by
   * halving.
   */
  long firstAtOrAfter(long loop) {
    long low = oldest();
    long high = added;
    while (low < high) {
      long middle = (low + high) >>> 1;
      if (loopOf(middle) < loop) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  /** How many events it keeps. */
  int capacity() {
    return loops.length;
  }

  /** How many bytes its memory takes: taken once, as it is made. */
  long bytes() {
    return (long) loops.length * (Long.BYTES + Integer.BYTES);
  }
}
