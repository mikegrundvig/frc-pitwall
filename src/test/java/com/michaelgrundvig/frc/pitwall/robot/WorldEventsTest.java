package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Subject;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The session's events about where the robot is: it was placed, a camera's measurement was refused,
 * what the estimate rests on changed, the estimate recovered. Each is found from the loops' moments
 * alone, and said in the words the World view says it in.
 */
class WorldEventsTest {
  private static final long LOOP_NANOS = 20_000_000L;
  private static final double[] NO_NUMBERS = new double[0];

  private static final SightingWords WORDS =
      new SightingWords() {
        @Override
        public String verdict(int verdict, double value, double threshold) {
          if (verdict == 2) {
            return String.format(Locale.ROOT, "too far: %.1f m", value);
          }
          return String.format(Locale.ROOT, "gate: %.1f > %.1f", value, threshold);
        }

        @Override
        public String solver(int solver) {
          return "SINGLE_TAG";
        }
      };

  private final RobotSession session =
      RobotSession.of(
          TestRobot.definition(),
          RobotSession.LOOP_SECONDS,
          RobotSession.REMEMBERED_SECONDS,
          WORDS);
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest().estimate(3, 4, 0, "TRACKING");
  private long loops;

  private void loop() {
    session.take(record.at(1_000_000_000L + loops++ * LOOP_NANOS));
  }

  private void loops(int howMany) {
    for (int each = 0; each < howMany; each++) {
      loop();
    }
  }

  private List<RobotEvent> events() {
    return session.events().since(0, Integer.MAX_VALUE);
  }

  private void frontSaw(Sighting.Fate fate, int verdict, double value) {
    record.sightings(
        "front",
        new Sighting[] {new Sighting(7, 0, fate, verdict, 1)},
        FakeRecord.measured(value, 13.8, 0.8, 3.2, 4.1, 0));
  }

  private void frontSawNothing() {
    record.sightings("front", new Sighting[0], NO_NUMBERS);
  }

  @Test
  void aRobotThatStandsTrackingHasNoEvents() {
    frontSaw(Sighting.Fate.ACCEPTED, 0, 0.2);
    loops(100);

    assertThat(events()).isEmpty();
  }

  /** A run of refusals is one event, its first, said as a click on its sight line says it. */
  @Test
  void aRunOfRefusedMeasurementsIsOneEvent() {
    loop();
    frontSaw(Sighting.Fate.REJECTED, 1, 45.2);
    loops(3);
    frontSaw(Sighting.Fate.ACCEPTED, 0, 0.2);
    loop();
    frontSaw(Sighting.Fate.REJECTED, 1, 51.7);
    loops(20);

    assertThat(events())
        .extracting(
            RobotEvent::loop,
            RobotEvent::kind,
            RobotEvent::subject,
            RobotEvent::about,
            RobotEvent::severity,
            RobotEvent::sentence)
        .containsExactly(
            tuple(
                1L,
                Kind.SIGHTING_REFUSED,
                Subject.CAMERA,
                List.of("front"),
                Severity.ROUTINE,
                "front → tag 7 rejected: gate: 45.2 > 13.8"));
  }

  /** After a second without one, the next refusal is news again: another run. */
  @Test
  void aRefusalASecondAfterTheLastBeginsAnotherRun() {
    loop();
    frontSaw(Sighting.Fate.REJECTED, 1, 45.2);
    loop();
    frontSawNothing();
    loops(EventFinder.REFUSALS_APART_LOOPS);
    // An image left out before it became a measurement is refused too, in its own words.
    record.sightings(
        "front",
        new Sighting[] {new Sighting(8, 0, Sighting.Fate.LEFT_OUT, 2, -1)},
        new double[] {6.3, 6.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN});
    loop();

    assertThat(events())
        .extracting(RobotEvent::sentence)
        .containsExactly(
            "front → tag 7 rejected: gate: 45.2 > 13.8", "front → tag 8 rejected: too far: 6.3 m");
    assertThat(session.events().newest(RobotEvents.Filter.about("front"), 10)).hasSize(2);
  }

  /** An image the robot held back on purpose is nothing wrong, and no event. */
  @Test
  void anImageHeldBackOnPurposeIsNoEvent() {
    loop();
    frontSaw(Sighting.Fate.HELD_BACK, 3, Double.NaN);
    loops(5);

    assertThat(events()).isEmpty();
  }

  @Test
  void whatTheEstimateRestsOnChangingIsAnEventAndLostIsAProblem() {
    loop();
    record.put(LogKeys.RESTS_ON, "DEAD_RECKONING");
    loop();
    record.put(LogKeys.RESTS_ON, "LOST");
    loop();
    record.put(LogKeys.RESTS_ON, "TRACKING");
    loops(3);

    assertThat(events())
        .extracting(
            RobotEvent::kind, RobotEvent::subject, RobotEvent::severity, RobotEvent::sentence)
        .containsExactly(
            tuple(
                Kind.ESTIMATE_RESTS_CHANGED,
                Subject.ESTIMATE,
                Severity.ROUTINE,
                "localization is now dead reckoning"),
            tuple(
                Kind.ESTIMATE_RESTS_CHANGED,
                Subject.ESTIMATE,
                Severity.PROBLEM,
                "localization is now lost"),
            tuple(
                Kind.ESTIMATE_RESTS_CHANGED,
                Subject.ESTIMATE,
                Severity.ROUTINE,
                "localization is now tracking"));
  }

  /** A recovery is a problem, and says how far the estimate jumped. */
  @Test
  void aRecoveryIsAProblemThatSaysHowFarTheEstimateJumped() {
    loop();
    record.estimate(3.6, 4.8, 0, "TRACKING").put(LogKeys.RECOVERIES, 1L);
    loops(2);

    assertThat(events())
        .extracting(RobotEvent::loop, RobotEvent::kind, RobotEvent::severity, RobotEvent::sentence)
        .containsExactly(
            tuple(
                1L,
                Kind.ESTIMATE_RECOVERED,
                Severity.PROBLEM,
                "the estimate jumped 1.00 m to agree with the cameras"));
    assertThat(session.events().newest(RobotEvents.Filter.problems(), 10)).hasSize(1);
  }

  @Test
  void theRobotBeingPlacedIsAnEventInTheRobotProgramsWords() {
    loop();
    record.put(LogKeys.PLACEMENTS, 1L).put(LogKeys.PLACED_BECAUSE, "placed on Center");
    loops(2);

    assertThat(events())
        .extracting(RobotEvent::kind, RobotEvent::subject, RobotEvent::sentence)
        .containsExactly(tuple(Kind.ROBOT_PLACED, Subject.ROBOT, "the robot was placed on Center"));
  }

  /** Found again later from the loops the memory holds, the events are the same. */
  @Test
  void theEventsFoundAgainFromTheMemoryAreTheSame() {
    loop();
    record.put(LogKeys.PLACEMENTS, 1L).put(LogKeys.PLACED_BECAUSE, "placed on Center");
    frontSaw(Sighting.Fate.REJECTED, 1, 45.2);
    loops(4);
    record.estimate(3.6, 4.8, 0, "LOST").put(LogKeys.RECOVERIES, 1L).put(LogKeys.PLACEMENTS, 1L);
    loops(3);

    assertThat(events()).hasSize(4);
    assertThat(session.events().foundAgain()).isEqualTo(events());
  }
}
