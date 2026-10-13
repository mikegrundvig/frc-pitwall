package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.experiment.ByHand;
import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.michaelgrundvig.frc.pitwall.robot.FakeRecord;
import com.michaelgrundvig.frc.pitwall.robot.RobotFeed;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.robot.TestRobot;
import com.michaelgrundvig.frc.pitwall.scene.Scenes;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wpilib.tunable.MockTunableBackend;

/**
 * The Robot view over real HTTP, as a page sees it: the test robot's log taken loop by loop into
 * its session, the view of any loop asked for by its index or its time, and the newest loop pushed
 * to the pages that show the view and to no others.
 */
class RobotViewStreamTest {
  private static final JsonType<Object> JSON = Jsonb.builder().build().type(Object.class);
  private static final long LOOP_NANOS = 20_000_000L;
  private static final Duration WAIT = Duration.ofSeconds(10);

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final FakeRecord record = FakeRecord.ofTheTestRobotAtRest();
  private final RobotSession session = RobotSession.of(TestRobot.definition());
  private final RobotFeed feed = new RobotFeed(session, clock::get);
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
          new Scenes(clock::get, System.out::println),
          session);
  private final Broadcaster broadcaster =
      new Broadcaster(Broadcaster.MAX_STREAMS, Broadcaster.MAILBOX, id -> {});
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<AutoCloseable> open = new ArrayList<>();
  private final PitwallServer server;

  RobotViewStreamTest() throws Exception {
    server = new PitwallServer(page, broadcaster, 0);
    server.start(true);
  }

  @AfterEach
  void stop() throws Exception {
    for (AutoCloseable stream : open) {
      stream.close();
    }
    server.close();
  }

  /** One loop of the robot: its log is taken, with the pivot at an angle, and the page is told. */
  private void loop(double pivotDegrees) {
    clock.addAndGet(LOOP_NANOS);
    record.at(clock.get()).joint("pivot", Math.toRadians(pivotDegrees), 0);
    session.take(record);
    step();
  }

  /** The page's part of a loop, the clock standing still. */
  private void step() {
    broadcaster.periodic(page::sections, null, List.of(feed));
  }

  /** The view of any loop the session holds is asked for by its index or by the time nearest it. */
  @Test
  void aLoopsViewIsAskedForByItsIndexOrItsTime() throws Exception {
    assertThat(get("/api/robot/view").statusCode()).as("before any loop").isEqualTo(404);
    for (int degrees = 0; degrees < 10; degrees++) {
      loop(degrees * 10);
    }

    Map<String, Object> summary = asMap(JSON.fromJson(get("/api/robot").body()));
    assertThat(summary)
        .containsEntry("robot", "test")
        .containsEntry("oldest", 0L)
        .containsEntry("newest", 9L)
        .containsEntry("rememberedLoops", 15_000L)
        .containsEntry("bytesPerLoop", 674L)
        .containsEntry("bytes", 10_110_000L)
        .containsEntry("newestNanos", clock.get())
        .containsKeys("oldestNanos", "meanTakeMicros", "slowestTakeMicros", "texts");

    assertThat(now(get("/api/robot/view"))).as("the newest").isEqualTo(90);
    assertThat(now(get("/api/robot/view?index=3"))).isEqualTo(30);
    long atTheFifth = clock.get() - 4 * LOOP_NANOS + 3_000_000;
    HttpResponse<String> nearest = get("/api/robot/view?at=" + atTheFifth);
    assertThat(now(nearest)).as("the loop nearest a time").isEqualTo(50);
    assertThat(asMap(JSON.fromJson(nearest.body()))).containsEntry("index", 5L);

    assertThat(get("/api/robot/view?index=10").statusCode()).as("not taken yet").isEqualTo(404);
    assertThat(get("/api/robot/view?index=10").body()).contains("holds loops 0 to 9, not 10");
    assertThat(get("/api/robot/view?index=soon").statusCode()).isEqualTo(400);
  }

  /**
   * The session's events are asked for by loops, by times, the newest, from a number on, and one at
   * a time, of some kinds, about one thing, or the problems only.
   */
  @Test
  void theSessionsEventsAreAskedForInRangesAndOneAtATime() throws Exception {
    long started = clock.get() + LOOP_NANOS;
    loop(0);
    record.sensor("claw beam", true, true, Double.NaN);
    loop(0);
    loop(0);
    record.sensor("hopper", false, false, Double.NaN);
    loop(0);
    record.sensor("claw beam", true, false, Double.NaN);
    loop(0);

    Map<String, Object> all = asMap(JSON.fromJson(get("/api/robot/events").body()));
    assertThat(all)
        .containsEntry("found", 3L)
        .containsEntry("oldest", 0L)
        .containsEntry("newest", 4L);
    assertThat(sentences("/api/robot/events"))
        .containsExactly("claw beam tripped", "hopper lost", "claw beam clear");
    Map<String, Object> lost = asMap(asList(all.get("events")).get(1));
    assertThat(lost)
        .containsEntry("number", 1L)
        .containsEntry("loop", 3L)
        .containsEntry("timestampNanos", started + 3 * LOOP_NANOS)
        .containsEntry("kind", "SENSOR_LOST")
        .containsEntry("subject", "SENSOR")
        .containsEntry("about", List.of("hopper"))
        .containsEntry("severity", "PROBLEM");

    assertThat(sentences("/api/robot/events?from=2&to=3")).containsExactly("hopper lost");
    assertThat(sentences("/api/robot/events?toAt=" + (started + LOOP_NANOS)))
        .containsExactly("claw beam tripped");
    assertThat(sentences("/api/robot/events?fromAt=" + (started + 4 * LOOP_NANOS)))
        .containsExactly("claw beam clear");
    assertThat(sentences("/api/robot/events?newest=2"))
        .containsExactly("hopper lost", "claw beam clear");
    assertThat(sentences("/api/robot/events?since=2")).containsExactly("claw beam clear");
    assertThat(sentences("/api/robot/events?after=1")).containsExactly("hopper lost");
    assertThat(sentences("/api/robot/events?before=4&about=claw%20beam"))
        .containsExactly("claw beam tripped");
    assertThat(sentences("/api/robot/events?after=0&kind=SENSOR_CLEARED,SENSOR_LOST"))
        .containsExactly("hopper lost");
    assertThat(sentences("/api/robot/events?problems=true")).containsExactly("hopper lost");
    assertThat(sentences("/api/robot/events?after=4")).isEmpty();

    assertThat(get("/api/robot/events?kind=SURPRISE").statusCode()).isEqualTo(400);
    assertThat(get("/api/robot/events?from=soon").statusCode()).isEqualTo(400);
    assertThat(asMap(JSON.fromJson(get("/api/robot").body())))
        .containsEntry("events", 3L)
        .containsEntry("rememberedEvents", 4096L)
        .containsEntry("eventBytes", 49_152L)
        .containsEntry("startedNanos", started);
  }

  /** The sentences of the events an address answers with, in order. */
  private List<String> sentences(String path) throws Exception {
    HttpResponse<String> answer = get(path);
    assertThat(answer.statusCode()).as(path).isEqualTo(200);
    List<String> sentences = new ArrayList<>();
    for (Object event : asList(asMap(JSON.fromJson(answer.body())).get("events"))) {
      sentences.add(String.valueOf(asMap(event).get("sentence")));
    }
    return sentences;
  }

  @SuppressWarnings("unchecked") // a JSON list
  private static List<Object> asList(@Nullable Object json) {
    return (List<Object>) Objects.requireNonNull(json);
  }

  /**
   * A page that shows the view is sent all of it as it starts, and then only what changes: with the
   * pivot moving, its needle and where it says it is. A page that does not show it is sent none.
   */
  @Test
  void aPageShowingTheViewIsSentItAndThenOnlyWhatChanges() throws Exception {
    loop(10);
    Listener tune = new Listener("/api/events");
    Listener robot = new Listener("/api/events?views=" + RobotFeed.VIEW);
    loopUntil(() -> robot.has("replace") && tune.has("snapshot"));

    assertThat(robot.data("snapshot"))
        .contains("\"robotView\":{\"robot\":\"test\",\"view\":\"robot\",\"joints\":6,");
    assertThat(robot.data("replace")).startsWith("{\"at\":[\"robotView\",\"live\"],\"value\":");
    Map<String, Object> whole = asMap(asMap(JSON.fromJson(robot.data("replace"))).get("value"));
    assertThat(whole.get("order"))
        .isEqualTo(List.of("pivot", "roller", "elevator", "arm", "shooter", "claw"));
    assertThat(number(in(whole, "joints", "pivot", "needles").get("now"))).isEqualTo(10);

    record.fired("operator right trigger starts Intake");
    for (int more = 0; more < 4; more++) {
      loop(40);
    }
    // The loops after the one that moved the pivot send patches too, of the time alone: the patch
    // looked at is the one that says the pivot moved.
    loopUntil(() -> robot.holding("patch", "\"needles\"").isPresent());

    Map<String, Object> live =
        in(
            asMap(JSON.fromJson(robot.holding("patch", "\"needles\"").orElseThrow())),
            "robotView",
            "live");
    assertThat(in(live, "joints").keySet())
        .as("only the joint that moved")
        .containsExactly("pivot");
    assertThat(number(in(live, "joints", "pivot", "needles").get("now"))).isEqualTo(40);
    assertThat(in(live, "joints", "pivot", "facts")).containsEntry("at", "40°");
    assertThat(in(live, "fired"))
        .containsEntry("bindings", List.of("operator right trigger starts Intake"));
    assertThat(tune.count("patch") + tune.count("replace"))
        .as("a page not showing the view")
        .isZero();
  }

  /** A page's stream, as its events arrive. */
  private final class Listener {
    private final List<String[]> events = new CopyOnWriteArrayList<>();

    Listener(String path) throws Exception {
      CompletableFuture<HttpResponse<Stream<String>>> connecting =
          client.sendAsync(
              HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofLines());
      loopUntil(connecting::isDone);
      Stream<String> lines = connecting.get().body();
      open.add(lines::close);
      Thread.ofVirtual().start(() -> read(lines));
    }

    private void read(Stream<String> lines) {
      List<String> event = new ArrayList<>();
      try {
        lines.forEach(
            line -> {
              if (!line.isEmpty()) {
                event.add(line);
                return;
              }
              if (event.size() == 2) {
                events.add(
                    new String[] {
                      event.get(0).substring("event: ".length()),
                      event.get(1).substring("data: ".length())
                    });
              }
              event.clear();
            });
      } catch (UncheckedIOException closed) {
        // The test closed the stream.
      }
    }

    boolean has(String name) {
      return count(name) > 0;
    }

    int count(String name) {
      return (int) events.stream().filter(event -> event[0].equals(name)).count();
    }

    /** The data of the first event of a name that holds some text. */
    Optional<String> holding(String name, String text) {
      return events.stream()
          .filter(event -> event[0].equals(name) && event[1].contains(text))
          .map(event -> event[1])
          .findFirst();
    }

    /** The latest event of a name's data. */
    String data(String name) {
      String found = null;
      for (String[] event : events) {
        if (event[0].equals(name)) {
          found = event[1];
        }
      }
      assertThat(found).as(name).isNotNull();
      return String.valueOf(found);
    }
  }

  /**
   * Runs the page's part of loops, the clock standing still, until the network brings something.
   */
  private void loopUntil(BooleanSupplier done) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!done.getAsBoolean() && System.nanoTime() < deadline) {
      step();
      Thread.onSpinWait();
    }
    assertThat(done.getAsBoolean()).as("it happened within " + WAIT).isTrue();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  /** Where a view's pivot is, in degrees. */
  private static double now(HttpResponse<String> view) {
    assertThat(view.statusCode()).isEqualTo(200);
    return number(in(asMap(JSON.fromJson(view.body())), "joints", "pivot", "needles").get("now"));
  }

  /** A JSON number, which is read as a whole number when it has no fraction. */
  private static double number(@Nullable Object json) {
    return ((Number) Objects.requireNonNull(json)).doubleValue();
  }

  private static Map<String, Object> in(Map<String, Object> object, String... path) {
    Map<String, Object> found = object;
    for (String key : path) {
      found = asMap(found.get(key));
    }
    return found;
  }

  @SuppressWarnings("unchecked") // a JSON object
  private static Map<String, Object> asMap(@Nullable Object json) {
    return (Map<String, Object>) Objects.requireNonNull(json);
  }
}
