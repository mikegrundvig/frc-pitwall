package com.michaelgrundvig.frc.pitwall.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.michaelgrundvig.frc.pitwall.push.Feed;
import com.michaelgrundvig.frc.pitwall.robot.FakeRecord;
import com.michaelgrundvig.frc.pitwall.robot.LogKeys;
import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.robot.Sighting;
import com.michaelgrundvig.frc.pitwall.robot.SightingWords;
import com.michaelgrundvig.frc.pitwall.robot.TestRobot;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.Scenes;
import com.michaelgrundvig.frc.pitwall.scene.StillState;
import com.michaelgrundvig.frc.world.field.Element;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.field.Place;
import com.michaelgrundvig.frc.world.field.Symmetry;
import com.michaelgrundvig.frc.world.field.Tag;
import com.michaelgrundvig.frc.world.field.TagLayout;
import com.michaelgrundvig.frc.world.physical.Material;
import com.michaelgrundvig.frc.world.physical.Polygon2d;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.Joint;
import com.michaelgrundvig.frc.world.robot.RobotDefinition;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation2d;

/**
 * The World view as a function of the robot, the field and one loop's moment: the same drawing
 * whether the loop is the newest, live, or one the page's cursor is on. The moments are taken from
 * records made by hand, as the runtime logs them.
 */
class WorldSceneTest {
  private static final JsonType<Object> JSON =
      Jsonb.builder().serializeNulls(true).build().type(Object.class);
  private static final RobotDefinition ROBOT = TestRobot.definition();
  private static final long LOOP_NANOS = 20_000_000L;
  private static final double[] NO_NUMBERS = new double[0];

  /** A post 0.5 m square and 1.2 m tall, standing on blue's half: the field adds red's. */
  private static final Element POST =
      Element.standing(
          "Post",
          Polygon2d.rectangle(new Translation2d(4, 3), new Translation2d(4.5, 3.5)),
          1.2,
          Material.ALUMINUM);

  /** A start on blue's half, 3 m from the wall and 2 m across, facing the field. */
  private static final Place START = Place.at("Left", new Pose2d(3, 2, Rotation2d.ZERO));

  /** A field with two tags on its far wall, facing back down the field, and a post on each half. */
  private static final FieldModel FIELD =
      FieldModel.builder(
              new TagLayout(
                  "two tags",
                  16.5,
                  8,
                  List.of(
                      Tag.nominal(7, new Pose3d(16.5, 3, 1.4, new Rotation3d(0, 0, Math.PI))),
                      Tag.nominal(8, new Pose3d(16.5, 5, 1.4, new Rotation3d(0, 0, Math.PI))))),
              Symmetry.ROTATED)
          .elements(List.of(POST))
          .starts(List.of(START))
          .build();

  /** The robot program's words: verdict 0 is accepted and 1 the gate, solver 1 a single tag. */
  private static final SightingWords WORDS =
      new SightingWords() {
        @Override
        public String verdict(int verdict, double value, double threshold) {
          if (verdict == 0) {
            return "accepted";
          }
          return String.format(Locale.ROOT, "gate: %.1f > %.1f", value, threshold);
        }

        @Override
        public String solver(int solver) {
          return solver == 1 ? "SINGLE_TAG" : "MULTI_TAG";
        }
      };

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final List<String> said = new ArrayList<>();
  private final Scenes scenes = new Scenes(clock::get, said::add);
  private final RobotSession session =
      RobotSession.of(ROBOT, RobotSession.LOOP_SECONDS, RobotSession.REMEMBERED_SECONDS, WORDS);
  private final FakeRecord record =
      FakeRecord.ofTheTestRobotAtRest().estimate(3, 4, Math.toRadians(30), "TRACKING");
  private final WorldScene world =
      WorldScene.of(
          ROBOT,
          () -> FIELD,
          WorldScene.Truth.IN_THE_RECORD,
          WORDS,
          camera -> Optional.of("Calibrate " + camera));

  /** Takes one more loop of the record, and answers with its moment. */
  private Moment loop() {
    session.take(record.at(clock.addAndGet(LOOP_NANOS)));
    return session.newest().orElseThrow();
  }

