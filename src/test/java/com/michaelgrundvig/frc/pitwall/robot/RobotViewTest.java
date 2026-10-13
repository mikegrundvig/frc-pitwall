package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.michaelgrundvig.frc.pitwall.robot.Causes.Emphasis;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Asked;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Drawn;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Health;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.JointCard;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Recorded;
import com.michaelgrundvig.frc.pitwall.robot.RobotView.Turning;
import com.michaelgrundvig.frc.world.robot.ConditionReading;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The Robot view as a function of the robot's definition and one moment, fed moments built by hand
 * ({@link Moments}) for the test robot ({@link TestRobot}).
 */
class RobotViewTest {
  private static final String ROLLER_RULE = "roller runs only when pivot at least 110°";
  private static final String ARM_RULE =
      "arm keeps out of -10° to 30° while elevator at most 0.8128 m";
  private static final String CLAW_RULE = "claw runs only when claw beam clear";

  // ---- Cause across cards ----

  /**
   * One moment of the intake: where the pivot is and whether it can be read, what the roller is
   * asked, and what the view then says on both cards.
   *
   * @param setUp the moment, from the robot at rest
   * @param heldBy what restricts or holds the roller, on the roller's card
   * @param hold whether the roller is free, only restricted, or held short of what was asked
   * @param emphasis how strongly the roller's rule is marked on the pivot's card
   * @param reading what the rule's condition reads
   */
  private record Intake(
      String what,
      UnaryOperator<Moments> setUp,
      List<String> heldBy,
      RobotView.Hold hold,
      Emphasis emphasis,
      ConditionReading reading) {}

  private static Moments intaking(Moments moment) {
    return moment.asked("roller", "Intake", "intaking", 2000);
  }

  /**
   * The roller runs only when the pivot is at least 110°. The threshold is marked on the pivot's
   * card, and is what is waited for exactly while a command asks the roller to run and the model
   * holds it.
   */
  @Test
  void theRollersRuleIsMarkedOnThePivotsCardAndWaitedForWhileItHoldsTheRoller() {
    List<Intake> scenarios =
        List.of(
            new Intake(
                "asked to intake with the pivot short of 110°",
                moment -> intaking(moment.at("pivot", 50)),
                List.of(ROLLER_RULE),
                RobotView.Hold.HELD,
                Emphasis.WAITED_FOR,
                ConditionReading.DOES_NOT_HOLD),
            new Intake(
                "asked to intake with the pivot at 110°",
                moment -> intaking(moment.at("pivot", 110)),
                List.of(),
                RobotView.Hold.FREE,
                Emphasis.QUIET,
                ConditionReading.HOLDS),
            new Intake(
                "idle with the pivot short: the rule restricts, and nothing waits",
                moment -> moment.at("pivot", 50),
                List.of(ROLLER_RULE),
                RobotView.Hold.RESTRICTED,
                Emphasis.IN_FORCE,
                ConditionReading.DOES_NOT_HOLD),
            new Intake(
                "asked while the robot is disabled: nothing is driven, so nothing is held",
                moment -> intaking(moment.at("pivot", 50)).disabled(),
                List.of(),
                RobotView.Hold.FREE,
                Emphasis.IN_FORCE,
                ConditionReading.DOES_NOT_HOLD),
            new Intake(
                "asked while the pivot's motor controller is lost: unknown holds the roller",
                moment ->
                    intaking(moment.at("pivot", 130)).standing("pivot", Moment.Link.LOST, "READY"),
                List.of(ROLLER_RULE),
                RobotView.Hold.HELD,
                Emphasis.WAITED_FOR,
                ConditionReading.UNKNOWN),
            new Intake(
                "asked while the pivot does not know where it is",
                moment ->
                    intaking(moment.at("pivot", 130))
                        .standing("pivot", Moment.Link.ANSWERS, "HOMING: toward its lower stop"),
                List.of(ROLLER_RULE),
                RobotView.Hold.HELD,
                Emphasis.WAITED_FOR,
                ConditionReading.UNKNOWN));

    for (Intake scenario : scenarios) {
      RobotView view = scenario.setUp().apply(Moments.atRest()).view();

      assertThat(view.joint("roller").facts().heldBy())
          .as(scenario.what() + ": held by")
          .isEqualTo(scenario.heldBy());
      assertThat(view.joint("roller").facts().hold())
          .as(scenario.what() + ": hold")
          .isEqualTo(scenario.hold());
      assertThat(view.joint("pivot").marks())
          .as(scenario.what() + ": the mark on the pivot's card")
          .singleElement()
          .satisfies(
              mark -> {
                assertThat(mark.from()).isCloseTo(110, within(1e-9));
                assertThat(mark.to()).isNaN();
                assertThat(mark.rule()).isEqualTo(RobotView.Rule.RUNS_ONLY_WHEN);
                assertThat(mark.forJoint()).isEqualTo("roller");
                assertThat(mark.label()).isEqualTo("roller runs only when at least 110°");
                assertThat(mark.sentence()).isEqualTo(ROLLER_RULE);
                assertThat(mark.emphasis()).isEqualTo(scenario.emphasis());
                assertThat(mark.reading()).isEqualTo(scenario.reading());
              });
      assertThat(view.joint("roller").marks()).as("the roller's own card has no mark").isEmpty();
    }
  }

