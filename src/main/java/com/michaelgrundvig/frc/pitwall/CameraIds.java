package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.world.robot.CameraConfig;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The rule for the ids Pitwall's World view names a camera's shapes by: its {@link
 * CameraConfig#id()}, in names of at most 64 characters, the longest of them twelve more than the
 * id ({@code "camera.<id>.view"}, {@code "ray.<id>.<tag>.bad"}). A robot program checks its cameras
 * by it as its configuration is built, so a camera whose name is too long is refused there, saying
 * which, and not when the World view first draws it.
 */
public final class CameraIds {
  /** The most characters a camera's id may have. */
  public static final int LONGEST = 52;

  private CameraIds() {}

  /**
   * Why one of these cameras can't be drawn by its id, if one can't: the first whose id is too
   * long, with its name and length.
   *
   * @return the reason, or null if every camera's id fits
   */
  public static @Nullable String whyTooLong(List<CameraConfig> cameras) {
    for (CameraConfig camera : cameras) {
      String id = camera.id();
      if (id.length() <= LONGEST) {
        continue;
      }
      return "camera \""
          + camera.name()
          + "\"'s name is too long as an id (\""
          + id
          + "\", "
          + id.length()
          + " characters; at most "
          + LONGEST
          + "): shorten it";
    }
    return null;
  }
}
