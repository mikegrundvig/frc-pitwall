package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.experiment.ByHand;
import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.michaelgrundvig.frc.pitwall.robot.FakeRecord;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.robot.Sighting;
import com.michaelgrundvig.frc.pitwall.robot.SightingWords;
import com.michaelgrundvig.frc.pitwall.robot.TestRobot;
import com.michaelgrundvig.frc.pitwall.scene.Scenes;
import com.michaelgrundvig.frc.pitwall.world.WorldScene;
import com.michaelgrundvig.frc.world.field.FieldModel;
import com.michaelgrundvig.frc.world.field.Symmetry;
import com.michaelgrundvig.frc.world.field.Tag;
import com.michaelgrundvig.frc.world.field.TagLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.tunable.MockTunableBackend;

/**
 * The World view at the page's cursor, over real HTTP: the scene as any loop of the robot's session
 * had it, asked for by the loop's index, as the page asks when its cursor is not live.
 */
class WorldAtTheCursorTest {
  private static final long LOOP_NANOS = 20_000_000L;

  private static final FieldModel FIELD =
      FieldModel.builder(
              new TagLayout(
                  "one tag",
                  16.5,
                  8,
                  List.of(Tag.nominal(7, new Pose3d(16.5, 3, 1.4, new Rotation3d(0, 0, Math.PI))))),
              Symmetry.ROTATED)
          .build();

  private static final SightingWords WORDS =
      new SightingWords() {
        @Override
        public String verdict(int verdict, double value, double threshold) {
          return String.format(Locale.ROOT, "gate: %.1f > %.1f", value, threshold);
        }

        @Override
        public String solver(int solver) {
          return "SINGLE_TAG";
        }
      };

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest();
  private final RobotSession session =
      RobotSession.of(
          TestRobot.definition(),
          RobotSession.LOOP_SECONDS,
          RobotSession.REMEMBERED_SECONDS,
          WORDS);
  private final Scenes scenes = new Scenes(clock::get, System.out::println);
  private final TunableTee tunables =
      new TunableTee(new MockTunableBackend(), () -> null, inputs -> {}, false);
  private final Page page =
      new Page(
          new Status().add("Robot", () -> "test"),
          new Actions(inputs -> {}),
          new Tuning("test", tunables::configs, tunables::pathOf, true, inputs -> {}, view -> {}),
          tunables,
          new ByHand(clock::get).experiments(),
          () -> "",
          null,
          new Devices(clock::get),
          scenes,
          session);
  private final Broadcaster broadcaster =
      new Broadcaster(Broadcaster.MAX_STREAMS, Broadcaster.MAILBOX, id -> {});
  private final HttpClient client = HttpClient.newHttpClient();
  private final PitwallServer server;

  /** What the estimate rests on in the loops to come, by its name in the log. */
  private String restsOn = "TRACKING";

  WorldAtTheCursorTest() throws Exception {
    scenes.add(
        WorldScene.of(
                TestRobot.definition(),
                () -> FIELD,
                WorldScene.Truth.IN_THE_RECORD,
                WORDS,
                camera -> Optional.empty())
            .scene(session));
    server = new PitwallServer(page, broadcaster, 0);
    server.start(true);
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  /** One loop of the robot, with the estimate along x and the truth beside it. */
  private void loop(double x) {
    record.estimate(x, 4, 0, restsOn).truth(x + 0.5, 4, 0);
    session.take(record.at(clock.addAndGet(LOOP_NANOS)));
  }

  private HttpResponse<String> get(String path) throws Exception {
    URI uri = URI.create("http://127.0.0.1:" + server.port() + path);
    return client.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theSceneAtALoopIsAskedForByTheLoopsIndex() throws Exception {
    for (int each = 0; each < 5; each++) {
      loop(1 + each);
    }
    record.sightings(
        "front",
        new Sighting[] {new Sighting(7, 0, Sighting.Fate.REJECTED, 1, 1)},
        FakeRecord.measured(45.2, 13.8, 0.8, 6.2, 4.1, 0));
    restsOn = "LOST";
    loop(6);

    HttpResponse<String> third = get("/api/scenes/world/state?index=2");
    HttpResponse<String> sixth = get("/api/scenes/world/state?index=5");

    assertThat(third.statusCode()).isEqualTo(200);
    assertThat(third.body())
        .contains("\"index\":2", "\"timestampNanos\":1060000000")
        .contains("\"robot\":[3,4,0]", "\"truth\":[3.5,4,0]")
        .contains("\"sight-lines\":[]", "\"liveOnly\":[]");
    assertThat(sixth.body())
        .contains("\"robot\":[6,4,0]")
        .contains("\"uncertainty.ellipse\":\"bad dashed\"")
        .contains("\"id\":\"ray.front.7.bad\"");
  }

  /** A click names the loop the page shows, and is answered from that loop, in live's words. */
  @Test
  void aClickAtALoopSaysWhatThatLoopHad() throws Exception {
    loop(1);
    record.sightings(
        "front",
        new Sighting[] {new Sighting(7, 0, Sighting.Fate.REJECTED, 1, 1)},
        FakeRecord.measured(45.2, 13.8, 0.8, 6.2, 4.1, 0));
    loop(2);
    record.sightings("front", new Sighting[0], new double[0]);
    restsOn = "DEAD_RECKONING";
    for (int each = 0; each < 30; each++) {
      loop(3);
    }

    HttpResponse<String> line = get("/api/scenes/world/inspect/ray.front.7.bad?index=1");
    assertThat(line.statusCode()).isEqualTo(200);
    assertThat(line.body())
        .contains("\"title\":\"front → tag 7\"")
        .contains("\"value\":\"rejected: gate: 45.2 > 13.8\"")
        .contains("0.80 m from the estimate, before this measurement moved it");
    assertThat(get("/api/scenes/world/inspect/ray.front.7.bad?index=31").statusCode())
        .as("the line was gone by then")
        .isEqualTo(404);
    assertThat(get("/api/scenes/world/inspect/uncertainty.ellipse?index=1").body())
        .contains("\"value\":\"tracking\"");
    assertThat(get("/api/scenes/world/inspect/uncertainty.ellipse?index=31").body())
        .contains("\"value\":\"dead reckoning\"");
    assertThat(get("/api/scenes/world/inspect/uncertainty.ellipse").body())
        .as("with no loop named, the newest")
        .contains("\"value\":\"dead reckoning\"");
    assertThat(get("/api/scenes/world/inspect/camera.front.axis?index=1").body())
        .as("a camera's mount is the same in every loop")
        .contains("\"title\":\"Camera front\"");
    assertThat(get("/api/scenes/world/inspect/tag.7.square?index=99").statusCode())
        .as("a tag says the same of any loop")
        .isEqualTo(200);
  }

  @Test
  void aLoopTheSessionDoesNotHoldAndASceneThatIsNotThereAreNotFound() throws Exception {
    loop(1);

    assertThat(get("/api/scenes/world/state?index=9").statusCode()).isEqualTo(404);
    assertThat(get("/api/scenes/world/state?index=-1").statusCode()).isEqualTo(404);
    assertThat(get("/api/scenes/elsewhere/state?index=0").statusCode()).isEqualTo(404);
    assertThat(get("/api/scenes/world/state").statusCode()).as("no loop named").isEqualTo(400);
    assertThat(get("/api/scenes/world/state?index=soon").statusCode()).isEqualTo(400);
  }
}