  /**
   * One moment of the arm on its elevator.
   *
   * @param heldBy what holds the arm, on the arm's card
   * @param emphasis how strongly the keep-out is drawn, as a band on the arm's card and as a mark
   *     on the elevator's
   */
  private record Lift(
      String what, UnaryOperator<Moments> setUp, List<String> heldBy, Emphasis emphasis) {}

  /**
   * The arm keeps out of -10° to 30° while the elevator is at most 32 in. The region is a band on
   * the arm's card, and its condition a mark on the elevator's: quiet while the region is open, in
   * force while it is closed, and waited for while it holds the arm short of where it was asked.
   */
  @Test
  void aKeepOutIsABandOnItsOwnCardAndItsConditionAMarkOnTheCardItDependsOn() {
    List<Lift> scenarios =
        List.of(
            new Lift(
                "the elevator down and the arm stowed: closed, and nothing asked",
                moment -> moment,
                List.of(),
                Emphasis.IN_FORCE),
            new Lift(
                "the arm asked to scoring through the closed region: held at its edge",
                moment -> moment.asked("arm", "Score", "scoring", -10),
                List.of(ARM_RULE),
                Emphasis.WAITED_FOR),
            new Lift(
                "the elevator above 32 in: the region is open",
                moment -> moment.atMeters("elevator", 1.0).asked("arm", "Score", "scoring", 120),
                List.of(),
                Emphasis.QUIET),
            new Lift(
                "the elevator lost: unknown closes the region, to be safe",
                moment ->
                    moment
                        .atMeters("elevator", 1.0)
                        .standing("elevator", Moment.Link.LOST, "READY")
                        .asked("arm", "Score", "scoring", -10),
                List.of(ARM_RULE),
                Emphasis.WAITED_FOR));

    for (Lift scenario : scenarios) {
      RobotView view = scenario.setUp().apply(Moments.atRest()).view();

      assertThat(view.joint("arm").facts().heldBy())
          .as(scenario.what() + ": held by")
          .isEqualTo(scenario.heldBy());
      assertThat(view.joint("arm").bands())
          .as(scenario.what() + ": the band on the arm's card")
          .singleElement()
          .satisfies(
              band -> {
                assertThat(band.lower()).isCloseTo(-10, within(1e-9));
                assertThat(band.upper()).isCloseTo(30, within(1e-9));
                assertThat(band.shown()).isEqualTo("-10° to 30°");
                assertThat(band.dependsOn()).isEqualTo("elevator");
                assertThat(band.rule()).isEqualTo(ARM_RULE);
                assertThat(band.emphasis()).isEqualTo(scenario.emphasis());
              });
      assertThat(view.joint("elevator").marks())
          .as(scenario.what() + ": the mark on the elevator's card")
          .singleElement()
          .satisfies(
              mark -> {
                assertThat(mark.from()).isNaN();
                assertThat(mark.to()).isCloseTo(0.8128, within(1e-9));
                assertThat(mark.rule()).isEqualTo(RobotView.Rule.KEEPS_OUT);
                assertThat(mark.label()).isEqualTo("arm keeps out while at most 0.8128 m");
                assertThat(mark.emphasis()).isEqualTo(scenario.emphasis());
              });
    }
  }