  private Feed onAPage() {
    scenes.add(world.scene(session));
    return scenes.feeds().getFirst();
  }

  private static Sighting.Fate rejected() {
    return Sighting.Fate.REJECTED;
  }

  /** The front camera's single-tag measurement of tag 7, as an update judged it. */
  private void frontSawTag7(Sighting.Fate fate, double innovationMeters, double chiSquared) {
    int verdict = fate == Sighting.Fate.ACCEPTED ? 0 : 1;
    record.sightings(
        "front",
        new Sighting[] {new Sighting(7, 0, fate, verdict, 1)},
        FakeRecord.measured(chiSquared, 13.8, innovationMeters, 3.2, 4.1, Math.toRadians(30)));
  }

  private void frontSawNothing() {
    record.sightings("front", new Sighting[0], NO_NUMBERS);
  }

  @Test
  void theFieldTheRobotAndItsCamerasAreDescribedFromTheModel() {
    onAPage();
    String described = JSON.toJson(scenes.descriptions());

    assertThat(described)
        .contains("\"focus\":\"robot\"")
        .contains("\"walls\"", "\"tag.7\":{\"parent\":\"field\",\"pose\":")
        .contains("\"id\":\"tag.7.id\"", "\"text\":\"7\"", "\"tag.8.square\"")
        .contains("\"camera.front\":{\"parent\":\"robot\",\"pose\":")
        .contains(
            "\"id\":\"camera.front.axis\",\"kind\":\"arrow\",\"frame\":\"camera.front\","
                + "\"style\":\"accent\",\"inspect\":true,"
                + "\"reveals\":[\"camera.front.view\",\"camera.front.name\"]")
        .contains("\"camera-views\":{\"title\":\"Camera views\",\"shown\":false")
        .contains("\"camera-names\":{\"title\":\"Camera names\",\"shown\":false")
        .contains("\"camera.front.view\"", "\"range\":[0,3]")
        .contains("\"tag-numbers\":{\"title\":\"Tag numbers\",\"shown\":true")
        .contains("\"wheel-arrows\":{\"title\":\"Wheel arrows\",\"shown\":true")
        .contains("\"sensors\":{\"title\":\"Sensors\",\"shown\":true", "\"sensor.0.block\"")
        .contains("\"wheel.back-right\"", "\"length\":\"speed.back-right\"")
        .contains("\"imu.gyro\":{\"parent\":\"robot\"", "\"imu.gyro.block\"")
        .contains("\"radii\":[\"uncertainty.major\",\"uncertainty.minor\"]")
        .contains("\"truth.robot\"", "\"style\":\"truth dashed\"")
        .contains("\"sight-lines\":{\"title\":\"Sight lines\"")
        .contains(
            "\"legend\":[{\"mark\":\"line\",\"style\":\"ok\",\"text\":\"A camera's"
                + " measurement of a tag that the estimator accepted\"},{\"mark\":\"line\","
                + "\"style\":\"bad dashed\"")
        .contains("\"mark\":\"outline\",\"style\":\"truth dashed\"");
    assertThat(said).isEmpty();
  }

  /**
   * What stands on the field is a layer of its own, shown unless switched off: each block a solid
   * in the structure style, and a click on it says the element's name and size. A tag is drawn so
   * the side it is read from shows: a lit face on the side its pose looks out of, a tick standing
   * out of that face, and a faint back behind it.
   */
  @Test
  void theSceneCarriesWhatStandsOnTheFieldAndWhichWayEachTagFaces() {
    onAPage();
    String described = JSON.toJson(scenes.descriptions());

    assertThat(described)
        .contains("\"elements\":{\"title\":\"Field elements\",\"shown\":true")
        .contains(
            "\"id\":\"element.0.0\",\"kind\":\"mesh\",\"frame\":\"field\","
                + "\"style\":\"structure\",\"inspect\":true")
        .contains("\"id\":\"element.1.0\"")
        .contains("\"mark\":\"fill\",\"style\":\"structure\"")
        // The face is the slab in front of the tag's plane, the back the one behind it, and the
        // tick runs on out of the face along the tag's own x.
        .contains(
            "\"id\":\"tag.7.square\",\"kind\":\"box\",\"frame\":\"tag.7\","
                + "\"style\":\"face\",\"inspect\":true,\"at\":[0.015,0,0]")
        .contains(
            "\"id\":\"tag.7.back\",\"kind\":\"box\",\"frame\":\"tag.7\","
                + "\"style\":\"muted\",\"at\":[-0.015,0,0]")
        .contains(
            "\"id\":\"tag.7.facing\",\"kind\":\"line\",\"frame\":\"tag.7\"," + "\"style\":\"face\"")
        .contains("\"from\":[0.03,0,0],\"to\":[0.14,0,0]")
        .contains("\"mark\":\"fill\",\"style\":\"face\"");
    assertThat(scenes.inspect(WorldScene.ID, "element.1.0").orElseThrow())
        .satisfies(
            post -> {
              assertThat(post.title()).isEqualTo("Post (red)");
              assertThat(post.details())
                  .extracting(Inspection.Detail::label, Inspection.Detail::value)
                  .contains(
                      tuple("Covers", "0.50 m along the field by 0.50 m across"),
                      tuple("Height", "1.20 m"));
            });
  }

