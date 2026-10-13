package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How moves ended, as the session memory has seen them, each kept once and known by a small number,
 * as its texts are ({@link Words}). A move's outcome is a list of records that changes only when a
 * move ends, and every loop until the next one holds the same outcome, so a moment holds its
 * number.
 *
 * <p>It holds at most {@link #MOST} outcomes. Past that a new one is kept as none, so what it holds
 * never grows without bound: at a move every few seconds, that is more than the five minutes the
 * memory keeps.
 *
 * <p>Not thread-safe: the session memory that owns it reads and writes it under its own lock.
 */
final class Moves {
  /** The most outcomes it keeps. */
  static final int MOST = 1024;

  private static final short NONE = 0;

  private final Map<Moment.LastMove, Short> numbers = new HashMap<>();
  private final List<Moment.LastMove> moves = new ArrayList<>();

  Moves() {
    moves.add(Moment.LastMove.NONE);
    numbers.put(Moment.LastMove.NONE, NONE);
  }

  /** The number an outcome is known by, given one if it is new. */
  short numberOf(Moment.LastMove move) {
    Short known = numbers.get(move);
    if (known != null) {
      return known;
    }
    if (moves.size() >= MOST) {
      return NONE;
    }
    short number = (short) moves.size();
    moves.add(move);
    numbers.put(move, number);
    return number;
  }

  /** The outcome a number stands for. */
  Moment.LastMove moveOf(short number) {
    return moves.get(number);
  }
}