  /** A held arm says where it was asked to go and where it is held instead. */
  @Test
  void aHeldJointSaysWhereItWasAskedAndWhereItIsHeadedInstead() {
    JointCard arm = Moments.atRest().asked("arm", "Score", "scoring", -10).view().joint("arm");

    assertThat(arm.facts().told()).isEqualTo("scoring, 120°, headed for -10°");
    assertThat(arm.facts().ownedBy()).isEqualTo("Score");
    assertThat(arm.tick("scoring").asked()).isEqualTo(Asked.ASKED_FOR);
    assertThat(arm.tick("stowed").asked()).isEqualTo(Asked.NOT_ASKED_FOR);
    assertThat(arm.needles().goal()).isCloseTo(-10, within(1e-9));
  }

  /**
   * One moment of the claw, whose wheels run only while its beam is clear.
   *
   * @param reading what the beam's row says it reads
   */
  private record Claw(
      String what,
      Moment.Link link,
      Moment.Switched switched,
      String reading,
      Health health,
      List<String> heldBy,
      Emphasis emphasis,
      ConditionReading condition) {}

  /** A rule on a sensor is marked on the sensor's row, and a lost sensor holds what reads it. */
  @Test
  void aRuleOnASensorIsMarkedOnItsRowAndALostSensorHoldsTheJoint() {
    List<Claw> scenarios =
        List.of(
            new Claw(
                "the beam clear",
                Moment.Link.ANSWERS,
                Moment.Switched.CLEAR,
                "clear",
                Health.FINE,
                List.of(),
                Emphasis.QUIET,
                ConditionReading.HOLDS),
            new Claw(
                "the beam tripped",
                Moment.Link.ANSWERS,
                Moment.Switched.TRIPPED,
                "tripped",
                Health.FINE,
                List.of(CLAW_RULE),
                Emphasis.WAITED_FOR,
                ConditionReading.DOES_NOT_HOLD),
            new Claw(
                "the beam lost",
                Moment.Link.LOST,
                Moment.Switched.CLEAR,
                "lost",
                Health.NEEDS_ATTENTION,
                List.of(CLAW_RULE),
                Emphasis.WAITED_FOR,
                ConditionReading.UNKNOWN));

    for (Claw scenario : scenarios) {
      RobotView view =
          Moments.atRest()
              .sensor("claw beam", scenario.link(), scenario.switched(), Double.NaN)
              .asked("claw", "Grip", "gripping", 600)
              .view();

      RobotView.SensorRow beam = view.sensor("claw beam");
      assertThat(beam.reading()).as(scenario.what()).isEqualTo(scenario.reading());
      assertThat(beam.health()).as(scenario.what()).isEqualTo(scenario.health());
      assertThat(beam.on()).isEqualTo("pivot");
      assertThat(beam.marks())
          .as(scenario.what() + ": the mark on the beam's row")
          .singleElement()
          .satisfies(
              mark -> {
                assertThat(mark.forJoint()).isEqualTo("claw");
                assertThat(mark.label()).isEqualTo("claw runs only when clear");
                assertThat(mark.emphasis()).isEqualTo(scenario.emphasis());
                assertThat(mark.reading()).isEqualTo(scenario.condition());
              });
      assertThat(view.joint("claw").facts().heldBy())
          .as(scenario.what() + ": held by")
          .isEqualTo(scenario.heldBy());
    }
  }

  // ---- Each kind of joint ----

