package com.michaelgrundvig.frc.pitwall;

import static com.michaelgrundvig.frc.pitwall.Route.Method.DELETE;
import static com.michaelgrundvig.frc.pitwall.Route.Method.GET;
import static com.michaelgrundvig.frc.pitwall.Route.Method.PATCH;
import static com.michaelgrundvig.frc.pitwall.Route.Method.POST;
import static com.michaelgrundvig.frc.pitwall.Route.Method.PUT;

import com.michaelgrundvig.frc.pitwall.PitwallServer.DeviceLimits;
import com.michaelgrundvig.frc.pitwall.experiment.Experiments;
import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.sun.net.httpserver.HttpExchange;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * The page and the resources of the API: one table of routes, each a method, a path or pattern, and
 * its handler, and the check that a request is addressed to the robot. The handlers are the routes'
 * own, by area ({@link SimulatorRoutes}, {@link DeviceRoutes}, ...).
 */
final class Api {
  /** The simulator's routes, which a robot doesn't have. */
  private static final String SIMULATOR = "/api/simulator";

  /** Every route, in the order a resource's methods are listed in an {@code Allow} header. */
  private final List<Route> routes;

  private final Page state;
  private final Experiments experiments;
  private final Responses responses;

  /** This computer's name on the local network, once looked up: see {@link #machineName}. */
  private volatile @Nullable String machineName;

  Api(Page state, Broadcaster broadcaster, Duration wait, Duration idle, DeviceLimits limits) {
    this.state = state;
    experiments = state.experiments();
    JsonType<Object> json = Jsonb.builder().build().type(Object.class);
    Requests requests = new Requests(json);
    responses = new Responses(json, wait, state.devices());
    PageRoutes pages = new PageRoutes(responses);
    StreamRoutes streams = new StreamRoutes(broadcaster, requests, responses, idle);
    ActionRoutes actions = new ActionRoutes(state, requests, responses, wait);
    TuningRoutes tuning = new TuningRoutes(state, requests, responses);
    ExperimentRoutes runs = new ExperimentRoutes(state, requests, responses);
    DeviceRoutes devices = new DeviceRoutes(state, requests, responses, limits);
    SessionRoutes session = new SessionRoutes(state, responses);
    SceneRoutes scenes = new SceneRoutes(state, actions, responses);
    SimulatorRoutes simulator = new SimulatorRoutes(state, requests, responses, wait);
    routes =
        List.of(
            Route.at(GET, "/", pages::index),
            Route.matching(GET, PageRoutes.PAGE_FILE, (exchange, path) -> pages.pageFile(exchange)),
            Route.matching(
                GET, PageRoutes.BUNDLED_FILE, (exchange, path) -> pages.bundled(exchange)),
            Route.at(GET, "/api/events", streams::stream),
            Route.at(PUT, "/api/views", streams::views),
            Route.at(GET, "/api/status", actions::status),
            Route.at(GET, "/api/actions", actions::list),
            Route.at(POST, "/api/actions", actions::startOrCancel),
            Route.at(GET, "/api/tuning", tuning::session),
            Route.at(PATCH, "/api/tuning", tuning::patchSession),
            Route.at(GET, "/api/configs", tuning::configs),
            Route.at(GET, "/api/tunables", tuning::tunables),
            Route.at(GET, "/api/values", tuning::values),
            Route.at(PATCH, "/api/values", tuning::patchValues),
            Route.at(GET, "/api/changes", tuning::changes),
            Route.at(DELETE, "/api/changes", tuning::resetChanges),
            Route.at(GET, "/api/wpilib-tunables", tuning::wpilibTunables),
            Route.at(PATCH, "/api/wpilib-tunables", tuning::patchWpilibTunables),
            Route.at(GET, "/api/experiments", runs::list),
            Route.at(POST, "/api/experiments", runs::startOrCancel),
            Route.at(GET, "/api/run", runs::run),
            Route.at(POST, "/api/heartbeat", runs::heartbeat),
            Route.at(GET, "/api/devices", devices::cards),
            Route.matching(GET, DeviceRoutes.DOWNLOAD, devices::download),
            Route.matching(POST, DeviceRoutes.UPLOAD, devices::upload),
            Route.matching(POST, DeviceRoutes.CHOICE, devices::choice),
            Route.at(GET, "/api/robot", session::summary),
            Route.at(GET, "/api/robot/view", session::view),
            Route.at(GET, "/api/robot/events", session::events),
            Route.matching(GET, SceneRoutes.INSPECT, scenes::inspect),
            Route.matching(GET, SceneRoutes.STATE, scenes::state),
            Route.at(GET, SIMULATOR, simulator::view),
            Route.at(PATCH, SIMULATOR, simulator::patch),
            Route.at(POST, SIMULATOR + "/step", simulator::step),
            Route.at(POST, SIMULATOR + "/stop", simulator::stop),
            Route.at(PUT, SIMULATOR + "/joysticks", simulator::joysticks),
            Route.at(POST, SIMULATOR + "/faults", simulator::fault),
            Route.at(DELETE, SIMULATOR + "/faults", simulator::undoAll),
            Route.at(POST, SIMULATOR + "/scenarios", simulator::scenario));
  }

