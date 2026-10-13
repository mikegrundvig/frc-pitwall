package com.michaelgrundvig.frc.pitwall.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where the robot was, taken out of each loop's record into the session's moments: the estimate,
 * the truth, the wheels and each camera's tags, as the World view draws them.
 */
class WorldMomentTest {
  private static final long LOOP_NANOS = 20_000_000L;
  private static final double[] NO_NUMBERS = new double[0];

  private final RobotSession session = RobotSession.of(TestRobot.definition());
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest();
  private long loops;

  private Moment loop() {
    session.take(record.at(1_000_000_000L + loops++ * LOOP_NANOS));
    return session.newest().orElseThrow();
  }

  private static Sighting accepted(int tag) {
    return new Sighting(tag, 0, Sighting.Fate.ACCEPTED, 0, 1);
  }

  private static Sighting rejected(int tag) {
    return new Sighting(tag, 0, Sighting.Fate.REJECTED, 1, 1);
  }

  @Test
  void aRecordThatSaysNothingOfTheWorldHasNoEstimateNoTruthAndNoTags() {
    Moment.OfWorld world = loop().world();

    assertThat(world.estimate()).isEmpty();
    assertThat(world.truth()).isEmpty();
    assertThat(world.modules()).isEmpty();
    assertThat(world.cameras())
        .extracting(Moment.OfCamera::name)
        .containsExactly("front", "on-arm");
    assertThat(world.cameras()).allSatisfy(camera -> assertThat(camera.sightings()).isEmpty());
  }

  @Test
  void theEstimateIsTakenWithHowSureItIsWhatItRestsOnAndHowTheCamerasFared() {
    record
        .estimate(3, 4, Math.toRadians(30), "DEAD_RECKONING")
        .put(LogKeys.RECOVERIES, 2L)
        .put(LogKeys.PLACEMENTS, 1L)
        .put(LogKeys.PLACED_BECAUSE, "placed on Center")
        .truth(3.05, 4, Math.toRadians(31))
        .put(LogKeys.MODULES, new double[] {1.5, 0.25, 1.5, 0.25, 1.4, 0.2, 1.4, 0.2});

    Moment.OfWorld world = loop().world();

    Moment.OfEstimate estimate = world.estimate().orElseThrow();
    assertThat(estimate.pose().getX()).isCloseTo(3, within(1e-6));
    assertThat(estimate.pose().getY()).isCloseTo(4, within(1e-6));
    assertThat(estimate.pose().getRotation().getZ()).isCloseTo(Math.toRadians(30), within(1e-6));
    assertThat(estimate.rests()).isEqualTo(Moment.Rests.DEAD_RECKONING);
    Moment.Spread spread = estimate.spread().orElseThrow();
    assertThat(spread.xx()).isCloseTo(0.0004, within(1e-9));
    assertThat(spread.xy()).isCloseTo(0.0001, within(1e-9));
    assertThat(spread.yy()).isCloseTo(0.0009, within(1e-9));
    assertThat(estimate.tally().orElseThrow().accepted()).isEqualTo(12);
    assertThat(estimate.tally().orElseThrow().rejected()).isEqualTo(3);
    assertThat(estimate.tally().orElseThrow().visionAgeSeconds()).isCloseTo(0.1, within(1e-6));
    assertThat(estimate.recoveries()).isEqualTo(2);
    assertThat(estimate.placements()).isEqualTo(1);
    assertThat(estimate.placedBecause()).isEqualTo("placed on Center");
    assertThat(world.truth().orElseThrow().getX()).isCloseTo(3.05, within(1e-6));
    assertThat(world.truth().orElseThrow().getRotation().getZ())
        .isCloseTo(Math.toRadians(31), within(1e-6));
    assertThat(world.modules()).hasSize(4);
    assertThat(world.modules().get(2).metersPerSecond()).isCloseTo(1.4, within(1e-6));
    assertThat(world.modules().get(2).radians()).isCloseTo(0.2, within(1e-6));
  }

  /** A localizer that reports only a pose logs only a pose, and nothing is made up beside it. */
  @Test
  void whatALocalizerDoesNotReportIsAbsentFromTheMoment() {
    record.put(LogKeys.ESTIMATED_POSE, FakeRecord.pose(1, 2, 0));

    Moment.OfEstimate estimate = loop().world().estimate().orElseThrow();

    assertThat(estimate.spread()).isEmpty();
    assertThat(estimate.tally()).isEmpty();
    assertThat(estimate.rests()).isEqualTo(Moment.Rests.NOT_REPORTED);
    assertThat(estimate.recoveries()).isEqualTo(-1);
  }