  /** Each joint is drawn by its kind, turned as the model's axis and its link say. */
  @Test
  void eachJointIsDrawnByItsKind() {
    RobotView view = Moments.atRest().view();

    assertThat(view.joints())
        .extracting(JointCard::label)
        .as("in the order the model lists them")
        .containsExactly("pivot", "roller", "elevator", "arm", "shooter", "claw");
    assertThat(view.joints())
        .extracting(JointCard::drawn)
        .containsExactly(Drawn.ARC, Drawn.DIAL, Drawn.BAR, Drawn.ARC, Drawn.DIAL, Drawn.DIAL);
    assertThat(view.joints())
        .extracting(JointCard::carriedBy)
        .containsExactly("", "pivot", "", "elevator", "", "arm");

    RobotView.Scale pivot = view.joint("pivot").scale();
    assertThat(pivot.lower()).isCloseTo(0, within(1e-9));
    assertThat(pivot.upper()).isCloseTo(135, within(1e-9));
    assertThat(pivot.upperShown()).isEqualTo("135°");
    assertThat(pivot.unit()).isEqualTo("°");
    assertThat(pivot.zeroAt()).as("its link stands upright at zero").isCloseTo(90, within(1e-9));
    assertThat(pivot.turning()).as("and pitches down").isEqualTo(Turning.CLOCKWISE);
    assertThat(pivot.softLower()).as("no soft limits inside its stops").isNaN();

    RobotView.Scale arm = view.joint("arm").scale();
    assertThat(arm.zeroAt()).as("a link with no shape points forward").isCloseTo(0, within(1e-9));
    assertThat(arm.turning()).isEqualTo(Turning.COUNTERCLOCKWISE);

    RobotView.Scale elevator = view.joint("elevator").scale();
    assertThat(elevator.upper()).isCloseTo(1.2, within(1e-9));
    assertThat(elevator.unit()).isEqualTo("m");
    assertThat(elevator.zeroAt()).as("it slides up").isCloseTo(90, within(1e-9));
    assertThat(view.joint("elevator").tick("clear").shown()).isEqualTo("0.8128 m");
  }

  /**
   * A joint run at speeds is a speedometer to its free speed: from zero for one that runs one way,
   * and either side of zero for one with an output that runs it backward. Each named output is a
   * tick where it runs the joint.
   */
  @Test
  void aJointRunAtSpeedsIsASpeedometerToItsFreeSpeed() {
    double rollerTop =
        TestRobot.definition().math().freeSpeed(TestRobot.ROLLER) * 60 / (2 * Math.PI);
    RobotView view = intaking(Moments.atRest().at("pivot", 130).running("roller", 1998)).view();
    JointCard roller = view.joint("roller");

    assertThat(roller.scale().unit()).isEqualTo("rpm");
    assertThat(roller.scale().upper()).isCloseTo(rollerTop, within(1e-6));
    assertThat(roller.scale().lower())
        .as("outtaking runs it backward, so zero is in the middle")
        .isCloseTo(-rollerTop, within(1e-6));
    assertThat(roller.tick("intaking").value()).isCloseTo(2000, within(1e-6));
    assertThat(roller.tick("intaking").shown()).isEqualTo("2000 rpm");
    assertThat(roller.tick("intaking").asked()).isEqualTo(Asked.ASKED_FOR);
    assertThat(roller.tick("outtaking").shown()).isEqualTo("-4 V");
    assertThat(roller.tick("outtaking").value())
        .as("a voltage is placed at the speed it would run the roller at unloaded")
        .isCloseTo(-4.0 / 12 * rollerTop, within(1e-6));
    assertThat(roller.needles().now()).isCloseTo(1998, within(1e-6));
    assertThat(roller.needles().goal()).isCloseTo(2000, within(1e-6));
    assertThat(roller.facts().at()).isEqualTo("1998 rpm, intaking");
    assertThat(roller.facts().told()).isEqualTo("intaking, 2000 rpm");
    assertThat(roller.facts().ownedBy()).isEqualTo("Intake");

    JointCard shooter = view.joint("shooter");
    assertThat(shooter.scale().lower()).as("it runs one way").isCloseTo(0, within(1e-9));
    assertThat(shooter.needles().goal()).as("nothing asks it to run").isNaN();
    assertThat(shooter.facts().ownedBy()).isEqualTo("idle, coasting");
    assertThat(view.joint("claw").facts().ownedBy()).isEqualTo("idle, stopped");
  }

  /** Where a joint is, in its own names for its places. */
  @Test
  void aJointSaysWhereItIsAmongItsNamedPlaces() {
    assertThat(Moments.atRest().at("pivot", 130).view().joint("pivot").facts().at())
        .isEqualTo("130°, at out");
    assertThat(Moments.atRest().at("pivot", 60).view().joint("pivot").facts().at())
        .isEqualTo("60°, between in and out");
    assertThat(Moments.atRest().at("pivot", 0.2).view().joint("pivot").facts().at())
        .isEqualTo("0.2°, at in");
    assertThat(Moments.atRest().at("pivot", 134.5).view().joint("pivot").facts().at())
        .isEqualTo("134.5°, above out");

    JointCard homing =
        Moments.atRest()
            .standing("pivot", Moment.Link.ANSWERS, "HOMING: toward its lower stop")
            .view()
            .joint("pivot");
    assertThat(homing.facts().at()).isEqualTo("unknown");
    assertThat(homing.facts().state()).isEqualTo("HOMING: toward its lower stop");
  }

