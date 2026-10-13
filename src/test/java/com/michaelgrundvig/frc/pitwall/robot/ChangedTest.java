package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.robot.Changed.Row;
import com.michaelgrundvig.frc.world.record.JointState;
import com.michaelgrundvig.frc.world.record.MoveResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What changed in a loop, as a function of two moments next to each other: moments built by hand
 * ({@link Moments}) for the test robot ({@link TestRobot}).
 */
class ChangedTest {
  private static final RobotPlan PLAN = RobotPlan.of(TestRobot.definition());

  /**
   * One loop after another, and what the second is marked as changing.
   *
   * @param from the loop before, from the robot at rest
   * @param change what the next loop changes
   * @param joints the rows marked on each joint's card
   */
  private record Step(
      String what,
      UnaryOperator<Moments> from,
      UnaryOperator<Moments> change,
      boolean robot,
      Map<String, Set<Row>> joints,
      List<String> sensors,
      List<String> gates) {
    /** A step that changes rows of one joint's card and nothing else. */
    static Step ofJoint(
        String what,
        UnaryOperator<Moments> from,
        UnaryOperator<Moments> change,
        String label,
        Row... rows) {
      return new Step(what, from, change, false, Map.of(label, Set.of(rows)), List.of(), List.of());
    }
  }

  private static final UnaryOperator<Moments> AT_REST = moment -> moment;

  /** The pivot on its way out, at an angle: where it is, and then what it is asked. */
  private static Moments goingOut(Moments moment, double degrees) {
    return moment.at("pivot", degrees).asked("pivot", "Intake", "out", 130);
  }

  private static Moments goingOut(Moments moment) {
    return goingOut(moment, 60);
  }

  private static Changed between(Step step) {
    Moments robot = step.from().apply(Moments.atRest());
    Moment before = robot.build();
    return Changed.between(PLAN, before, step.change().apply(robot).build());
  }