  @Test
  void aCamerasTagsAreTakenWithWhatBecameOfEachAndTheirNumbers() {
    double[] numbers = new double[2 * Sighting.NUMBERS];
    System.arraycopy(FakeRecord.measured(1.2, 13.8, 0.04, 3.1, 4.0, 0.5), 0, numbers, 0, 7);
    System.arraycopy(FakeRecord.measured(45.2, 13.8, 0.9, 3.9, 4.2, 0.6), 0, numbers, 7, 7);
    record.sightings("front", new Sighting[] {accepted(7), rejected(8)}, numbers);

    Moment.OfCamera front = loop().world().cameras().get(0);

    assertThat(front.dropped()).isZero();
    assertThat(front.sightings()).extracting(Moment.Sighted::tag).containsExactly(8, 7);
    Moment.Sighted refused = front.sightings().get(0);
    assertThat(refused.fate()).isEqualTo(Sighting.Fate.REJECTED);
    assertThat(refused.verdict()).isEqualTo(1);
    assertThat(refused.solver()).isEqualTo(1);
    assertThat(refused.loopsAgo()).isZero();
    assertThat(refused.value()).isCloseTo(45.2, within(1e-4));
    assertThat(refused.threshold()).isCloseTo(13.8, within(1e-4));
    assertThat(refused.innovationMeters()).isCloseTo(0.9, within(1e-6));
    assertThat(refused.measuredX()).isCloseTo(3.9, within(1e-6));
    assertThat(refused.measuredHeading()).isCloseTo(0.6, within(1e-6));
  }

  /**
   * A tag a camera measured stays with the loops after it for a moment, as a sight line that does
   * not flicker between images, and then goes. A newer measurement of the same tag replaces it.
   */
  @Test
  void aMeasuredTagIsHeldForAMomentAndThenGone() {
    double[] numbers = FakeRecord.measured(1.2, 13.8, 0.04, 3.1, 4.0, 0.5);
    record.sightings("front", new Sighting[] {accepted(7)}, numbers);
    loop();
    record.sightings("front", new Sighting[0], NO_NUMBERS);

    Moment next = loop();
    assertThat(next.world().cameras().get(0).sightings())
        .extracting(Moment.Sighted::tag, Moment.Sighted::loopsAgo)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(7, 1));

    Moment held = next;
    for (int each = 2; each * LOOP_NANOS <= WorldMemory.HELD_NANOS; each++) {
      held = loop();
    }
    assertThat(held.world().cameras().get(0).sightings()).hasSize(1);
    assertThat(loop().world().cameras().get(0).sightings()).isEmpty();

    record.sightings("front", new Sighting[] {accepted(7)}, numbers);
    loop();
    record.sightings("front", new Sighting[] {rejected(7)}, numbers);
    assertThat(loop().world().cameras().get(0).sightings())
        .as("the newest measurement of a tag is the one that stands")
        .extracting(Moment.Sighted::fate)
        .containsExactly(Sighting.Fate.REJECTED);
  }

  /** A tag whose image was left out before it was measured belongs to its own loop only. */
  @Test
  void aTagThatWasLeftOutIsNotHeld() {
    double[] numbers = new double[Sighting.NUMBERS];
    java.util.Arrays.fill(numbers, Double.NaN);
    record.sightings(
        "front", new Sighting[] {new Sighting(7, 0, Sighting.Fate.LEFT_OUT, 9, -1)}, numbers);
    Moment seen = loop();
    record.sightings("front", new Sighting[0], NO_NUMBERS);

    assertThat(seen.world().cameras().get(0).sightings()).hasSize(1);
    assertThat(seen.world().cameras().get(0).sightings().get(0).measured()).isFalse();
    assertThat(seen.world().cameras().get(0).sightings().get(0).solver()).isEqualTo(-1);
    assertThat(loop().world().cameras().get(0).sightings()).isEmpty();
  }

  @Test
  void aCamerasTagsPastTheFirstFewOfALoopAreCountedAndNotKept() {
    int seen = WorldMemory.SIGHTINGS + 2;
    Sighting[] sighted = new Sighting[seen];
    for (int each = 0; each < seen; each++) {
      sighted[each] = accepted(each + 1);
    }
    record.sightings("on-arm", sighted, new double[seen * Sighting.NUMBERS]);

    Moment.OfCamera onArm = loop().world().cameras().get(1);

    assertThat(onArm.sightings()).hasSize(WorldMemory.SIGHTINGS);
    assertThat(onArm.dropped()).isEqualTo(2);
    assertThat(List.copyOf(onArm.sightings()))
        .extracting(Moment.Sighted::tag)
        .containsExactly(4, 3, 2, 1);
  }
}
