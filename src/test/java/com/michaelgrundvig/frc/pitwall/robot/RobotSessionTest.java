package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The session: each loop's record taken into a moment ({@link Extraction}), and the moments kept in
 * a ring of fixed size ({@link SessionMemory}). The records are made by hand ({@link FakeRecord}),
 * with the keys the runtime logs.
 */
class RobotSessionTest {
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest();
  private final RobotSession session = RobotSession.of(TestRobot.definition());

  private Moment taken() {
    session.take(record);
    return session.newest().orElseThrow();
  }

  /** Everything the view shows of a loop comes out of the record under the runtime's keys. */
  @Test
  void aLoopsMomentHoldsWhatTheRecordSaysUnderTheRuntimesKeys() {
    String pivot = LogKeys.jointOutputs("pivot");
    record
        .at(4_020_000_000L)
        .joint("pivot", Math.toRadians(62), Math.toRadians(300))
        .put(LogKeys.joint("pivot") + LogKeys.APPLIED_VOLTS, 7.5)
        .put(pivot + LogKeys.COMMAND, "Intake")
        .put(pivot + LogKeys.GOAL, Math.toRadians(130))
        .put(pivot + LogKeys.GOAL_NAME, "out")
        .put(pivot + LogKeys.SETPOINT, Math.toRadians(63))
        .put(pivot + LogKeys.SETPOINT_VELOCITY, Math.toRadians(310))
        .put(pivot + LogKeys.OUTCOME, "in: ARRIVED in 0.50 s")
        .put(pivot + LogKeys.ABSOLUTE_AT, Math.toRadians(62.2))
        .sensor("claw beam", true, true, Double.NaN)
        .sensor("hopper", false, false, Double.NaN)
        .gate("piece in", true)
        .gate("ready to feed", false, "not aimed at the Hub", "shooter not at goal", "a third")
        .put(LogKeys.FIRED, new String[] {"operator right trigger starts Intake while held"})
        .put(LogKeys.gamepad(1) + LogKeys.BUTTONS, 0b1000_0000L)
        .put(LogKeys.gamepad(1) + LogKeys.AXES, new float[] {0, 0.25f, 0, 0, 0, 1})
        .put(LogKeys.gamepad(1) + LogKeys.POVS, new long[] {90});

    Moment moment = taken();

    assertThat(moment.timestampNanos()).isEqualTo(4_020_000_000L);
    assertThat(moment.enabled()).isEqualTo(Moment.Enabled.ENABLED);
    assertThat(moment.mode()).isEqualTo(Moment.Mode.TELEOPERATED);
    assertThat(moment.joints())
        .extracting(Moment.OfJoint::label)
        .containsExactly("pivot", "roller", "elevator", "arm", "shooter", "claw");
    Moment.OfJoint joint = Objects.requireNonNull(moment.joint("pivot"));
    assertThat(joint.link()).isEqualTo(Moment.Link.ANSWERS);
    assertThat(joint.position()).as("exactly, as the rules judge it").isEqualTo(Math.toRadians(62));
    assertThat(joint.velocity()).isEqualTo(Math.toRadians(300));
    assertThat(joint.goal()).isEqualTo(Math.toRadians(130));
    assertThat(joint.appliedVolts()).isCloseTo(7.5, within(1e-6));
    assertThat(joint.setpoint()).isCloseTo(Math.toRadians(63), within(1e-6));
    assertThat(joint.setpointVelocity()).isCloseTo(Math.toRadians(310), within(1e-6));
    assertThat(joint.absoluteAt()).isCloseTo(Math.toRadians(62.2), within(1e-6));
    assertThat(joint.encoder()).isEqualTo(Moment.Encoder.HEALTHY);
    assertThat(joint.state()).isEqualTo("READY");
    assertThat(joint.command()).isEqualTo("Intake");
    assertThat(joint.goalName()).isEqualTo("out");
    assertThat(joint.outcome()).isEqualTo("in: ARRIVED in 0.50 s");

    assertThat(moment.sensor("claw beam"))
        .isEqualTo(
            new Moment.OfSensor(
                "claw beam", Moment.Link.ANSWERS, Moment.Switched.TRIPPED, Double.NaN));
    assertThat(moment.sensor("hopper"))
        .extracting(Moment.OfSensor::link)
        .isEqualTo(Moment.Link.LOST);
    assertThat(moment.gates())
        .containsExactly(
            new Moment.OfGate("piece in", Moment.Held.HOLDS, List.of()),
            new Moment.OfGate(
                "ready to feed",
                Moment.Held.DOES_NOT_HOLD,
                List.of("not aimed at the Hub", "shooter not at goal")));
    assertThat(moment.fired()).containsExactly("operator right trigger starts Intake while held");
    Moment.OfGamepad operator = moment.gamepads().get(1);
    assertThat(operator.isDown(7)).isTrue();
    assertThat(operator.isDown(6)).isFalse();
    assertThat(operator.axes()).containsExactly(0.0, 0.25, 0.0, 0.0, 0.0, 1.0);
    assertThat(operator.pov()).isEqualTo(90);
    assertThat(moment.gamepads().get(0).pov()).isEqualTo(-1);
  }