  /** The simulator's parts, as a robot's simulation would name them. */
  private static final SimulatedParts SIMULATED =
      new SimulatedParts() {
        @Override
        public Optional<String> camera(String name) {
          return Optional.of("Camera " + name);
        }

        @Override
        public Optional<String> gyro() {
          return Optional.of("Gyro");
        }

        @Override
        public Optional<String> backupImu() {
          return Optional.of("Backup IMU");
        }

        @Override
        public Optional<String> module(int index) {
          return Optional.of("Module " + index);
        }

        @Override
        public Optional<String> robot() {
          return Optional.of("Robot");
        }

        @Override
        public Optional<Inspection.FaultOffer> placingTheRobotAt(Pose2d pose) {
          return Optional.of(
              new Inspection.FaultOffer(
                  "Place the robot here",
                  "Robot",
                  "Placed",
                  Map.of("X", pose.getX(), "Y", pose.getY())));
        }
      };

  /**
   * In the simulator a click on a part that can fail there names the simulator's part it is, whose
   * faults the page then offers: a camera (on its arrow or its view), the gyro, each wheel's
   * module, and on the bumpers the robot itself with the backup IMU, which has no shape. A sensor
   * and a tag are no such part.
   */
  @Test
  void inTheSimulatorAClickOnAPartNamesTheSimulatorsPartItIs() {
    WorldScene simulated =
        WorldScene.of(
            ROBOT,
            () -> FIELD,
            WorldScene.Truth.IN_THE_RECORD,
            WORDS,
            camera -> Optional.empty(),
            SIMULATED);
    scenes.add(simulated.scene(session));

    Map<String, List<String>> partsOf = new LinkedHashMap<>();
    for (String shape :
        List.of(
            "camera.front.axis",
            "camera.front.view",
            "imu.gyro.block",
            "imu.gyro.x",
            "wheel.front-left",
            "wheel.back-right",
            "robot.bumpers",
            "sensor.0.block",
            "tag.7.square")) {
      partsOf.put(shape, scenes.inspect(WorldScene.ID, shape).orElseThrow().components());
    }

    assertThat(partsOf)
        .containsEntry("camera.front.axis", List.of("Camera front"))
        .containsEntry("camera.front.view", List.of("Camera front"))
        .containsEntry("imu.gyro.block", List.of("Gyro"))
        .containsEntry("imu.gyro.x", List.of("Gyro"))
        .containsEntry("wheel.front-left", List.of("Module 0"))
        .containsEntry("wheel.back-right", List.of("Module 3"))
        .containsEntry("robot.bumpers", List.of("Robot", "Backup IMU"))
        .containsEntry("sensor.0.block", List.of())
        .containsEntry("tag.7.square", List.of());
    assertThat(scenes.inspect(WorldScene.ID, "wheel.front-left").orElseThrow().title())
        .isEqualTo("Module front-left");
  }

  /** A robot that isn't simulated names no such part: its page is never offered a fault. */
  @Test
  void onARobotAClickNamesNoPartThatCanFail() {
    onAPage();

    for (String shape :
        List.of("camera.front.axis", "imu.gyro.block", "wheel.front-left", "robot.bumpers")) {
      assertThat(scenes.inspect(WorldScene.ID, shape).orElseThrow().components())
          .as(shape)
          .isEmpty();
    }
  }

