package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Kind;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Severity;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent.Subject;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvents.Filter;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The session's events, as a function of its moments: sequences of moments built by hand ({@link
 * Moments}) for the test robot ({@link TestRobot}), taken by a session as a robot's loops are
 * ({@link Recorded}), and the events the session then has.
 */
class RobotEventsTest {
  private static final String INTAKE = "operator right trigger: while held: Intake";
  private static final String ROLLER_RULE = "roller runs only when pivot at least 110°";

  /** An event as a test expects it: everything but its number, its loop and its time. */
  private record Expected(
      Kind kind,
      Subject subject,
      List<String> about,
      Severity severity,
      String sentence,
      Optional<MoveResult> result) {
    static Expected of(Kind kind, Subject subject, String about, String sentence) {
      List<String> names = about.isEmpty() ? List.of() : List.of(about);
      return new Expected(kind, subject, names, Severity.ROUTINE, sentence, Optional.empty());
    }

    Expected problem() {
      return new Expected(kind, subject, about, Severity.PROBLEM, sentence, result);
    }

    Expected ended(MoveResult how) {
      return new Expected(kind, subject, about, severity, sentence, Optional.of(how));
    }

    static Expected of(RobotEvent event) {
      return new Expected(
          event.kind(),
          event.subject(),
          event.about(),
          event.severity(),
          event.sentence(),
          event.moveResult());
    }
  }

  private static Expected joint(Kind kind, String label, String sentence) {
    return Expected.of(kind, Subject.JOINT, label, sentence);
  }

  /**
   * One change from one loop to the next, and the events it is.
   *
   * @param from the loop before, from the robot at rest
   * @param change what the next loop changes
   */
  private record Change(
      String what,
      UnaryOperator<Moments> from,
      UnaryOperator<Moments> change,
      Expected... events) {}

  private static final UnaryOperator<Moments> AT_REST = moment -> moment;

  private static Moments intaking(Moments moment) {
    return moment.asked("pivot", "Intake", "out", 130);
  }