  @Test
  void aRowIsMarkedInTheLoopWhatItSaysChanged() {
    List<Step> steps =
        List.of(
            new Step("nothing", AT_REST, AT_REST, false, Map.of(), List.of(), List.of()),
            new Step(
                "a joint moving between the same two places: a number moved, and no row",
                ChangedTest::goingOut,
                moment -> goingOut(moment, 61),
                false,
                Map.of(),
                List.of(),
                List.of()),
            Step.ofJoint(
                "a joint reaching a place",
                ChangedTest::goingOut,
                moment -> goingOut(moment, 130),
                "pivot",
                Row.AT),
            Step.ofJoint(
                "a command taking a joint and telling it where",
                AT_REST,
                moment -> moment.asked("pivot", "Intake", "out", 130),
                "pivot",
                Row.OWNED_BY,
                Row.TOLD),
            Step.ofJoint(
                "a move ending",
                ChangedTest::goingOut,
                moment -> moment.ended("pivot", "out", MoveResult.TIMED_OUT, 1.62),
                "pivot",
                Row.LAST_MOVE),
            Step.ofJoint(
                "an interlock beginning to restrict a joint",
                AT_REST,
                moment -> moment.heldBy("roller", Moments.ROLLER_RULE),
                "roller",
                Row.HELD_BY),
            Step.ofJoint(
                "a command asking a joint its interlock restricts: restricted becomes held",
                moment -> moment.heldBy("roller", Moments.ROLLER_RULE),
                moment -> moment.asked("roller", "Intake", "intaking", 2000),
                "roller",
                Row.OWNED_BY,
                Row.TOLD,
                Row.AT,
                Row.HELD_BY),
            Step.ofJoint(
                "a joint's homing beginning: it no longer knows where it is",
                AT_REST,
                moment ->
                    moment.standing(
                        "pivot",
                        Moment.Link.ANSWERS,
                        JointState.homing(JointState.Toward.LOWER_STOP, 9.5)),
                "pivot",
                Row.STATE,
                Row.AT),
            Step.ofJoint(
                "a joint's motor controller lost",
                AT_REST,
                moment -> moment.standing("pivot", Moment.Link.LOST, JointState.READY),
                "pivot",
                Row.MOTOR_CONTROLLER),
            Step.ofJoint(
                "a joint's absolute encoder reading badly",
                AT_REST,
                moment -> moment.encoder("arm", Moment.Encoder.MAGNET_BAD),
                "arm",
                Row.ABSOLUTE_ENCODER),
            new Step(
                "a switch tripping, and a rangefinder's distance only moving",
                AT_REST,
                moment ->
                    moment
                        .sensor(
                            "claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN)
                        .sensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.6),
                false,
                Map.of(),
                List.of("claw beam"),
                List.of()),
            new Step(
                "a sensor lost",
                AT_REST,
                moment ->
                    moment.sensor("hopper", Moment.Link.LOST, Moment.Switched.CLEAR, Double.NaN),
                false,
                Map.of(),
                List.of("hopper"),
                List.of()),
            new Step(
                "a gate falling, and another gate's blocking terms changing",
                moment ->
                    moment
                        .gate("piece in", Moment.Held.HOLDS)
                        .gate(
                            "ready to feed", Moment.Held.DOES_NOT_HOLD, "aimed", "shooter at goal")
                        .gate("aimed", Moment.Held.HOLDS),
                moment ->
                    moment
                        .gate("piece in", Moment.Held.DOES_NOT_HOLD, "claw beam clear")
                        .gate("ready to feed", Moment.Held.DOES_NOT_HOLD, "shooter at goal"),
                false,
                Map.of(),
                List.of(),
                List.of("piece in", "ready to feed")),
            new Step(
                "the robot disabled: every idle joint's owner says so",
                AT_REST,
                Moments::disabled,
                true,
                Map.of(
                    "pivot", Set.of(Row.OWNED_BY),
                    "roller", Set.of(Row.OWNED_BY),
                    "elevator", Set.of(Row.OWNED_BY),
                    "arm", Set.of(Row.OWNED_BY),
                    "shooter", Set.of(Row.OWNED_BY),
                    "claw", Set.of(Row.OWNED_BY)),
                List.of(),
                List.of()),
            new Step(
                "another mode",
                AT_REST,
                moment -> moment.mode(Moment.Mode.AUTONOMOUS),
                true,
                Map.of(),
                List.of(),
                List.of()));

    for (Step step : steps) {
      Changed changed = between(step);

      assertThat(changed.robot()).as(step.what() + ": the robot").isEqualTo(step.robot());
      assertThat(changed.joints()).as(step.what() + ": joints").isEqualTo(step.joints());
      assertThat(changed.sensors())
          .as(step.what() + ": sensors")
          .containsExactlyInAnyOrderElementsOf(step.sensors());
      assertThat(changed.gates())
          .as(step.what() + ": gates")
          .containsExactlyInAnyOrderElementsOf(step.gates());
      assertThat(changed.isNothing())
          .as(step.what() + ": nothing")
          .isEqualTo(
              !step.robot()
                  && step.joints().isEmpty()
                  && step.sensors().isEmpty()
                  && step.gates().isEmpty());
    }
  }

  /** A session answers for any loop it holds with the loop before it, and for no other. */
  @Test
  void aSessionSaysWhatChangedInALoopItHoldsWithTheLoopBeforeIt() {
    RobotSession session = RobotSession.of(TestRobot.definition());
    Moments robot = Moments.atRest();
    Recorded.take(session, robot.build());
    Recorded.take(session, robot.asked("pivot", "Intake", "out", 130).build());
    Recorded.take(session, robot.build());

    assertThat(session.changed(0)).as("the first loop has no loop before it").isEmpty();
    assertThat(session.changed(1).orElseThrow().joint("pivot"))
        .containsExactlyInAnyOrder(Row.OWNED_BY, Row.TOLD);
    assertThat(session.changed(2).orElseThrow().isNothing()).isTrue();
    assertThat(session.changed(3)).as("not taken yet").isEmpty();
  }
}