  /**
   * Where the robot may start is a layer of its own, shown unless switched off: each start of both
   * alliances is the robot's bumpers as a dashed outline in a frame at the start's pose, with an
   * arrow out of its front and the alliance's name and the start's beside it. A click on one says
   * its name, alliance and pose, and in the simulator offers to place the robot there.
   */
  @Test
  void theSceneCarriesEachStartOfBothAlliancesAndAClickOnOneSaysWhereItIs() {
    WorldScene simulated =
        WorldScene.of(
            ROBOT,
            () -> FIELD,
            WorldScene.Truth.IN_THE_RECORD,
            WORDS,
            camera -> Optional.empty(),
            SIMULATED);
    scenes.add(simulated.scene(session));
    String described = JSON.toJson(scenes.descriptions());

    assertThat(described)
        .contains("\"starts\":{\"title\":\"Starting positions\",\"shown\":true")
        .contains("\"id\":\"start.blue.0.outline\",\"kind\":\"polygon\"")
        .contains("\"style\":\"muted dashed\",\"inspect\":true")
        .contains("\"id\":\"start.blue.0.name\"", "\"text\":\"Blue left\"")
        .contains("\"id\":\"start.red.0.name\"", "\"text\":\"Red left\"")
        .contains("\"mark\":\"outline\",\"style\":\"muted dashed\"");
    assertThat(scenes.inspect(WorldScene.ID, "start.red.0.outline").orElseThrow())
        .satisfies(
            start -> {
              assertThat(start.title()).isEqualTo("Red left start");
              assertThat(start.details())
                  .extracting(Inspection.Detail::label, Inspection.Detail::value)
                  .contains(
                      tuple("Name", "Left"),
                      tuple("Alliance", "Red"),
                      tuple("Where", "x 13.500 m, y 6.000 m"),
                      tuple("Heading", "180.0°"));
              assertThat(start.offers())
                  .containsExactly(
                      new Inspection.FaultOffer(
                          "Place the robot here", "Robot", "Placed", Map.of("X", 13.5, "Y", 6.0)));
            });
    assertThat(scenes.inspect(WorldScene.ID, "start.blue.0.facing"))
        .as("the arrow is no thing to click: the outline is")
        .isEmpty();
  }

  /**
   * A camera's view is one area seen from above, so the page draws its outline on the floor in
   * place of the pyramid's edges, and its name stands at the far end of its axis, 3 m out.
   */
  @Test
  void aCameraViewIsSeenFromAboveAsOneAreaAndNamedAtItsFarEnd() {
    onAPage();
    String described = JSON.toJson(scenes.descriptions());

    assertThat(described)
        .contains("\"id\":\"camera.front.view\",\"kind\":\"frustum\"")
        .contains("\"plan\":true")
        .contains("\"id\":\"camera.front.view.name\",\"kind\":\"label\"")
        .contains("\"text\":\"front\"")
        .contains("\"at\":[3,0,0]");
  }

  /** On a robot, a click on a start says where it is and offers nothing to do. */
  @Test
  void onARobotAClickOnAStartOffersNoPlacing() {
    onAPage();

    Inspection start = scenes.inspect(WorldScene.ID, "start.blue.0.outline").orElseThrow();

    assertThat(start.title()).isEqualTo("Blue left start");
    assertThat(start.offers()).isEmpty();
  }

  /** A real robot's record has no true pose, and its view no layer for one. */
  @Test
  void aRobotWhoseTruePoseNobodyKnowsHasNoTruthLayer() {
    WorldScene real =
        WorldScene.of(
            ROBOT, () -> FIELD, WorldScene.Truth.NOT_KNOWN, WORDS, camera -> Optional.empty());
    scenes.add(real.scene(session));

    assertThat(JSON.toJson(scenes.descriptions())).doesNotContain("truth");
    assertThat(real.draw(loop()).poses()).doesNotContainKey("truth");
  }

