package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The Robot view's live feed: the newest loop, at most 20 times a second, only what changed. */
class RobotFeedTest {
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest();
  private final RobotSession session = RobotSession.of(TestRobot.definition());
  private final AtomicLong nanos = new AtomicLong();
  private final RobotFeed feed = new RobotFeed(session, nanos::get);

  private void loop(double pivotDegrees) {
    nanos.addAndGet(20_000_000);
    record.at(nanos.get()).joint("pivot", Math.toRadians(pivotDegrees), 0);
    session.take(record);
  }

  @SuppressWarnings("unchecked") // the wire's objects are keyed by texts
  private static Map<String, Object> in(Map<String, ?> object, String... path) {
    Object found = object;
    for (String key : path) {
      found = Objects.requireNonNull(((Map<String, Object>) found).get(key));
    }
    return (Map<String, Object>) found;
  }

  /** The first patch holds the whole view; the next, only the numbers that moved. */
  @Test
  void aPatchHoldsOnlyWhatChangedSinceTheLast() {
    assertThat(feed.patch()).as("before any loop").isEmpty();
    assertThat(feed.view()).isEqualTo("robot");
    assertThat(feed.at()).containsExactly("robotView", "live");

    loop(10);
    Map<String, Object> first = feed.patch();
    assertThat(first.get("order"))
        .isEqualTo(List.of("pivot", "roller", "elevator", "arm", "shooter", "claw"));
    assertThat(in(first, "joints", "pivot", "needles")).containsEntry("now", 10.0);
    assertThat(in(first, "joints", "pivot", "scale")).containsEntry("upper", 135.0);
    assertThat(feed.sent()).isEqualTo(first);

    for (int more = 0; more < 3; more++) {
      loop(20);
    }
    Map<String, Object> second = feed.patch();
    assertThat(second.keySet()).containsExactlyInAnyOrder("index", "timestampNanos", "joints");
    assertThat(in(second, "joints").keySet()).containsExactly("pivot");
    assertThat(in(second, "joints", "pivot").keySet()).containsExactly("needles", "facts");
    assertThat(in(second, "joints", "pivot", "needles"))
        .containsEntry("now", 20.0)
        .containsEntry("goal", 20.0);
    assertThat(in(feed.sent(), "joints", "pivot", "needles")).containsEntry("now", 20.0);
    assertThat(in(feed.sent(), "joints", "roller", "scale")).containsKey("upper");
  }

  /**
   * A page may start showing the view in a loop in which no patch was asked for: it is sent the
   * newest loop whole all the same, and the patch after it holds only what changed since.
   */
  @Test
  void aPageThatIsFirstToShowTheViewStartsFromTheNewestLoopWhole() {
    assertThat(feed.sent()).as("before any loop").isEmpty();
    loop(10);

    Map<String, Object> whole = feed.sent();
    assertThat(whole.get("order"))
        .isEqualTo(List.of("pivot", "roller", "elevator", "arm", "shooter", "claw"));
    assertThat(in(whole, "joints", "pivot", "needles")).containsEntry("now", 10.0);
    assertThat(feed.patch()).as("nothing has changed since").isEmpty();

    loop(20);
    assertThat(in(feed.patch(), "joints").keySet()).containsExactly("pivot");
  }

  /**
   * No more than 20 patches a second, and none for a loop already sent: a paused robot is quiet.
   */
  @Test
  void itSendsAtMostTwentyTimesASecondAndNothingWhileNoLoopIsNew() {
    loop(10);
    assertThat(feed.patch()).isNotEmpty();

    loop(11);
    assertThat(feed.patch()).as("20 ms after the last patch").isEmpty();
    loop(12);
    loop(13);
    assertThat(feed.patch()).as("60 ms after it").isNotEmpty();

    nanos.addAndGet(1_000_000_000);
    assertThat(feed.patch()).as("a second on with no new loop").isEmpty();
  }

  /** A firing is kept in what is sent for about a second, with when it was. */
  @Test
  void aFiringStaysInWhatIsSentForAboutASecond() {
    record.fired("operator right trigger starts Intake");
    loop(10);
    long firedAt = nanos.get();
    record.fired();
    for (int more = 0; more < 10; more++) {
      loop(10);
    }
    feed.patch();
    assertThat(in(feed.sent(), "fired"))
        .containsEntry("bindings", List.of("operator right trigger starts Intake"))
        .containsEntry("timestampNanos", firedAt);

    for (int more = 0; more < 60; more++) {
      loop(10);
    }
    feed.patch();
    assertThat(in(feed.sent(), "fired")).containsEntry("bindings", List.of());
  }

  /** The changes between two objects are a JSON merge patch: nested, with null for what is gone. */
  @Test
  void changesAreAMergePatch() {
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("kept", 1);
    before.put("gone", 2);
    before.put("inner", Map.of("same", "a", "changes", 1, "leaves", true));
    before.put("list", List.of(1, 2));
    Map<String, Object> now = new LinkedHashMap<>();
    now.put("kept", 1);
    now.put("inner", Map.of("same", "a", "changes", 2));
    now.put("list", List.of(1, 2, 3));
    now.put("added", "new");

    Map<String, Object> changed = RobotFeed.changes(before, now);

    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("changes", 2);
    inner.put("leaves", null);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("inner", inner);
    expected.put("list", List.of(1, 2, 3));
    expected.put("added", "new");
    expected.put("gone", null);
    assertThat(changed).isEqualTo(expected);
    assertThat(RobotFeed.changes(now, now)).isEmpty();
  }
}