  private static final List<Change> CHANGES =
      List.of(
          new Change(
              "disabled",
              AT_REST,
              Moments::disabled,
              Expected.of(Kind.DISABLED, Subject.ROBOT, "", "the robot was disabled")),
          new Change(
              "enabled, which says the mode it is enabled in",
              Moments::disabled,
              moment -> moment.enabled().mode(Moment.Mode.AUTONOMOUS),
              Expected.of(Kind.ENABLED, Subject.ROBOT, "", "the robot was enabled in autonomous")),
          new Change(
              "another mode, while disabled",
              Moments::disabled,
              moment -> moment.mode(Moment.Mode.AUTONOMOUS),
              Expected.of(Kind.MODE_CHANGED, Subject.ROBOT, "", "the mode changed to autonomous")),
          new Change(
              "a binding fired",
              AT_REST,
              moment -> moment.fired(INTAKE),
              Expected.of(Kind.BINDING_FIRED, Subject.GAMEPAD, "", INTAKE)),
          new Change(
              "a switch tripped",
              AT_REST,
              moment ->
                  moment.sensor(
                      "claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN),
              Expected.of(Kind.SENSOR_TRIPPED, Subject.SENSOR, "claw beam", "claw beam tripped")),
          new Change(
              "a switch cleared",
              moment ->
                  moment.sensor(
                      "claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN),
              moment ->
                  moment.sensor(
                      "claw beam", Moment.Link.ANSWERS, Moment.Switched.CLEAR, Double.NaN),
              Expected.of(Kind.SENSOR_CLEARED, Subject.SENSOR, "claw beam", "claw beam clear")),
          new Change(
              "a sensor lost, which is a problem",
              AT_REST,
              moment ->
                  moment.sensor("hopper", Moment.Link.LOST, Moment.Switched.CLEAR, Double.NaN),
              Expected.of(Kind.SENSOR_LOST, Subject.SENSOR, "hopper", "hopper lost").problem()),
          new Change(
              "a lost sensor answering again",
              moment ->
                  moment.sensor("hopper", Moment.Link.LOST, Moment.Switched.CLEAR, Double.NaN),
              moment -> moment.sensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.8),
              Expected.of(Kind.SENSOR_ANSWERED, Subject.SENSOR, "hopper", "hopper answers again")),
          new Change(
              "a gate falling, which says why",
              moment -> moment.gate("ready to feed", Moment.Held.HOLDS),
              moment -> moment.gate("ready to feed", Moment.Held.DOES_NOT_HOLD, "shooter at goal"),
              Expected.of(
                  Kind.GATE_FELL,
                  Subject.GATE,
                  "ready to feed",
                  "ready to feed is false: shooter at goal")),
          new Change(
              "a gate beginning to hold",
              moment -> moment.gate("ready to feed", Moment.Held.DOES_NOT_HOLD, "shooter at goal"),
              moment -> moment.gate("ready to feed", Moment.Held.HOLDS),
              Expected.of(Kind.GATE_HELD, Subject.GATE, "ready to feed", "ready to feed holds")),
          new Change(
              "a joint that stopped answering, which is a problem",
              AT_REST,
              moment -> moment.standing("pivot", Moment.Link.LOST, JointState.READY),
              joint(
                      Kind.JOINT_STOPPED_ANSWERING,
                      "pivot",
                      "pivot isn't answering: its motor controllers are silent")
                  .problem()),
          new Change(
              "a joint answering again",
              moment -> moment.standing("pivot", Moment.Link.LOST, JointState.READY),
              moment -> moment.standing("pivot", Moment.Link.ANSWERS, JointState.READY),
              joint(Kind.JOINT_ANSWERED, "pivot", "pivot answers again")),
          new Change(
              "an absolute encoder lost, which is a problem",
              AT_REST,
              moment -> moment.encoder("pivot", Moment.Encoder.LOST),
              joint(Kind.ENCODER_CHANGED, "pivot", "pivot absolute encoder lost").problem()),
          new Change(
              "a joint's homing beginning",
              AT_REST,
              moment ->
                  moment.standing(
                      "pivot",
                      Moment.Link.ANSWERS,
                      JointState.homing(JointState.Toward.LOWER_STOP, 9.5)),
              joint(
                  Kind.STATE_CHANGED,
                  "pivot",
                  "pivot HOMING: toward its lower stop, for at most 9.5 s")),
          new Change(
              "a joint faulting, which is a problem",
              AT_REST,
              moment ->
                  moment.faulted(
                      "pivot", JointState.Fault.ENCODER_SLIPPED, "its encoder slipped by 12°"),
              joint(Kind.STATE_CHANGED, "pivot", "pivot FAULTED: its encoder slipped by 12°")
                  .problem()),
          new Change(
              "a joint becoming ready",
              moment ->
                  moment.standing(
                      "pivot",
                      Moment.Link.ANSWERS,
                      JointState.homing(JointState.Toward.LOWER_STOP, 9.5)),
              moment -> moment.standing("pivot", Moment.Link.ANSWERS, JointState.READY),
              joint(Kind.STATE_CHANGED, "pivot", "pivot READY")),
          new Change(
              "a command taking a joint, and asking it for a place",
              AT_REST,
              RobotEventsTest::intaking,
              joint(Kind.JOINT_TAKEN, "pivot", "pivot owned by Intake"),
              joint(Kind.JOINT_ASKED, "pivot", "pivot told out, 130°")),
          new Change(
              "the command that owns a joint asking it for another place",
              RobotEventsTest::intaking,
              moment -> moment.asked("pivot", "Intake", "in", 1.5),
              joint(Kind.JOINT_ASKED, "pivot", "pivot told in, 1.5°")),
          new Change(
              "another command taking a joint for the same place",
              RobotEventsTest::intaking,
              moment -> moment.asked("pivot", "Deploy", "out", 130),
              joint(Kind.JOINT_TAKEN, "pivot", "pivot owned by Deploy")),
          new Change(
              "a command leaving a joint",
              RobotEventsTest::intaking,
              moment -> moment.idle("pivot"),
              joint(Kind.JOINT_RELEASED, "pivot", "pivot idle, holding")),
          new Change(
              "a joint that only runs, asked for an output",
              AT_REST,
              moment -> moment.at("pivot", 130).asked("roller", "Intake", "intaking", 2000),
              joint(Kind.JOINT_TAKEN, "roller", "roller owned by Intake"),
              joint(Kind.JOINT_ASKED, "roller", "roller told intaking, 2000 rpm")),
          new Change(
              "a move arriving",
              RobotEventsTest::intaking,
              moment -> moment.at("pivot", 130).arrivedAt("pivot", "out", 0.41),
              joint(Kind.MOVE_ENDED, "pivot", "pivot → out: ARRIVED in 0.41 s")
                  .ended(MoveResult.ARRIVED)),
          new Change(
              "a move timing out, which is a problem",
              moment -> intaking(moment).at("pivot", 50),
              moment -> moment.ended("pivot", "out", MoveResult.TIMED_OUT, 1.62),
              joint(Kind.MOVE_ENDED, "pivot", "pivot → out: TIMED_OUT after 1.62 s, pivot at 50°")
                  .ended(MoveResult.TIMED_OUT)
                  .problem()),
          new Change(
              "a move refused, which is a problem",
              AT_REST,
              moment -> moment.ended("pivot", "out", MoveResult.REFUSED, 0),
              joint(Kind.MOVE_ENDED, "pivot", "pivot → out: REFUSED")
                  .ended(MoveResult.REFUSED)
                  .problem()),
          new Change(
              "a move canceled, which is not",
              moment -> intaking(moment).at("pivot", 50),
              moment -> moment.ended("pivot", "out", MoveResult.CANCELED, 0.2),
              joint(Kind.MOVE_ENDED, "pivot", "pivot → out: CANCELED after 0.20 s, pivot at 50°")
                  .ended(MoveResult.CANCELED)),
          new Change(
              "an interlock beginning to hold a joint a command asks",
              moment -> moment.asked("roller", "Intake", "intaking", 2000),
              moment -> moment.heldBy("roller", Moments.ROLLER_RULE),
              joint(Kind.HOLD_BEGAN, "roller", "roller held: " + ROLLER_RULE)),
          new Change(
              "a command asking a joint its interlock already restricts: now it is held",
              moment -> moment.heldBy("roller", Moments.ROLLER_RULE),
              moment -> moment.asked("roller", "Intake", "intaking", 2000),
              joint(Kind.JOINT_TAKEN, "roller", "roller owned by Intake"),
              joint(Kind.JOINT_ASKED, "roller", "roller told intaking, 2000 rpm"),
              joint(Kind.HOLD_BEGAN, "roller", "roller held: " + ROLLER_RULE)),
          new Change(
              "nothing holding a joint any more",
              moment ->
                  moment
                      .asked("roller", "Intake", "intaking", 2000)
                      .heldBy("roller", Moments.ROLLER_RULE),
              moment -> moment.heldBy("roller"),
              joint(Kind.HOLD_ENDED, "roller", "roller no longer held")));

  private final RobotSession session = RobotSession.of(TestRobot.definition());

  private List<Expected> everyEvent(RobotSession of) {
    return of.events().since(0, Integer.MAX_VALUE).stream().map(Expected::of).toList();
  }

  /** Each kind of change is its event, in the model's words, and a problem is flagged as one. */
  @Test
  void eachChangeIsItsEventInWordsAndAProblemIsFlagged() {
    for (Change change : CHANGES) {
      RobotSession fresh = RobotSession.of(TestRobot.definition());
      Moments robot = change.from().apply(Moments.atRest());
      Recorded.take(fresh, robot.build());
      Recorded.take(fresh, change.change().apply(robot).build());

      assertThat(everyEvent(fresh)).as(change.what()).containsExactly(change.events());
      assertThat(fresh.events().since(0, 10))
          .as(change.what() + ": in the loop it changed in, at that loop's time")
          .allSatisfy(
              event -> {
                assertThat(event.loop()).isEqualTo(1);
                assertThat(event.timestampNanos()).isEqualTo(Recorded.LOOP_NANOS);
              });
    }
  }

  /** Every kind of event there is has a change that makes it, above. */
  @Test
  void everyKindOfEventIsCovered() {
    List<Kind> made = new ArrayList<>();
    for (Change change : CHANGES) {
      for (Expected event : change.events()) {
        made.add(event.kind());
      }
    }
    assertThat(made).contains(Kind.values());
  }

  /**
   * A robot doing what it was doing has no events: a joint moving between the same two places, a
   * rangefinder's reading drifting, loop after loop. Nor has an interlock that restricts a joint
   * nothing asks to move, coming or going.
   */
  @Test
  void aLoopInWhichNothingAPersonFollowsChangedHasNoEvent() {
    Moments robot = intaking(Moments.atRest().at("pivot", 20));
    Recorded.take(session, robot.build());
    for (int loop = 1; loop <= 100; loop++) {
      robot
          .at("pivot", 20 + loop * 0.8)
          .sensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.8 - loop * 0.001);
      intaking(robot);
      if (loop == 40) {
        robot.heldBy("roller", Moments.ROLLER_RULE);
      }
      if (loop == 60) {
        robot.heldBy("roller");
      }
      Recorded.take(session, robot.build());
    }

    assertThat(session.events().found()).isZero();
  }