  @Test
  void theRobotIsDrawnWhereTheEstimateWasWithItsWheelsTurnedAndDriving() {
    record
        .put(
            LogKeys.ESTIMATED_POSE,
            new double[] {3, 4, 0.05, Math.cos(Math.PI / 12), 0, 0, Math.sin(Math.PI / 12)})
        .put(LogKeys.MODULES, new double[] {2.0, Math.toRadians(45), 0, 0, 0, 0, 0, 0})
        .truth(3.05, 4, Math.toRadians(31));

    StillState state = world.draw(loop());

    assertThat(state.poses().get("robot"))
        .containsExactly(new double[] {3, 4, 0.05, 0, 0, Math.toRadians(30)}, within(1e-6));
    assertThat(state.poses().get("module.front-left"))
        .containsExactly(new double[] {0.2889, 0.2381, 0, 0, 0, Math.toRadians(45)}, within(1e-3));
    assertThat(state.values().get("speed.front-left")).isCloseTo(0.4, within(1e-6));
    assertThat(state.values().get("speed.back-right")).isZero();
    assertThat(state.poses().get("truth"))
        .containsExactly(new double[] {3.05, 4, 0, 0, 0, Math.toRadians(31)}, within(1e-6));
  }

  /** What rides on a joint is drawn where the joint was in that loop, not where the joint rests. */
  @Test
  void aCameraOnAJointIsDrawnWhereTheJointWas() {
    CameraConfig onArm = ROBOT.cameras().get(1);
    Joint arm = onArm.joint().orElseThrow();
    Joint elevator = ROBOT.joints().get(2);
    record.joint("elevator", 0.4, 0).joint("arm", Math.toRadians(20), 0);

    double[] drawn = world.draw(loop()).poses().get("camera.on-arm");

    Pose3d where = ROBOT.math().cameraPose(onArm, Map.of(elevator, 0.4, arm, Math.toRadians(20)));
    assertThat(drawn)
        .containsExactly(
            new double[] {
              where.getX(),
              where.getY(),
              where.getZ(),
              where.getRotation().getX(),
              where.getRotation().getY(),
              where.getRotation().getZ()
            },
            within(1e-9));
    Pose3d atRest = ROBOT.math().cameraPose(onArm, Map.of());
    assertThat(Math.abs(where.getZ() - atRest.getZ())).isGreaterThan(0.1);
    assertThat(world.draw(loop()).poses()).doesNotContainKey("camera.front");
  }

  /**
   * The ellipse reaches two standard deviations along the covariance's own axes, and is drawn as
   * something wrong while the estimate is lost.
   */
  @Test
  void theEllipseIsSizedByTheCovarianceAndDrawnBadWhileTheEstimateIsLost() {
    record.put(LogKeys.COVARIANCE, new double[] {0.04, 0, 0.01, 0.0001});

    StillState tracking = world.draw(loop());

    assertThat(tracking.poses().get("uncertainty"))
        .containsExactly(new double[] {3, 4, 0, 0, 0, 0}, within(1e-6));
    assertThat(tracking.values().get("uncertainty.major")).isCloseTo(0.4, within(1e-6));
    assertThat(tracking.values().get("uncertainty.minor")).isCloseTo(0.2, within(1e-6));
    assertThat(tracking.styles()).isEmpty();

    record.put(LogKeys.RESTS_ON, "LOST");
    assertThat(world.draw(loop()).styles()).containsEntry("uncertainty.ellipse", "bad dashed");
  }