  /** A joint idle, its last move, and its own sensors, each as a fact a person can read. */
  @Test
  void aJointsFactsSayWhoOwnsItHowItsLastMoveEndedAndHowItsSensorsAre() {
    JointCard pivot =
        Moments.atRest()
            .lastMove("pivot", "out: ARRIVED in 0.62 s")
            .standing("pivot", Moment.Link.STALE, "READY")
            .view()
            .joint("pivot");

    assertThat(pivot.facts().ownedBy()).isEqualTo("idle, holding");
    assertThat(pivot.facts().told()).isEqualTo("hold at 1.5°");
    assertThat(pivot.facts().lastMove()).isEqualTo("out: ARRIVED in 0.62 s");
    assertThat(pivot.sensors())
        .extracting(RobotView.OwnSensor::label, RobotView.OwnSensor::reading)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("motor controller", "connected, reading not fresh"),
            org.assertj.core.groups.Tuple.tuple("absolute encoder", "healthy"));
    assertThat(pivot.sensors().get(0).health()).isEqualTo(Health.NEEDS_ATTENTION);
    assertThat(Moments.atRest().view().joint("roller").facts().lastMove()).isEqualTo("none yet");
    assertThat(Moments.atRest().disabled().view().joint("pivot").facts().ownedBy())
        .isEqualTo("idle, robot disabled");
  }

  /** A joint the record has nothing of still has its card, saying so. */
  @Test
  void aJointTheRecordLacksStillHasItsCard() {
    JointCard shooter = Moments.atRest().without("shooter").view().joint("shooter");

    assertThat(shooter.recorded()).isEqualTo(Recorded.NOT_IN_THE_RECORD);
    assertThat(shooter.facts().at()).isEqualTo("not in the record");
    assertThat(shooter.needles().now()).isNaN();
    assertThat(shooter.ticks()).as("what the model says of it is still drawn").hasSize(1);
  }

  // ---- Beside the joints ----

  /** The sensors, gates, gamepads and fired bindings of the moment, as they are. */
  @Test
  void theSensorsGatesGamepadsAndFiredBindingsAreShownAsTheMomentHasThem() {
    RobotView view =
        Moments.atRest()
            .sensor("hopper", Moment.Link.ANSWERS, Moment.Switched.CLEAR, 0.4237)
            .gate("piece in", Moment.Held.DOES_NOT_HOLD, "claw beam clear")
            .gate("ready", Moment.Held.HOLDS)
            .fired("operator right trigger starts Intake while held")
            .view();

    RobotView.SensorRow hopper = view.sensor("hopper");
    assertThat(hopper.kind()).isEqualTo(RobotView.SensorKind.RANGEFINDER);
    assertThat(hopper.reading()).isEqualTo("0.424 m");
    assertThat(hopper.meters()).isCloseTo(0.4237, within(1e-9));
    assertThat(hopper.range()).isCloseTo(1.3, within(1e-9));
    assertThat(hopper.on()).isEqualTo("chassis");
    assertThat(view.gates())
        .extracting(RobotView.GateRow::name, RobotView.GateRow::held, RobotView.GateRow::blocking)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "piece in", Moment.Held.DOES_NOT_HOLD, List.of("claw beam clear")),
            org.assertj.core.groups.Tuple.tuple("ready", Moment.Held.HOLDS, List.of()));
    assertThat(view.fired()).containsExactly("operator right trigger starts Intake while held");
    assertThat(view.enabled()).isEqualTo(Moment.Enabled.ENABLED);
    assertThat(view.mode()).isEqualTo(Moment.Mode.TELEOPERATED);
    assertThat(view.robot()).isEqualTo("test");
    assertThat(RobotView.GamepadRow.of(new Moment.OfGamepad(1, 0b100101, List.of(0.5), 90)).down())
        .containsExactly(0, 2, 5);
  }
}
