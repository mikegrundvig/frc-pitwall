package com.michaelgrundvig.frc.pitwall.robot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The texts the session memory has seen, each kept once and known by a small number, so that a
 * moment holds numbers only. The robot logs the same few texts loop after loop ("READY", "idle",
 * "at out"), so nearly every lookup finds its text and makes nothing new.
 *
 * <p>It holds at most {@link #MOST} texts, each at most {@link #LONGEST} characters. Past that a
 * new text is kept as {@link #TOO_MANY}, so what it holds never grows without bound.
 *
 * <p>Not thread-safe: the session memory that owns it reads and writes it under its own lock.
 */
final class Words {
  /** The most texts it keeps. */
  static final int MOST = 4096;

  /** The most characters of a text it keeps. */
  static final int LONGEST = 200;

  /** What a text reads as once it holds {@link #MOST}. */
  static final String TOO_MANY = "(more texts than the session memory keeps)";

  private static final short EMPTY = 0;
  private static final short OVERFLOW = 1;

  private final Map<String, Short> numbers = new HashMap<>();
  private final List<String> texts = new ArrayList<>();

  Words() {
    texts.add("");
    texts.add(TOO_MANY);
    numbers.put("", EMPTY);
  }

  /** The number a text is known by, given one if it is new. */
  short numberOf(String text) {
    Short known = numbers.get(text);
    if (known != null) {
      return known;
    }
    if (texts.size() >= MOST) {
      return OVERFLOW;
    }
    String kept = text.length() > LONGEST ? text.substring(0, LONGEST) + "…" : text;
    short number = (short) texts.size();
    texts.add(kept);
    numbers.put(text, number);
    return number;
  }

  /** The text a number stands for. */
  String textOf(short number) {
    return texts.get(number);
  }

  /** How many texts it holds. */
  int size() {
    return texts.size();
  }
}