  @Test
  void aSightLineIsDrawnForEachMeasuredTagAsItFared() {
    frontSawTag7(rejected(), 0.8, 45.2);
    Moment refused = loop();

    assertThat(JSON.toJson(world.draw(refused).shapesOfTheMoment()))
        .isEqualTo(
            "{\"sight-lines\":[{\"id\":\"ray.front.7.bad\",\"kind\":\"line\",\"frame\":\"field\","
                + "\"style\":\"bad dashed\",\"inspect\":true,"
                + "\"between\":[\"camera.front\",\"tag.7\"]}]}");
    Inspection why = world.inspect(refused, "ray.front.7.bad").orElseThrow();
    assertThat(why.title()).isEqualTo("front → tag 7");
    assertThat(why.details())
        .extracting(Inspection.Detail::label, Inspection.Detail::value)
        .containsExactly(
            tuple("Verdict", "rejected: gate: 45.2 > 13.8"),
            tuple("Innovation", "0.80 m from the estimate, before this measurement moved it"),
            tuple("χ²", "45.2"),
            tuple("Distance", "13.35 m from the measured pose to the tag"),
            tuple("Measured pose", "x 3.20 m, y 4.10 m, heading 30.0°"),
            tuple("Solved", "single tag from tags 7"));
    assertThat(why.actions()).containsExactly("Calibrate front");

    // A good camera's innovation is millimeters, its χ² hundredths: shown, not rounded to nothing.
    frontSawTag7(Sighting.Fate.ACCEPTED, 0.0058, 0.0189);
    Moment accepted = loop();
    assertThat(JSON.toJson(world.draw(accepted).shapesOfTheMoment()))
        .contains("\"id\":\"ray.front.7.ok\"", "\"style\":\"ok\"");
    assertThat(world.inspect(accepted, "ray.front.7.ok").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("accepted", "6 mm from the estimate, before this measurement moved it", "0.019");
    assertThat(world.inspect(accepted, "ray.front.7.bad")).isEmpty();
  }

  /** A line stays a moment after its measurement, and a loop long after has none. */
  @Test
  void aSightLineIsHeldAMomentAndThenGone() {
    frontSawTag7(Sighting.Fate.ACCEPTED, 0.01, 0.2);
    loop();
    frontSawNothing();

    assertThat(world.draw(loop()).shapesOfTheMoment().get("sight-lines")).hasSize(1);
    Moment later = loop();
    for (int each = 0; each < 20; each++) {
      later = loop();
    }
    assertThat(world.draw(later).shapesOfTheMoment().get("sight-lines")).isEmpty();
  }

  /** A tag the field does not have has nowhere to draw a line to, and an image left out no line. */
  @Test
  void aTagOffTheFieldAndAnImageLeftOutHaveNoLine() {
    double[] numbers = new double[2 * Sighting.NUMBERS];
    record.sightings(
        "front",
        new Sighting[] {
          new Sighting(99, 0, Sighting.Fate.ACCEPTED, 0, 1),
          new Sighting(8, 1, Sighting.Fate.LEFT_OUT, 1, -1)
        },
        numbers);

    assertThat(world.draw(loop()).shapesOfTheMoment().get("sight-lines")).isEmpty();
  }

  /** A measurement given to a localizer that reports nothing of it is drawn as neither. */
  @Test
  void whatALocalizerDoesNotReportIsSaidSoAndNeverDrawn() {
    FakeRecord bare = FakeRecord.ofTheTestRobotAtRest();
    bare.put(LogKeys.ESTIMATED_POSE, FakeRecord.pose(3, 4, 0));
    bare.sightings(
        "front",
        new Sighting[] {new Sighting(7, 0, Sighting.Fate.GIVEN, 2, 1)},
        new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, 3.2, 4.1, 0.5});
    session.take(bare.at(clock.addAndGet(LOOP_NANOS)));
    Moment moment = session.newest().orElseThrow();

    StillState state = world.draw(moment);

    assertThat(state.values()).containsEntry("uncertainty.major", 0.0);
    assertThat(state.values()).containsEntry("uncertainty.minor", 0.0);
    assertThat(world.inspect(moment, "uncertainty.ellipse").orElseThrow().details())
        .extracting(Inspection.Detail::label, Inspection.Detail::value)
        .containsExactly(
            tuple("Based on", "not reported by this localizer"),
            tuple("Position, 1σ", "not reported by this localizer"),
            tuple("Heading, 1σ", "not reported by this localizer"),
            tuple("Cameras", "not reported by this localizer"),
            tuple("Drawn", "nothing: the localizer reports no uncertainty"));
    assertThat(JSON.toJson(state.shapesOfTheMoment()))
        .contains("\"id\":\"ray.front.7.given\"", "\"style\":\"muted\"");
    Inspection given = world.inspect(moment, "ray.front.7.given").orElseThrow();
    assertThat(given.details())
        .extracting(Inspection.Detail::label)
        .containsExactly("Verdict", "Distance", "Measured pose", "Solved");
    assertThat(given.details().get(0).value())
        .isEqualTo("not reported by this localizer: the measurement was given to it");
    assertThat(given.actions()).isEmpty();
  }