  /** A joint that does not answer, or whose encoder is unwell, is told as a named state. */
  @Test
  void aJointsLinkAndItsEncodersHealthAreReadFromItsInputs() {
    String pivot = LogKeys.joint("pivot");
    record.put(pivot + LogKeys.FRESH, false).put(pivot + LogKeys.MAGNET_BAD, true);
    assertThat(taken().joint("pivot"))
        .extracting(Moment.OfJoint::link, Moment.OfJoint::encoder)
        .containsExactly(Moment.Link.STALE, Moment.Encoder.MAGNET_BAD);

    record.put(pivot + LogKeys.CONNECTED, false).put(pivot + LogKeys.ABSOLUTE_CONNECTED, false);
    assertThat(taken().joint("pivot"))
        .extracting(Moment.OfJoint::link, Moment.OfJoint::encoder)
        .containsExactly(Moment.Link.LOST, Moment.Encoder.LOST);
  }

  /** A joint or a sensor the robot program does not log is left out of the moment. */
  @Test
  void whatTheRecordLacksIsLeftOutOfTheMoment() {
    FakeRecord sparse =
        new FakeRecord().put(LogKeys.ENABLED, false).joint("pivot", 0, 0).at(20_000_000);
    session.take(sparse);

    Moment moment = session.newest().orElseThrow();
    assertThat(moment.enabled()).isEqualTo(Moment.Enabled.DISABLED);
    assertThat(moment.mode()).isEqualTo(Moment.Mode.UNKNOWN);
    assertThat(moment.joints()).extracting(Moment.OfJoint::label).containsExactly("pivot");
    assertThat(moment.sensors()).isEmpty();
    assertThat(moment.gates()).isEmpty();
    assertThat(moment.fired()).isEmpty();
  }

