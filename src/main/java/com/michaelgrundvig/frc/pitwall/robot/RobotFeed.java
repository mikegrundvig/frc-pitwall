package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.push.Feed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The Robot view, live: the newest moment of the session, sent to the pages that show the view, at
 * most {@link #PER_SECOND} times a second, and each time only what changed since the last.
 *
 * <p>While no page shows the view the robot loop is not asked anything. While one does, the loop
 * reads the newest moment, makes its view and compares it with what was sent: the work of a few
 * dozen small objects, {@link #PER_SECOND} times a second.
 */
public final class RobotFeed implements Feed {
  /** The view a page names to be sent the robot's joints. */
  public static final String VIEW = "robot";

  /** The most patches a second. */
  public static final int PER_SECOND = 20;

  private static final long PATCH_NANOS = 1_000_000_000L / PER_SECOND;

  private static final List<String> AT = List.of("robotView", "live");

  private final RobotSession session;
  private final LongSupplier clock;

  /** Everything as last sent. It is replaced, never changed, so what was handed out stays as is. */
  private Map<String, Object> sent = Map.of();

  /** The index of the loop last sent, so a paused robot sends nothing. */
  private long sentIndex = -1;

  private long nextPatchNanos = Long.MIN_VALUE;

  /**
   * @param clock the robot's clock, in nanoseconds: patches are timed on it
   */
  public RobotFeed(RobotSession session, LongSupplier clock) {
    this.session = session;
    this.clock = clock;
  }

  @Override
  public String view() {
    return VIEW;
  }

  @Override
  public List<String> at() {
    return AT;
  }

  @Override
  public Map<String, @Nullable Object> patch() {
    long now = clock.getAsLong();
    long newest = session.taken() - 1;
    if (now < nextPatchNanos || newest == sentIndex) {
      return Map.of();
    }
    Map<String, Object> before = sent;
    if (!send(newest)) {
      return Map.of();
    }
    nextPatchNanos = now + PATCH_NANOS;
    return changes(before, sent);
  }

  /**
   * Everything as last sent. A page can start showing the view in a loop in which no patch was
   * asked for yet, the first page to show it for one: it is then sent the newest loop whole, and
   * that is remembered as sent, so the patch that follows holds only what changed since.
   */
  @Override
  public Map<String, Object> sent() {
    if (sentIndex < 0) {
      send(session.taken() - 1);
    }
    return sent;
  }

  /**
   * Remembers a loop's view as what was sent.
   *
   * @return whether the session holds the loop
   */
  private boolean send(long index) {
    Optional<RobotView> view = session.view(index);
    if (view.isEmpty()) {
      return false;
    }
    sent = RobotWire.wire(view.get(), index, session.lastFiring(index));
    sentIndex = index;
    return true;
  }

  /**
   * What differs between two JSON objects, as a JSON merge patch (RFC 7396): a key that is new or
   * whose value changed with its value now, an object's changes within it, and a key that is gone
   * as null. Lists, texts and numbers are compared whole.
   */
  static Map<String, @Nullable Object> changes(
      Map<String, Object> before, Map<String, Object> now) {
    Map<String, @Nullable Object> changed = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : now.entrySet()) {
      Object was = before.get(entry.getKey());
      Object is = entry.getValue();
      if (was instanceof Map<?, ?> wasMap && is instanceof Map<?, ?> isMap) {
        Map<String, @Nullable Object> within = changes(object(wasMap), object(isMap));
        if (!within.isEmpty()) {
          changed.put(entry.getKey(), within);
        }
      } else if (!is.equals(was)) {
        changed.put(entry.getKey(), is);
      }
    }
    for (String key : before.keySet()) {
      if (!now.containsKey(key)) {
        changed.put(key, null);
      }
    }
    return changed;
  }

  @SuppressWarnings("unchecked") // every object of the view's wire is keyed by texts
  private static Map<String, Object> object(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