  /** A move of two joints ends on both in one loop, and is one event about them both. */
  @Test
  void aMoveOfSeveralJointsIsOneEventAboutEachOfThem() {
    Moments robot = Moments.atRest();
    Recorded.take(session, robot.build());
    robot
        .atMeters("elevator", 0.8128)
        .at("arm", 120)
        .arrivedTogether("elevator and arm → high", "elevator", "clear", "arm", "scoring", 0.72);
    Recorded.take(session, robot.build());

    List<RobotEvent> events = session.events().since(0, 10);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().about()).containsExactly("elevator", "arm");
    assertThat(events.getFirst().sentence()).startsWith("elevator and arm → high: ARRIVED in 0.72");
    assertThat(session.events().between(0, 10, Filter.about("arm"), 10)).hasSize(1);
  }

  /**
   * An intake, loop by loop: the trigger, the pivot going out with the roller held behind its
   * interlock, the roller let go as the pivot passes 110°, the pivot arriving, the piece seen.
   */
  private List<Long> anIntake() {
    Moments robot = Moments.atRest().heldBy("roller", Moments.ROLLER_RULE);
    List<Long> loops = new ArrayList<>();
    loops.add(Recorded.take(session, robot.build()));
    robot.fired(INTAKE);
    intaking(robot).asked("roller", "Intake", "intaking", 2000);
    loops.add(Recorded.take(session, robot.build()));
    loops.add(Recorded.take(session, robot.firedNothing().at("pivot", 60).build()));
    intaking(robot);
    loops.add(Recorded.take(session, robot.at("pivot", 112).heldBy("roller").build()));
    intaking(robot);
    loops.add(
        Recorded.take(session, robot.at("pivot", 130).arrivedAt("pivot", "out", 0.41).build()));
    robot.sensor("claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN);
    robot.idle("pivot").idle("roller");
    loops.add(Recorded.take(session, robot.build()));
    return loops;
  }

  /**
   * The events of an intake read in the order things happened, and within one loop in the order a
   * cause runs: the binding, then the sensors, then each joint.
   */
  @Test
  void theEventsOfAnIntakeReadInTheOrderThingsHappened() {
    anIntake();

    assertThat(session.events().since(0, 100))
        .extracting(event -> event.loop() + " " + event.sentence())
        .containsExactly(
            "1 " + INTAKE,
            "1 pivot owned by Intake",
            "1 pivot told out, 130°",
            "1 roller owned by Intake",
            "1 roller told intaking, 2000 rpm",
            "1 roller held: " + ROLLER_RULE,
            "3 roller no longer held",
            "4 pivot → out: ARRIVED in 0.41 s",
            "5 claw beam tripped",
            "5 pivot idle, holding",
            "5 roller idle, stopped");
  }

  /** Events are asked for by loops, by times, the newest, one at a time, and filtered. */
  @Test
  void eventsAreAskedForByLoopsByTimesTheNewestAndOneAtATime() {
    anIntake();
    RobotEvents events = session.events();
    Filter everything = Filter.EVERYTHING;

    assertThat(events.between(3, 4, everything, 100))
        .as("a range of loops, both ends included")
        .extracting(RobotEvent::sentence)
        .containsExactly("roller no longer held", "pivot → out: ARRIVED in 0.41 s");
    assertThat(events.betweenTimes(3 * Recorded.LOOP_NANOS, 4 * Recorded.LOOP_NANOS, everything, 9))
        .as("a range of times, both ends included")
        .extracting(RobotEvent::loop)
        .containsExactly(3L, 4L);
    assertThat(events.between(0, 100, everything, 2)).as("the first so many").hasSize(2);
    assertThat(events.newest(everything, 2))
        .as("the newest, in order")
        .extracting(RobotEvent::sentence)
        .containsExactly("pivot idle, holding", "roller idle, stopped");
    assertThat(events.newest(Filter.about("pivot"), 2))
        .extracting(RobotEvent::sentence)
        .containsExactly("pivot → out: ARRIVED in 0.41 s", "pivot idle, holding");
    assertThat(events.since(events.found() - 1, 100)).as("from a number on").hasSize(1);

    assertThat(events.after(1, everything).map(RobotEvent::loop)).contains(3L);
    assertThat(events.before(3, everything).map(RobotEvent::sentence))
        .as("the last event of the nearest loop before")
        .contains("roller held: " + ROLLER_RULE);
    assertThat(events.after(1, Filter.about("pivot")).map(RobotEvent::loop))
        .as("the next about one joint")
        .contains(4L);
    assertThat(events.before(5, Filter.kinds(Kind.BINDING_FIRED)).map(RobotEvent::loop))
        .as("the one before of a kind")
        .contains(1L);
    assertThat(events.after(5, everything)).as("none after the last").isEmpty();
    assertThat(events.before(1, everything)).as("none before the first").isEmpty();
    assertThat(events.between(0, 100, Filter.problems(), 100)).as("no problems").isEmpty();
  }

  /** The problems alone are asked for, so a failed move is found at once. */
  @Test
  void theProblemsAreAskedForAlone() {
    Moments robot = intaking(Moments.atRest().at("pivot", 50));
    Recorded.take(session, robot.build());
    Recorded.take(session, robot.ended("pivot", "out", MoveResult.TIMED_OUT, 1.62).build());
    Recorded.take(session, robot.idle("pivot").build());

    assertThat(session.events().between(0, 100, Filter.problems(), 100))
        .extracting(RobotEvent::sentence)
        .containsExactly("pivot → out: TIMED_OUT after 1.62 s, pivot at 50°");
    assertThat(session.events().found()).isEqualTo(2);
  }

  /** One of the changes a robot makes, picked by a number, for a long run of them. */
  private static void changeSomething(Moments robot, int which, int loop) {
    switch (which) {
      case 0 -> robot.asked("pivot", loop % 2 == 0 ? "Intake" : "Deploy", "out", 130);
      case 1 -> robot.idle("pivot");
      case 2 -> robot.at("pivot", loop % 140);
      case 3 -> robot.heldBy("roller", Moments.ROLLER_RULE);
      case 4 -> robot.heldBy("roller");
      case 5 -> robot.arrivedAt("pivot", "out", 0.3 + loop % 7 * 0.01);
      case 6 -> robot.ended("arm", "scoring", MoveResult.TIMED_OUT, 1 + loop % 5 * 0.1);
      case 7 ->
          robot.gate("piece in", loop % 2 == 0 ? Moment.Held.HOLDS : Moment.Held.DOES_NOT_HOLD);
      case 8 -> robot.sensor("claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN);
      case 9 -> robot.sensor("claw beam", Moment.Link.ANSWERS, Moment.Switched.CLEAR, Double.NaN);
      case 10 -> robot.fired(INTAKE);
      case 11 -> robot.disabled();
      case 12 -> robot.enabled();
      default -> robot.standing("shooter", Moment.Link.LOST, JointState.READY);
    }
  }

  /**
   * The events kept as the loops arrive are the events found again from the loops the session still
   * remembers, however long it has run: they are a function of its moments, and they age out with
   * them.
   */
  @Test
  void theEventsKeptAsLoopsArriveAreThoseFoundAgainFromTheLoopsRemembered() {
    RobotSession brief = RobotSession.of(TestRobot.definition(), 0.02, 2);
    Random random = new Random(7);
    Moments robot = Moments.atRest();
    for (int loop = 0; loop < 1000; loop++) {
      robot.firedNothing();
      if (random.nextInt(3) == 0) {
        changeSomething(robot, random.nextInt(14), loop);
      }
      Recorded.take(brief, robot.build());

      boolean checked = loop % 37 == 0 || loop == 999;
      if (checked) {
        assertThat(brief.events().since(0, Integer.MAX_VALUE).stream().map(Expected::of))
            .as("after loop " + loop)
            .containsExactlyElementsOf(
                brief.events().foundAgain().stream().map(Expected::of).toList());
      }
    }

    assertThat(brief.capacity()).isEqualTo(100);
    assertThat(brief.events().found()).as("it found events all the way").isGreaterThan(100);
    assertThat(brief.events().since(0, Integer.MAX_VALUE))
        .as("and holds only those of loops it remembers, each after a loop it remembers")
        .isNotEmpty()
        .allSatisfy(event -> assertThat(event.loop()).isGreaterThan(brief.oldest()));
  }

  /** Its ring holds the newest events, however many there were: its memory never grows. */
  @Test
  void itsRingKeepsTheNewestEventsHoweverManyThereWere() {
    Moments robot = Moments.atRest();
    int loops = RobotEvents.KEPT + 500;
    for (int loop = 0; loop <= loops; loop++) {
      Moment.Switched read = loop % 2 == 0 ? Moment.Switched.CLEAR : Moment.Switched.TRIPPED;
      Recorded.take(
          session, robot.sensor("claw beam", Moment.Link.ANSWERS, read, Double.NaN).build());
    }

    List<RobotEvent> kept = session.events().since(0, Integer.MAX_VALUE);
    assertThat(session.events().found()).isEqualTo(loops);
    assertThat(kept).hasSize(RobotEvents.KEPT);
    assertThat(kept.getFirst().number()).isEqualTo(loops - RobotEvents.KEPT);
    assertThat(kept.getLast().loop()).isEqualTo(loops);
    assertThat(session.events().bytes()).isEqualTo(RobotEvents.KEPT * 12L);
  }
}