  /**
   * The ring keeps a fixed number of loops: the newest replaces the oldest, a loop keeps its index,
   * and a loop is found by its index or by the time nearest it.
   */
  @Test
  void theRingKeepsTheLastLoopsEachByItsIndexAndItsTime() {
    RobotSession brief = RobotSession.of(TestRobot.definition(), 0.02, 0.1);
    assertThat(brief.capacity()).isEqualTo(5);
    assertThat(brief.newest()).isEmpty();
    assertThat(brief.nearest(0)).isEqualTo(-1);

    for (int loop = 0; loop < 12; loop++) {
      record.at(loop * 20_000_000L).joint("pivot", Math.toRadians(loop), 0);
      brief.take(record);
    }

    assertThat(brief.taken()).isEqualTo(12);
    assertThat(brief.oldest()).isEqualTo(7);
    assertThat(brief.moment(6)).as("replaced since").isEmpty();
    assertThat(brief.moment(12)).as("not taken yet").isEmpty();
    for (long index = 7; index < 12; index++) {
      Moment moment = brief.moment(index).orElseThrow();
      assertThat(moment.timestampNanos()).isEqualTo(index * 20_000_000L);
      Moment.OfJoint pivot = Objects.requireNonNull(moment.joint("pivot"));
      assertThat(pivot.position()).isEqualTo(Math.toRadians(index));
    }
    assertThat(brief.nearest(9 * 20_000_000L + 9_000_000)).isEqualTo(9);
    assertThat(brief.nearest(9 * 20_000_000L + 11_000_000)).isEqualTo(10);
    assertThat(brief.nearest(0)).as("before all it holds: the oldest").isEqualTo(7);
    assertThat(brief.nearest(Long.MAX_VALUE)).as("after all it holds: the newest").isEqualTo(11);
    assertThat(brief.view(11)).isPresent();
    assertThat(brief.view(3)).isEmpty();
  }

  /** A firing lasts one loop; the session finds the latest within about a second before a loop. */
  @Test
  void theLatestFiringStaysFoundForAboutASecond() {
    for (int loop = 0; loop < 100; loop++) {
      boolean fires = loop == 10;
      record
          .at(loop * 20_000_000L)
          .put(LogKeys.FIRED, fires ? new String[] {"driver A starts Shoot"} : new String[0]);
      session.take(record);
    }

    assertThat(session.lastFiring(9)).as("before it fired").isEmpty();
    assertThat(session.lastFiring(10).orElseThrow().fired())
        .containsExactly("driver A starts Shoot");
    assertThat(session.lastFiring(60).orElseThrow().timestampNanos()).isEqualTo(200_000_000);
    assertThat(session.lastFiring(61)).as("more than fifty loops on").isEmpty();
  }

  /**
   * The memory is taken once and never grows: a loop of the test robot takes a fixed number of
   * bytes, five minutes of them are kept, and no more gates or texts are kept than there is room
   * for, however many the record has.
   */
  @Test
  void itsMemoryIsFixedHoweverMuchTheRecordHas() {
    assertThat(session.capacity()).as("five minutes at 50 loops a second").isEqualTo(15_000);
    // Six joints and two sensors: 20 doubles, 36 floats, 87 shorts and 3 longs.
    assertThat(session.bytesPerMoment()).isEqualTo(20 * 8 + 36 * 4 + 87 * 2 + 3 * 8);
    assertThat(session.bytes()).isEqualTo(502L * 15_000);

    for (int gate = 0; gate < SessionMemory.GATES + 3; gate++) {
      record.gate("gate " + gate, true);
    }
    for (int loop = 0; loop < Words.MOST + 50; loop++) {
      record.put(LogKeys.jointOutputs("pivot") + LogKeys.OUTCOME, "move " + loop);
      session.take(record);
    }

    Moment moment = session.newest().orElseThrow();
    assertThat(moment.gates()).hasSize(SessionMemory.GATES);
    assertThat(session.gatesNotKept()).isEqualTo(3);
    assertThat(session.texts()).isEqualTo(session.mostTexts());
    Moment.OfJoint pivot = Objects.requireNonNull(moment.joint("pivot"));
    assertThat(pivot.outcome()).isEqualTo(Words.TOO_MANY);
    assertThat(session.meanTakeMicros()).isPositive();
    assertThat(session.slowestTakeMicros()).isGreaterThanOrEqualTo(session.meanTakeMicros());
  }

  /** A long text is kept short, so the table of texts is bounded in bytes as well as in number. */
  @Test
  void aLongTextIsKeptShort() {
    record.put(LogKeys.jointOutputs("pivot") + LogKeys.STATE, "FAULTED: " + "x".repeat(500));

    Moment.OfJoint pivot = Objects.requireNonNull(taken().joint("pivot"));
    assertThat(pivot.state()).hasSize(Words.LONGEST + 1).endsWith("…");
  }
}