  /** Every route, for the test that holds them to the API's list. */
  List<Route> routes() {
    return routes;
  }

  void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      try {
        dispatch(exchange);
      } catch (Problem problem) {
        responses.problem(exchange, problem);
      } catch (RuntimeException e) {
        responses.problem(exchange, new Problem(500, "Server error", String.valueOf(e)));
      }
    }
  }

  /**
   * Hands a request to the route of its path and method: 404 when no route has its path, 405 (with
   * the methods it allows) when none of those has its method.
   */
  private void dispatch(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    checkAddressed(exchange, path, method);
    if (path.startsWith(SIMULATOR) && state.simulator() == null) {
      throw new Problem(
          404, "Not found", "There is no simulator here: a robot has no simulator controls");
    }
    List<String> allowed = new ArrayList<>();
    for (Route route : routes) {
      Matcher matched = route.match(path);
      if (matched == null) {
        continue;
      }
      if (route.answers(method)) {
        // Any request from the page that started an experiment keeps it going.
        experiments.heard(exchange.getRequestHeaders().getFirst(PitwallServer.STREAM));
        route.handler().handle(exchange, matched);
        return;
      }
      allowed.add(route.method().name());
    }
    if (allowed.isEmpty()) {
      throw new Problem(404, "Not found", "There is nothing at " + path);
    }
    exchange.getResponseHeaders().set("Allow", String.join(", ", allowed));
    throw new Problem(405, "Method not allowed", path + " allows " + String.join(", ", allowed));
  }

  /**
   * Refuses a request addressed to anything but the robot itself, a change sent from another site's
   * page, and any use of the API by another site's page. A web page open on a laptop connected to
   * the robot could otherwise point a name it controls at the robot's address (DNS rebinding) and
   * use the page as its own; the address the request names gives it away. The robot is reached by
   * address, as localhost, as robot.local, or by this computer's own name on the local network
   * ({@code fedora.local}): any other name, even another {@code .local} one, is 403. A browser says
   * which site a request comes from ({@code Sec-Fetch-Site}), so another site's page can't even
   * read the API, or close a page's event stream by naming it.
   */
  private void checkAddressed(HttpExchange exchange, String path, String method) {
    String host = exchange.getRequestHeaders().getFirst("Host");
    if (host == null || !isRobotsOwn(withoutPort(host))) {
      throw new Problem(
          403, "Wrong address", "Pitwall answers only at the robot's own address or name");
    }
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (!method.equals("GET") && origin != null && !origin.equals("http://" + host)) {
      throw new Problem(403, "Another site", "Changes come only from Pitwall's own page");
    }
    String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
    if (path.startsWith("/api/")
        && site != null
        && !site.equals("same-origin")
        && !site.equals("none")) {
      throw new Problem(403, "Another site", "Pitwall's API answers only Pitwall's own page");
    }
  }

  private static String withoutPort(String host) {
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      return end < 0 ? host : host.substring(0, end + 1);
    }
    int colon = host.lastIndexOf(':');
    return colon < 0 ? host : host.substring(0, colon);
  }

  private boolean isRobotsOwn(String name) {
    String host = name.toLowerCase(Locale.ROOT);
    return host.equals("localhost")
        || host.equals("robot.local")
        || isAddress(host)
        || host.equals(machineName());
  }

  /** Whether a host is an IP address, v4 or v6 in brackets, rather than a name. */
  private static boolean isAddress(String host) {
    try {
      InetAddress.ofLiteral(host); // parses it, and never asks DNS
      return true;
    } catch (IllegalArgumentException name) {
      return false;
    }
  }

  /**
   * This computer's own name on the local network, such as {@code fedora.local}: looked up the
   * first time a request names something else, on a server thread, never the robot loop's. Empty
   * when the computer has no name to look up.
   */
  private String machineName() {
    String name = machineName;
    if (name == null) {
      try {
        String host = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
        int dot = host.indexOf('.');
        name = (dot < 0 ? host : host.substring(0, dot)) + ".local";
      } catch (IOException noName) {
        name = "";
      }
      machineName = name;
    }
    return name;
  }
}
