package com.michaelgrundvig.frc.pitwall.robot;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The events of the robot's session: the loops in which something a person follows changed, each
 * with what changed, in words ({@link RobotEvent}). They are what the page marks on its timeline
 * and lists beside the joints, so that the order things happened in can be read.
 *
 * <p>The events are found as each loop is taken ({@link EventFinder}), on the thread that takes the
 * loops, and kept in a ring of their own ({@link EventRing}): the last {@link #capacity()} of them,
 * and none from a loop whose loop before the session no longer remembers. In five minutes that is
 * 13 events a second, every second; past that the oldest go first.
 *
 * <p>Safe from any thread.
 */
public final class RobotEvents {
  /** How many events a session keeps unless it is told otherwise. */
  public static final int KEPT = 4096;

  /** Which events to answer with. */
  public enum Shown {
    EVERY_EVENT,
    PROBLEMS_ONLY
  }

  /**
   * Which events to answer with.
   *
   * @param kinds the kinds to answer with; empty for every kind
   * @param about the name of what the events are about: a joint's label, a sensor's, a gate's name,
   *     a gamepad's port; empty for everything
   * @param shown every event, or the problems only
   */
  public record Filter(Set<Kind> kinds, String about, Shown shown) {
    /** Every event. */
    public static final Filter EVERYTHING = new Filter(Set.of(), "", Shown.EVERY_EVENT);

    public Filter {
      kinds = Set.copyOf(kinds);
    }

    /** The events about one thing, by its name. */
    public static Filter about(String name) {
      return new Filter(Set.of(), name, Shown.EVERY_EVENT);
    }

    /** The problems only. */
    public static Filter problems() {
      return new Filter(Set.of(), "", Shown.PROBLEMS_ONLY);
    }

    /** The events of some kinds. */
    public static Filter kinds(Kind... kinds) {
      return new Filter(Set.of(kinds), "", Shown.EVERY_EVENT);
    }
  }

  private final RobotPlan plan;
  private final SightingWords words;
  private final SessionMemory memory;
  private final EventRing ring;

  /**
   * @param words the robot program's own words for what became of a camera's image
   */
  RobotEvents(RobotPlan plan, SightingWords words, SessionMemory memory, int capacity) {
    this.plan = plan;
    this.words = words;
    this.memory = memory;
    ring = new EventRing(capacity);
  }

  /**
   * Finds the events of the loop the memory just took. Call it under the memory's lock, on the
   * thread that takes the loops.
   */
  void findIn(long loop) {
    EventFinder.find(memory, loop, ring);
  }

  // ---- Asking ----

  /** How many events have been found since the session began: one more than the newest's number. */
  public long found() {
    synchronized (memory) {
      return ring.added();
    }
  }

  /**
   * The events in a range of loops, both ends included, in order: the first so many of them.
   *
   * @param most the most events to answer with
   */
  public List<RobotEvent> between(long fromLoop, long toLoop, Filter filter, int most) {
    synchronized (memory) {
      List<RobotEvent> events = new ArrayList<>();
      long number = ring.firstAtOrAfter(Math.max(fromLoop, earliestLoop()));
      for (; number < ring.added() && ring.loopOf(number) <= toLoop; number++) {
        if (events.size() >= most) {
          break;
        }
        matching(number, filter).ifPresent(events::add);
      }
      return events;
    }
  }

  /**
   * The events in a range of times on the robot's clock, in nanoseconds, both ends included, in
   * order: the first so many of them.
   */
  public List<RobotEvent> betweenTimes(long fromNanos, long toNanos, Filter filter, int most) {
    synchronized (memory) {
      long first = firstLoopAtOrAfter(fromNanos);
      long last = firstLoopAtOrAfter(toNanos + 1) - 1;
      return between(first, last, filter, most);
    }
  }

  /** The newest events, in order: the last so many of them. */
  public List<RobotEvent> newest(Filter filter, int most) {
    synchronized (memory) {
      List<RobotEvent> events = new ArrayList<>();
      long oldest = ring.firstAtOrAfter(earliestLoop());
      for (long number = ring.added() - 1; number >= oldest && events.size() < most; number--) {
        matching(number, filter).ifPresent(events::add);
      }
      return events.reversed();
    }
  }

  /** The events numbered from one on, in order: the first so many of them. */
  public List<RobotEvent> since(long number, int most) {
    synchronized (memory) {
      List<RobotEvent> events = new ArrayList<>();
      long from = Math.max(number, ring.firstAtOrAfter(earliestLoop()));
      for (long each = from; each < ring.added() && events.size() < most; each++) {
        events.add(event(each));
      }
      return events;
    }
  }

  /** The latest event in a loop before one given. */
  public Optional<RobotEvent> before(long loop, Filter filter) {
    synchronized (memory) {
      long oldest = ring.firstAtOrAfter(earliestLoop());
      for (long number = ring.firstAtOrAfter(loop) - 1; number >= oldest; number--) {
        Optional<RobotEvent> event = matching(number, filter);
        if (event.isPresent()) {
          return event;
        }
      }
      return Optional.empty();
    }
  }

  /** The first event in a loop after one given. */
  public Optional<RobotEvent> after(long loop, Filter filter) {
    synchronized (memory) {
      long from = ring.firstAtOrAfter(Math.max(loop + 1, earliestLoop()));
      for (long number = from; number < ring.added(); number++) {
        Optional<RobotEvent> event = matching(number, filter);
        if (event.isPresent()) {
          return event;
        }
      }
      return Optional.empty();
    }
  }

  // ---- What it costs ----

  /** How many events it keeps. */
  public int capacity() {
    return ring.capacity();
  }

  /** How many bytes its memory of events takes: taken once, as the session is made. */
  public long bytes() {
    return ring.bytes();
  }

  // ---- From what is kept to an event ----

  /**
   * The events of every loop the memory holds, found again from the start, in order: what the
   * events are by their definition. The events it keeps as the loops arrive are the same, for as
   * long as its ring has room for them.
   */
  List<RobotEvent> foundAgain() {
    synchronized (memory) {
      RobotEvents again = new RobotEvents(plan, words, memory, (int) Math.max(1, countAgain()));
      for (long loop = memory.oldest(); loop < memory.taken(); loop++) {
        again.findIn(loop);
      }
      return again.since(0, Integer.MAX_VALUE);
    }
  }

  /** How many events the loops the memory holds have, found again. */
  private long countAgain() {
    EventRing counted = new EventRing(1);
    for (long loop = memory.oldest(); loop < memory.taken(); loop++) {
      EventFinder.find(memory, loop, counted);
    }
    return counted.added();
  }

  /**
   * The earliest loop whose events it answers with: the one after the oldest the memory holds. An
   * event is what differs from the loop before, so the events of the oldest loop went with the loop
   * before it.
   */
  private long earliestLoop() {
    return memory.oldest() + 1;
  }

  /** An event the ring holds, if it is one the filter asks for. */
  private Optional<RobotEvent> matching(long number, Filter filter) {
    boolean ofAKindAsked = filter.kinds().isEmpty() || filter.kinds().contains(ring.kindOf(number));
    boolean shown =
        filter.shown() == Shown.EVERY_EVENT || ring.severityOf(number) == Severity.PROBLEM;
    if (!ofAKindAsked || !shown) {
      return Optional.empty();
    }
    RobotEvent event = event(number);
    boolean aboutWhatWasAsked = filter.about().isEmpty() || event.isAbout(filter.about());
    return aboutWhatWasAsked ? Optional.of(event) : Optional.empty();
  }

  /** An event the ring holds, of a loop the memory holds, in words. */
  private RobotEvent event(long number) {
    long loop = ring.loopOf(number);
    Kind kind = ring.kindOf(number);
    int about = ring.aboutOf(number);
    Moment moment = memory.at(loop);
    if (moment == null) {
      throw new IllegalStateException("the session memory no longer holds loop " + loop);
    }
    boolean ofAGate = kind == Kind.GATE_HELD || kind == Kind.GATE_FELL;
    String gate = ofAGate ? memory.gateName(about) : "";
    if (EventSentences.isOfTheWorld(kind)) {
      // A recovery says how far the estimate jumped: from where the loop before had it.
      return EventSentences.ofTheWorld(
          words, number, loop, memory.at(loop - 1), moment, kind, about, ring.severityOf(number));
    }
    return EventSentences.event(
        plan, number, loop, moment, kind, about, gate, ring.severityOf(number));
  }

  /**
   * The index of the first loop the memory holds whose time is one given or later; one past the
   * newest when every loop is earlier. The loops are in the order of their times.
   */
  private long firstLoopAtOrAfter(long timestampNanos) {
    long low = memory.oldest();
    long high = memory.taken();
    while (low < high) {
      long middle = (low + high) >>> 1;
      if (memory.timeOf(middle) < timestampNanos) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }
}