  @Test
  void aClickOnTheEllipseSaysHowSureTheEstimateWas() {
    Moment moment = loop();

    assertThat(world.inspect(moment, "uncertainty.ellipse").orElseThrow().details())
        .extracting(Inspection.Detail::label, Inspection.Detail::value)
        .containsExactly(
            tuple("Based on", "tracking"),
            tuple("Position, 1σ", "2.0 cm along x, 3.0 cm along y, 3.0 cm the least sure way"),
            tuple("Heading, 1σ", "0.57°"),
            tuple("Cameras", "12 accepted, 3 rejected; last accepted 0.1 s ago"),
            tuple("Drawn", "two standard deviations across, about the estimate"));
    assertThat(world.inspect(moment, "tag.7.square")).as("the same in every loop").isEmpty();
  }

  /** What never moves answers a click from the robot's definition, the same at any loop. */
  @Test
  void aClickOnACameraASensorOrTheGyroSaysWhatItIsAndWhereItIsMounted() {
    onAPage();

    Inspection camera = scenes.inspect(WorldScene.ID, "camera.front.view").orElseThrow();
    assertThat(camera.title()).isEqualTo("Camera front");
    assertThat(camera.details())
        .extracting(Inspection.Detail::label, Inspection.Detail::value)
        .contains(
            tuple("Mounted", "0.254 m forward, 0.203 m left, 0.508 m up"),
            tuple("Turned", "yaw 20.0°, tilted up 10.0°, roll 0.0°"),
            tuple("Sees", "80° across its diagonal, 1280 × 800, out to 7 m"));
    assertThat(camera.actions()).containsExactly("Calibrate front");

    Inspection beam = scenes.inspect(WorldScene.ID, "sensor.0.block").orElseThrow();
    assertThat(beam.title()).isEqualTo("claw beam");
    assertThat(beam.details())
        .extracting(Inspection.Detail::label, Inspection.Detail::value)
        .contains(tuple("Is", "a beam break"))
        .anySatisfy(detail -> assertThat(detail.toList().get(1).toString()).startsWith("on pivot"));

    Inspection hopper = scenes.inspect(WorldScene.ID, "sensor.1.cone").orElseThrow();
    assertThat(hopper.details())
        .extracting(Inspection.Detail::value)
        .contains("a rangefinder: to 1.30 m, in a 27.0° cone");

    Inspection gyro = scenes.inspect(WorldScene.ID, "imu.gyro.block").orElseThrow();
    assertThat(gyro.title()).isEqualTo("Gyro IMU");
    assertThat(gyro.details())
        .extracting(Inspection.Detail::label)
        .containsExactly("Mounted", "Turned", "Accelerometer", "From");
    assertThat(gyro.details().get(2).value()).startsWith("not yet checked");
    assertThat(scenes.inspect(WorldScene.ID, "tag.7.square").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("x 16.500 m, y 3.000 m, 1.400 m up", "180.0°");
  }

  // ---- On a page: live from the newest loop, and any loop the session holds when asked ----

  @Test
  void liveThePageIsSentTheNewestLoopAndOnlyWhatChanged() {
    Feed feed = onAPage();
    record.put(LogKeys.MODULES, new double[] {2.0, Math.toRadians(45), 0, 0, 0, 0, 0, 0});
    loop();

    String patch = JSON.toJson(feed.patch());

    assertThat(patch)
        .contains("\"robot\":[3,4,30]")
        .contains("\"module.front-left\":[")
        .contains(",45]")
        .contains("\"speed.front-left\":0.4")
        .contains("\"uncertainty\":[3,4,");
    clock.addAndGet(60_000_000L);
    assertThat(JSON.toJson(feed.patch())).as("no new loop, nothing to send").isEqualTo("{}");
    loop();
    clock.addAndGet(60_000_000L);
    assertThat(JSON.toJson(feed.patch())).as("a still robot sends nothing").isEqualTo("{}");
    assertThat(said).isEmpty();
  }

  @Test
  void liveALineIsDrawnAgainOnlyWhenTheLinesChangeAndAClickSaysItsLatest() {
    Feed feed = onAPage();
    frontSawTag7(rejected(), 0.8, 45.2);
    loop();
    assertThat(JSON.toJson(feed.patch())).contains("\"id\":\"ray.front.7.bad\"");

    frontSawTag7(rejected(), 0.9, 51.7);
    loop();
    clock.addAndGet(60_000_000L);
    assertThat(JSON.toJson(feed.patch())).as("the same line").doesNotContain("sight-lines");
    assertThat(scenes.inspect(WorldScene.ID, "ray.front.7.bad").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("rejected: gate: 51.7 > 13.8");

    frontSawNothing();
    for (int each = 0; each < 20; each++) {
      loop();
      clock.addAndGet(60_000_000L);
      feed.patch();
    }
    assertThat(JSON.toJson(feed.sent())).doesNotContain("ray.front.7");
    assertThat(scenes.inspect(WorldScene.ID, "ray.front.7.bad"))
        .as("still answered for a while after")
        .isPresent();
    assertThat(said).isEmpty();
  }

  /** An earlier loop is drawn whole from the session, while live has moved on. */
  @Test
  @SuppressWarnings("unchecked") // the state is a JSON object
  void anEarlierLoopIsDrawnWholeFromTheSession() {
    onAPage();
    frontSawTag7(rejected(), 0.8, 45.2);
    record.put(LogKeys.RESTS_ON, "LOST");
    loop();
    long refused = session.taken() - 1;
    frontSawNothing();
    record.estimate(5, 2, 0, "TRACKING");
    for (int each = 0; each < 30; each++) {
      loop();
    }

    Map<String, Object> then = scenes.stateAt(WorldScene.ID, refused).orElseThrow();
    Map<String, Object> now = scenes.stateAt(WorldScene.ID, session.taken() - 1).orElseThrow();

    assertThat(JSON.toJson(then))
        .contains("\"robot\":[3,4,30]")
        .contains("\"styles\":{\"uncertainty.ellipse\":\"bad dashed\"}")
        .contains("\"sight-lines\":[{\"id\":\"ray.front.7.bad\"")
        .contains("\"liveOnly\":[]");
    assertThat(((Map<String, Object>) then.get("values")))
        .containsKeys("uncertainty.major", "uncertainty.minor");
    assertThat(JSON.toJson(now))
        .contains("\"robot\":[5,2,0]")
        .contains("\"styles\":{}")
        .contains("\"sight-lines\":[]");
    assertThat(scenes.stateAt(WorldScene.ID, session.taken() + 5)).isEmpty();
    assertThat(scenes.stateAt("no-such-scene", refused)).isEmpty();
  }

  @Test
  void aClickAtAnEarlierLoopSaysWhatThatLoopHad() {
    onAPage();
    frontSawTag7(rejected(), 0.8, 45.2);
    loop();
    long refused = session.taken() - 1;
    frontSawNothing();
    record.put(LogKeys.RESTS_ON, "DEAD_RECKONING");
    for (int each = 0; each < 30; each++) {
      loop();
    }
    long later = session.taken() - 1;

    assertThat(scenes.inspectAt(WorldScene.ID, refused, "ray.front.7.bad").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("rejected: gate: 45.2 > 13.8");
    assertThat(scenes.inspectAt(WorldScene.ID, later, "ray.front.7.bad"))
        .as("the line was gone by then")
        .isEmpty();
    assertThat(
            scenes.inspectAt(WorldScene.ID, refused, "uncertainty.ellipse").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("tracking");
    assertThat(
            scenes.inspectAt(WorldScene.ID, later, "uncertainty.ellipse").orElseThrow().details())
        .extracting(Inspection.Detail::value)
        .contains("dead reckoning");
    assertThat(scenes.inspectAt(WorldScene.ID, refused, "camera.front.axis").orElseThrow().title())
        .as("a camera's mount is the same in every loop")
        .isEqualTo("Camera front");
    assertThat(scenes.inspectAt(WorldScene.ID, refused, "tag.7.square")).isPresent();
    assertThat(scenes.inspectAt(WorldScene.ID, refused, "no-such-shape")).isEmpty();
  }
}
