package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.experiment.ByHand;
import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wpilib.tunable.MockTunableBackend;

/**
 * The server's one route table against the API the server's documentation lists: every route listed
 * is in the table once, and the table has no route the list doesn't have. A route left out of the
 * table, or one added without its row here, fails this test by name.
 */
class RouteTableTest {
  /** A request the API answers: its method, and a path it answers at. */
  record Listed(String method, String path) {}

  /** Every route of the API, as PitwallServer's documentation lists them. */
  private static final List<Listed> API =
      List.of(
          new Listed("GET", "/"),
          new Listed("GET", "/page/tune.js"),
          new Listed("GET", "/page/pitwall.css"),
          new Listed("GET", "/uPlot.iife.min.js"),
          new Listed("GET", "/uPlot.min.css"),
          new Listed("GET", "/three/three.module.min.js"),
          new Listed("GET", "/three/three.core.min.js"),
          new Listed("GET", "/three/OrbitControls.js"),
          new Listed("GET", "/api/events"),
          new Listed("PUT", "/api/views"),
          new Listed("GET", "/api/status"),
          new Listed("GET", "/api/actions"),
          new Listed("POST", "/api/actions"),
          new Listed("GET", "/api/tuning"),
          new Listed("PATCH", "/api/tuning"),
          new Listed("GET", "/api/configs"),
          new Listed("GET", "/api/tunables"),
          new Listed("GET", "/api/values"),
          new Listed("PATCH", "/api/values"),
          new Listed("GET", "/api/changes"),
          new Listed("DELETE", "/api/changes"),
          new Listed("GET", "/api/wpilib-tunables"),
          new Listed("PATCH", "/api/wpilib-tunables"),
          new Listed("GET", "/api/experiments"),
          new Listed("POST", "/api/experiments"),
          new Listed("GET", "/api/run"),
          new Listed("POST", "/api/heartbeat"),
          new Listed("GET", "/api/devices"),
          new Listed("GET", "/api/devices/vision-front/downloads/settings"),
          new Listed("POST", "/api/devices/venue/uploads/survey"),
          new Listed("POST", "/api/devices/venue/choices/venue"),
          new Listed("POST", "/api/devices/venue/presses/keep"),
          new Listed("GET", "/api/robot"),
          new Listed("GET", "/api/robot/view"),
          new Listed("GET", "/api/robot/events"),
          new Listed("GET", "/api/scenes/world/inspect/robot.bumpers"),
          new Listed("GET", "/api/scenes/world/state"),
          new Listed("GET", "/api/simulator"),
          new Listed("PATCH", "/api/simulator"),
          new Listed("POST", "/api/simulator/step"),
          new Listed("POST", "/api/simulator/stop"),
          new Listed("PUT", "/api/simulator/joysticks"),
          new Listed("POST", "/api/simulator/faults"),
          new Listed("DELETE", "/api/simulator/faults"),
          new Listed("POST", "/api/simulator/scenarios"));

  private final TunableTee tunables =
      new TunableTee(new MockTunableBackend(), () -> null, inputs -> {}, false);
  private final Page page =
      new Page(
          new Status(),
          new Actions(inputs -> {}),
          new Tuning("test", tunables::configs, tunables::pathOf, true, inputs -> {}, view -> {}),
          tunables,
          new ByHand(System::nanoTime).experiments());
  private final Api api =
      new Api(
          page,
          new Broadcaster(Broadcaster.MAX_STREAMS, Broadcaster.MAILBOX, stream -> {}),
          PitwallServer.WAIT,
          PitwallServer.IDLE,
          PitwallServer.DeviceLimits.DEFAULT);

  @Test
  void everyRouteTheApiListsIsInTheTableOnce() {
    Set<Route> reached = new HashSet<>();
    for (Listed listed : API) {
      List<Route> answering =
          api.routes().stream()
              .filter(route -> route.answers(listed.method()) && route.match(listed.path()) != null)
              .toList();
      assertThat(answering).as(listed.method() + " " + listed.path()).hasSize(1);
      reached.add(answering.getFirst());
    }
    assertThat(api.routes())
        .as("every route in the table is one the API lists")
        .allSatisfy(route -> assertThat(reached).as(route.toString()).contains(route));
  }
}
