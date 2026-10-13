package com.michaelgrundvig.frc.pitwall.scene;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One line of a layer's legend: what a kind of mark in the layer means, in a few words, with the
 * style the mark is drawn in. The page draws the sample from that same style, in its own colors, so
 * the legend can never show another color or stroke than the drawing does.
 *
 * @param mark what kind of mark it is
 * @param style how the layer draws it
 * @param says what it means: "A tag's measurement the estimator accepted"
 */
public record Legend(Mark mark, Style style, String says) {
  /** What kind of mark a legend's line explains, which is how its sample is drawn. */
  public enum Mark {
    /** A line between two things. */
    LINE,
    /** The outline of a shape. */
    OUTLINE,
    /** A filled shape. */
    FILL,
    /** An arrow. */
    ARROW,
    /** A few words drawn in the scene. */
    TEXT
  }

  /** A line's meaning. */
  public static Legend line(Style style, String says) {
    return new Legend(Mark.LINE, style, says);
  }

  /** An outline's meaning. */
  public static Legend outline(Style style, String says) {
    return new Legend(Mark.OUTLINE, style, says);
  }

  /** A filled shape's meaning. */
  public static Legend fill(Style style, String says) {
    return new Legend(Mark.FILL, style, says);
  }

  /** An arrow's meaning. */
  public static Legend arrow(Style style, String says) {
    return new Legend(Mark.ARROW, style, says);
  }

  /** What words drawn in the scene are. */
  public static Legend text(Style style, String says) {
    return new Legend(Mark.TEXT, style, says);
  }

  /** As the page reads it: {@code {"mark": "line", "style": "bad dashed", "text": ...}}. */
  Map<String, Object> wire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("mark", mark.name().toLowerCase(Locale.ROOT));
    wire.put("style", style.wire());
    wire.put("text", says);
    return wire;
  }
}
