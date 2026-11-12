package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.michaelgrundvig.frc.pitwall.robot.RobotEvent;
import com.michaelgrundvig.frc.pitwall.robot.RobotWire;
import com.michaelgrundvig.frc.pitwall.simulator.Simulator;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pitwall's page and its REST API, served by the robot program itself: http://localhost:5820 in the
 * simulator, http://robot.local:5820 on the robot. Any browser works, a phone's included, and so
 * does anything else that speaks HTTP. It serves the robot's status ({@link Status}), actions
 * ({@link Actions}), live tuning of the mechanisms' configurations ({@link Tuning}, {@link
 * LiveConfig}), every WPILib tunable in the program ({@link TunableTee}), and experiments ({@link
 * com.michaelgrundvig.frc.pitwall.experiment.Experiments}):
 *
 * <ul>
 *   <li>{@code GET /api/events}: the page's state as server-sent events, pushed only when something
 *       changes: a {@code snapshot} first, then {@code patch} events (JSON merge patches) and the
 *       run's {@code samples}; a {@code heartbeat} after 5 s with nothing else to say. A page
 *       reconnecting names the stream it had ({@code ?replaces=id}), which closes; with eight open,
 *       the one that wrote least lately makes way. A page names the views it shows ({@code
 *       ?views=world}), and only then is sent their part of the state (a scene's moving parts),
 *       starting with a {@code replace} event holding all of it. See {@link Broadcaster}.
 *   <li>{@code PUT /api/views}: {@code {"views": ["world"]}}, the views the page shows now, naming
 *       its stream; 204.
 *   <li>{@code GET /api/scenes/<id>/inspect/<shape>}: what a click on a scene's shape says, {@code
 *       {"scene", "shape", "title", "details": [{"label", "value"}], "actions", "components"}}, the
 *       actions as {@code /api/actions} gives them, and the components the simulator's parts the
 *       shape is (none on a robot); 404 when nothing is known of it now. With {@code ?index=} it
 *       says what the shape was in that loop of the robot's session. See {@link
 *       com.michaelgrundvig.frc.pitwall.scene.Scenes}.
 *   <li>{@code GET /api/scenes/<id>/state?index=}: a scene's moving parts as a loop of the robot's
 *       session had them, whole: {@code {"index", "timestampNanos", "frames", "values", "styles",
 *       "shapes", "liveOnly"}}, as the scene's state is sent live, with the layers that have no
 *       earlier loop to show named under {@code liveOnly}; 404 when the session holds no such loop.
 *   <li>{@code GET /api/robot}: with a robot's session, how much of it is remembered and what that
 *       costs, {@code {"robot", "oldest", "newest", "oldestNanos", "newestNanos",
 *       "rememberedLoops", "bytesPerLoop", "bytes", "meanTakeMicros", "slowestTakeMicros", "texts",
 *       "mostTexts", "gatesNotKept", "events", "rememberedEvents", "eventBytes", "startedNanos"}}:
 *       the loops it holds by index, when each end of them was and when the session began; 404 for
 *       a robot program that hands Pitwall none.
 *   <li>{@code GET /api/robot/view}: the robot's joints as one loop had them, as {@link RobotWire}
 *       says: the newest loop, or {@code ?index=} a loop by its index, or {@code ?at=} the loop
 *       nearest a time on the robot's clock, in nanoseconds; 404 when the session holds no such
 *       loop. With it, as {@code "changed"}, what changed in that loop from the loop before. Live,
 *       the page is sent the same at {@code robotView.live}, named as the view {@code robot}.
 *   <li>{@code GET /api/robot/events}: the session's events, the loops in which something changed,
 *       each as {@link RobotWire#wire(RobotEvent)} says, in order: {@code {"found", "oldest",
 *       "newest", "events": [event]}}, with how many events the session has found and the loops it
 *       holds. Every event it holds (500 at most, as every answer), or {@code ?from=&to=} those in
 *       a range of loops, {@code ?fromAt=&toAt=} in a range of times on the robot's clock in
 *       nanoseconds, {@code ?newest=} the last so many, {@code ?since=} those numbered from one on,
 *       {@code ?before=} the one event before a loop and {@code ?after=} the one after it. Any of
 *       them but {@code since} only {@code ?kind=} of some kinds (their names, with commas
 *       between), {@code ?about=} about one joint, sensor, gate or gamepad port by its name, and
 *       {@code ?problems=true} the problems.
 *   <li>{@code GET /api/status}: the status items, read by the next loop, {@code [{"name",
 *       "value"}]}; 503 when the loop doesn't get to it within a second.
 *   <li>{@code GET /api/actions}: every action and where it stands, {@code [{"name", "phase",
 *       "detail", "locked", "when"}]}: why it can't start now (empty while it can), and when it may
 *       run ({@code ENABLED_IN_UTILITY} or {@code DISABLED}). {@code POST} it {@code {"start":
 *       name}} or {@code {"cancel": name}}.
 *   <li>{@code GET /api/tuning}: the session, {@code {"name", "enabled", "fieldConnected",
 *       "refused"}}, the last being the latest dashboard changes refused and why (a dashboard can't
 *       say). {@code PATCH} it with {@code {"enabled": true}} to switch tuning on or off.
 *   <li>{@code GET /api/configs}: each configuration's documentation, from its records' Javadoc
 *       ({@link LiveConfig.Documentation}), {@code {name: {"record", "doc": {"summary", "text"},
 *       "groups": [{"path", "record", "doc", "meaning"}]}}}: its root record, then each record its
 *       values are in, with what the record holding it says it is. A {@code record} is a display
 *       name, such as {@code DrivetrainControl.SwerveGains}.
 *   <li>{@code GET /api/tunables}: the configurations' tunable values, by the WPILib tunable each
 *       is published as, {@code {path: {"config", "field", "unit", "limits", "min", "max",
 *       "committed", "record", "group", "meaning"}}}: its tuning limits as text and their ends as
 *       numbers (an open end has none), and the record that declares the value, its path, and what
 *       its {@code @param} says the value means (empty when there's none).
 *   <li>{@code GET /api/values}: their current values, {@code {path: number}}. {@code PATCH} it
 *       with a JSON merge patch (RFC 7396) to change several at once, all or none, each checked by
 *       its configuration first; {@code null} returns a value to its committed one.
 *   <li>{@code GET /api/changes}: what differs from the committed configurations, with the builder
 *       calls that commit it, {@code [{"config", "field", "committed", "tuned", "builderCall"}]}
 *       ({@code Accept: text/plain} for the report). {@code DELETE} it to return every value to its
 *       committed one.
 *   <li>{@code GET /api/wpilib-tunables}: every other WPILib tunable, {@code [{"path", "type",
 *       "value", "editable", "limits", "min", "max"}]}, the limits as text ({@code "0 to 30"},
 *       empty for none) and their ends as numbers. {@code PATCH} it with {@code {path: value}} to
 *       change several at once, all or none, each within its limits.
 *   <li>{@code GET /api/experiments}: the rigs and the experiments that run on them. {@code POST}
 *       it {@code {"start": name}}, naming the page's stream, or {@code {"cancel": name, "run": id,
 *       "reason": why}}; canceling what isn't running is 409.
 *   <li>{@code GET /api/run}: the run in progress, or the latest to end, with its trace.
 *   <li>{@code POST /api/heartbeat}: nothing, but it keeps a run going: while an experiment runs,
 *       the page that started it sends a request at least every 500 ms (the deadman), naming its
 *       event stream in the {@code Pitwall-Stream} header, as it does with every request.
 *   <li>{@code GET /api/devices}: each device's card as of its last read, as the event stream's
 *       {@code devices} section carries them ({@link Devices}).
 *   <li>{@code GET /api/devices/<name>/downloads/<id>}: a file from a device, fetched now, as an
 *       attachment; 502 when the device doesn't give it, 503 (with {@code Retry-After}) when it's
 *       busy.
 *   <li>{@code POST /api/devices/<name>/uploads/<id>}: a file for a device, the body as it is (64
 *       KB at most), and {@code POST /api/devices/<name>/choices/<id>}: {@code {"value": option}},
 *       a choice made on its card; each {@code {"message"}}, what the robot did with it, or 422
 *       with why it refused.
 * </ul>
 *
 * <p>The device routes ask the device on the server's thread, never the robot loop's; the page
 * never talks to a device itself. Each names the page's event stream ({@code Pitwall-Stream}), as
 * only the page can (400 without), so another site's page can't make the robot ask a device
 * anything; at most two downloads are under way at once (503 beyond); and a download, or a change
 * to a device, waits until the field system disconnects (409). A file is streamed through as it
 * arrives, up to 64 MB.
 *
 * <p>In the simulator only, the simulator's controls ({@link Simulator}); on a robot every one of
 * these is 404, so nothing can write the Driver Station's state there. They are exempt from the
 * field lock: in the simulator, they are the field. See docs/simulator.md for the details.
 *
 * <ul>
 *   <li>{@code GET /api/simulator}: {@code {"simulator", "components", "scenarios"}}, as the event
 *       stream's sections of those names carry them. {@code PATCH} it with any of {@code
 *       "enabled"}, {@code "mode"} ({@code "AUTONOMOUS"}, {@code "TELEOPERATED"}, {@code
 *       "UTILITY"}), {@code "allianceStation"} ({@code "RED_1"} to {@code "BLUE_3"}, or {@code
 *       "UNKNOWN"}), {@code "fmsAttached"}, {@code "dsAttached"}, {@code "matchTime"} (seconds, or
 *       null for none), and {@code "paused"}.
 *   <li>{@code POST /api/simulator/step}: one loop of a paused simulator; 409 while it runs.
 *   <li>{@code POST /api/simulator/stop}: ends the program, as closing the GUI's window did.
 *   <li>{@code PUT /api/simulator/joysticks}: {@code {"0": {"axes": [...], "buttons": [...],
 *       "povs": [...]}, "1": null}}, each joystick's whole input (null lets it go), at most once a
 *       frame and again every 200 ms while anything is pressed, naming the page's event stream; 204
 *       at once.
 *   <li>{@code POST /api/simulator/faults}: {@code {"component", "fault", "values": {name:
 *       number}}} applies a fault, {@code {"component", "fault", "undo": true}} undoes a lasting
 *       one. {@code DELETE} it to undo every lasting fault.
 *   <li>{@code POST /api/simulator/scenarios}: {@code {"apply": name}} applies a scenario's faults.
 * </ul>
 *
 * <p>A change waits for the robot loop and answers with what became of it: 200 and the new state
 * when applied, 422 when a limit or the configuration's checks (or the action) refuse it, 409 when
 * it is locked out (tuning off, not enabled in utility mode, or the field system connected), and
 * 202 when the loop didn't get to it in time (the next GET shows the result). Errors are problem
 * details (RFC 9457). A refused change to tunables also lists each reason in {@code errors}, tied
 * to the tunables it concerns ({@code [{"layer", "by", "path", "paths", "config", "group", "text",
 * "line"}]}), while {@code detail} keeps every reason as a line, as it always has.
 *
 * <p>The server's threads never change the robot: they read what the robot loop last published
 * ({@link Status#view()}, {@link Actions#view()}, {@link Tuning#view()}) and queue requests for the
 * robot loop to apply. The event stream is written by its own thread from what the loop hands it.
 *
 * <p>The page itself is {@code index.html} and the files in {@code page/} beside it ({@code
 * /page/tune.js}, ...): a script module for its core and one for each view, plain JavaScript served
 * as written, with nothing to build. Its Content-Security-Policy lets it load nothing from anywhere
 * else but the devices' pictures, and those only from the places the robot program named ({@link
 * Devices#picturesFrom}).
 *
 * <p>Every route is one row of one table ({@code Api}): its method, its path or pattern, and its
 * handler, one of the small types that answer an area's routes.
 *
 * <p>Nothing here is bundled: the web server is the one built into Java ({@code
 * com.sun.net.httpserver}), and JSON is avaje-jsonb, which WPILib gives every robot program.
 */
public final class PitwallServer implements AutoCloseable {
  /**
   * Deliberately outside 5800-5810, the only team ports the field network lets through. Tools that
   * must work on the field crowd into that range: on a Systemcore, Elastic's package serves on
   * 5803, AdvantageScope Lite on 5808, and NetworkTables on 5810, and Elastic's docs have teams
   * serve layouts from the robot program on 5800. Pitwall is locked while the field is connected,
   * so it doesn't need the range. If something on your robot already uses this port, pass another
   * to {@link #startOrExplain}, or name it in the {@code pitwall.port} system property, which
   * {@link Pitwall#start(String)} reads.
   */
  public static final int PORT = 5820;

  /** How long a change waits for the robot loop before answering 202 Accepted. */
  static final Duration WAIT = Duration.ofSeconds(1);

  /**
   * How long a stream stays silent before a heartbeat says it's still there. The heartbeat is an
   * event, not a comment line: a browser's EventSource never shows the page a comment, and the page
   * counts 10 s without an event as the link lost.
   */
  static final Duration IDLE = Duration.ofSeconds(5);

  /** The header a page names its event stream in, so the deadman knows who's asking. */
  static final String STREAM = "Pitwall-Stream";

  private final HttpServer server;
  private final ExecutorService threads;
  private final int port;

  /**
   * @param page what the page shows and changes
   * @param broadcaster pushes the page's state to each event stream
   * @param port the port to serve on; 0 picks a free one
   */
  public PitwallServer(Page page, Broadcaster broadcaster, int port) {
    this(page, broadcaster, port, WAIT, IDLE);
  }

  PitwallServer(Page page, Broadcaster broadcaster, int port, Duration wait, Duration idle) {
    this(page, broadcaster, port, wait, idle, DeviceLimits.DEFAULT);
  }

  PitwallServer(
      Page page,
      Broadcaster broadcaster,
      int port,
      Duration wait,
      Duration idle,
      DeviceLimits deviceLimits) {
    this.port = port;
    // Small events go out at once, not held back to fill a packet (Nagle's algorithm). The server
    // reads this as it starts, so it's set first; a program that set it already keeps its choice.
    if (System.getProperty("sun.net.httpserver.nodelay") == null) {
      System.setProperty("sun.net.httpserver.nodelay", "true");
    }
    try {
      server = HttpServer.create();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // A virtual thread for each request: a stream sleeps on its mailbox between events, so a slow
    // or vanished page stalls only its own thread. They never keep the program running.
    threads = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(threads);
    Api api = new Api(page, broadcaster, wait, idle, deviceLimits);
    server.createContext("/", api::handle);
  }

  /**
   * Starts the page, or says why it couldn't. The page is a convenience: the robot runs without it.
   *
   * @param port usually {@link #PORT}
   * @param say prints a line where the team sees it
   * @return the running server, or empty if it couldn't start
   */
  public static Optional<PitwallServer> startOrExplain(
      Page page, Broadcaster broadcaster, int port, Consumer<String> say) {
    return startOrExplain(page, broadcaster, port, false, say);
  }

  /**
   * Starts the page, or says why it couldn't.
   *
   * @param thisComputerOnly serve only to this computer (the simulator on a laptop, where the
   *     network may be a school's or an event's), not to the network the robot is on
   */
  public static Optional<PitwallServer> startOrExplain(
      Page page,
      Broadcaster broadcaster,
      int port,
      boolean thisComputerOnly,
      Consumer<String> say) {
    PitwallServer server = null;
    try {
      server = new PitwallServer(page, broadcaster, port);
      server.start(thisComputerOnly);
      say.accept(
          "Pitwall: http://localhost:"
              + server.port()
              + " in the simulator, http://robot.local:"
              + server.port()
              + " on the robot");
      return Optional.of(server);
    } catch (Exception e) {
      say.accept("Pitwall's page couldn't start, so it is unavailable: " + e);
      if (server != null) {
        server.close();
      }
      return Optional.empty();
    }
  }

  /** Serves the page to the network the robot is on. */
  public void start() throws IOException {
    start(false);
  }

  /**
   * Serves the page.
   *
   * @param thisComputerOnly serve only to this computer, not to the network
   */
  public void start(boolean thisComputerOnly) throws IOException {
    server.bind(
        thisComputerOnly
            ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
            : new InetSocketAddress(port),
        0);
    server.start();
  }

  /** The port it serves on, once started; -1 before. */
  public int port() {
    InetSocketAddress address = server.getAddress();
    return address != null ? address.getPort() : -1;
  }

  @Override
  public void close() {
    try {
      server.stop(0);
    } catch (IllegalStateException neverStarted) {
      // There is nothing to stop.
    }
    threads.shutdownNow();
  }

  /**
   * What asking devices may cost: how many downloads at once (more are 503, never queued), and the
   * largest file passed on.
   *
   * @param concurrent downloads from devices at once, across every page
   * @param maxFileBytes the largest file passed on from a device
   */
  record DeviceLimits(int concurrent, long maxFileBytes) {
    /** Two at once, 64 MB. */
    static final DeviceLimits DEFAULT = new DeviceLimits(2, 64L * 1024 * 1024);
  }
}
