package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvents;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.robot.RobotView;
import com.michaelgrundvig.frc.pitwall.robot.RobotWire;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The robot session's routes ({@code /api/robot...}): how much of it is remembered, the robot as
 * one loop had it, and its events. 404 for a robot program that hands Pitwall no session.
 */
final class SessionRoutes {
  /** The most events one answer holds. */
  private static final int MOST_EVENTS = 500;

  private final Page state;
  private final Responses responses;

  SessionRoutes(Page state, Responses responses) {
    this.state = state;
    this.responses = responses;
  }

  /** The robot's session; 404 for a robot program that handed Pitwall none. */
  private RobotSession session() {
    RobotSession session = state.robotSession();
    if (session == null) {
      throw new Problem(
          404,
          "No robot session",
          "This robot program hands Pitwall no session of its robot, so there is no Robot view");
    }
    return session;
  }

  /** {@code GET /api/robot}: how much of the session is remembered, and what that costs. */
  void summary(HttpExchange exchange) throws IOException {
    RobotSession session = session();
    long taken = session.taken();
    long oldest = session.oldest();
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("robot", session.definition().name());
    shown.put("oldest", oldest);
    shown.put("newest", taken - 1);
    session.moment(oldest).ifPresent(first -> shown.put("oldestNanos", first.timestampNanos()));
    session.newest().ifPresent(last -> shown.put("newestNanos", last.timestampNanos()));
    shown.put("rememberedLoops", session.capacity());
    shown.put("bytesPerLoop", session.bytesPerMoment());
    shown.put("bytes", session.bytes());
    shown.put("meanTakeMicros", session.meanTakeMicros());
    shown.put("slowestTakeMicros", session.slowestTakeMicros());
    shown.put("texts", session.texts());
    shown.put("mostTexts", session.mostTexts());
    shown.put("gatesNotKept", session.gatesNotKept());
    shown.put("events", session.events().found());
    shown.put("rememberedEvents", session.events().capacity());
    shown.put("eventBytes", session.events().bytes());
    session.startedNanos().ifPresent(started -> shown.put("startedNanos", started));
    responses.json(exchange, 200, shown);
  }

  /**
   * {@code GET /api/robot/view}: the robot as one loop of the session had it: the newest, or the
   * loop {@code ?index=} names, or the loop nearest the time {@code ?at=} names. 404 when the
   * session holds no such loop.
   */
  void view(HttpExchange exchange) throws IOException {
    RobotSession session = session();
    Map<String, String> asked = Requests.query(exchange);
    long index = session.taken() - 1;
    if (asked.containsKey("index")) {
      index = Requests.wholeNumber("index", asked.get("index"));
    } else if (asked.containsKey("at")) {
      index = session.nearest(Requests.wholeNumber("at", asked.get("at")));
    }
    long loop = index;
    RobotView view =
        session
            .view(loop)
            .orElseThrow(
                () ->
                    new Problem(
                        404,
                        "No such loop",
                        "The session holds loops "
                            + session.oldest()
                            + " to "
                            + (session.taken() - 1)
                            + ", not "
                            + loop));
    Map<String, Object> wire = RobotWire.wire(view, loop, session.lastFiring(loop));
    session.changed(loop).ifPresent(changed -> wire.put("changed", RobotWire.wire(changed)));
    responses.json(exchange, 200, wire);
  }

  /**
   * {@code GET /api/robot/events}: the session's events, those a range, a count or a loop in the
   * address asks for, of the kinds and about the thing it names, in order.
   */
  void events(HttpExchange exchange) throws IOException {
    RobotSession session = session();
    Map<String, String> asked = Requests.query(exchange);
    List<RobotEvent> answer = asked(session.events(), asked);
    Map<String, Object> shown = new LinkedHashMap<>();
    shown.put("found", session.events().found());
    shown.put("oldest", session.oldest());
    shown.put("newest", session.taken() - 1);
    shown.put("events", answer.stream().map(RobotWire::wire).toList());
    responses.json(exchange, 200, shown);
  }

  /** The events an address asks for: since a number, around a loop, the newest, or a range. */
  private static List<RobotEvent> asked(RobotEvents events, Map<String, String> asked) {
    RobotEvents.Filter filter = eventFilter(asked);
    if (asked.containsKey("since")) {
      return events.since(Requests.wholeNumber("since", asked.get("since")), MOST_EVENTS);
    }
    if (asked.containsKey("before")) {
      long loop = Requests.wholeNumber("before", asked.get("before"));
      return events.before(loop, filter).stream().toList();
    }
    if (asked.containsKey("after")) {
      long loop = Requests.wholeNumber("after", asked.get("after"));
      return events.after(loop, filter).stream().toList();
    }
    if (asked.containsKey("newest")) {
      long most = Math.clamp(Requests.wholeNumber("newest", asked.get("newest")), 0, MOST_EVENTS);
      return events.newest(filter, (int) most);
    }
    if (asked.containsKey("fromAt") || asked.containsKey("toAt")) {
      long from = numberOr(asked, "fromAt", 0);
      long to = numberOr(asked, "toAt", Long.MAX_VALUE - 1);
      return events.betweenTimes(from, to, filter, MOST_EVENTS);
    }
    long from = numberOr(asked, "from", 0);
    long to = numberOr(asked, "to", Long.MAX_VALUE);
    return events.between(from, to, filter, MOST_EVENTS);
  }

  /** A query's whole number, or a default when the query doesn't name it. */
  private static long numberOr(Map<String, String> asked, String name, long otherwise) {
    if (!asked.containsKey(name)) {
      return otherwise;
    }
    return Requests.wholeNumber(name, asked.get(name));
  }

  /** Which events an address asks for: of some kinds, about one thing, the problems only. */
  private static RobotEvents.Filter eventFilter(Map<String, String> asked) {
    Set<RobotEvent.Kind> kinds = new LinkedHashSet<>();
    for (String name : asked.getOrDefault("kind", "").split(",")) {
      if (name.isEmpty()) {
        continue;
      }
      try {
        kinds.add(RobotEvent.Kind.valueOf(name));
      } catch (IllegalArgumentException e) {
        throw new Problem(
            400,
            "Not a kind of event",
            name + " is not one of " + List.of(RobotEvent.Kind.values()));
      }
    }
    boolean problems = Boolean.parseBoolean(asked.getOrDefault("problems", "false"));
    return new RobotEvents.Filter(
        kinds,
        asked.getOrDefault("about", ""),
        problems ? RobotEvents.Shown.PROBLEMS_ONLY : RobotEvents.Shown.EVERY_EVENT);
  }
}
