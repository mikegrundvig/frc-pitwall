package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.push.Broadcaster;
import com.michaelgrundvig.frc.pitwall.push.Event;
import com.michaelgrundvig.frc.pitwall.push.Stream;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The event stream's routes: the page's state as server-sent events ({@code GET /api/events}), and
 * the views a page shows, for the parts of the state sent only to the pages that show them ({@code
 * PUT /api/views}).
 */
final class StreamRoutes {
  /** What a quiet stream sends: an event the page sees, holding nothing. */
  private static final String HEARTBEAT = "event: heartbeat\ndata: {}\n\n";

  /** What a view is named: a scene's id. */
  private static final Pattern VIEW = Pattern.compile(SceneRoutes.NAMES);

  /** The most views a page may say it shows at once. */
  private static final int MAX_VIEWS = 8;

  private final Broadcaster broadcaster;
  private final Requests requests;
  private final Responses responses;
  private final Duration idle;

  StreamRoutes(Broadcaster broadcaster, Requests requests, Responses responses, Duration idle) {
    this.broadcaster = broadcaster;
    this.requests = requests;
    this.responses = responses;
    this.idle = idle;
  }

  /**
   * {@code GET /api/events}: streams the page's state until the page goes away: events as the robot
   * loop sends them, and a heartbeat whenever it has been quiet for the idle time. A write that
   * fails means the page has gone, and ends the stream.
   */
  void stream(HttpExchange exchange) throws IOException {
    Map<String, String> asked = Requests.query(exchange);
    String replaces = asked.get("replaces");
    String shown = asked.getOrDefault("views", "");
    Set<String> views = views(shown.isEmpty() ? List.of() : List.of(shown.split(",")));
    Stream stream =
        broadcaster
            .open(replaces, views)
            .orElseThrow(() -> new Problem(503, "Stopping", "Pitwall is stopping"));
    String why = "the page went away";
    try {
      stream.writtenBy(Thread.currentThread());
      responses.headers(exchange, "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      OutputStream out = exchange.getResponseBody();
      while (true) {
        Event event = stream.next(idle);
        if (!stream.isOpen()) {
          break;
        }
        out.write((event == null ? HEARTBEAT : event.wire()).getBytes(StandardCharsets.UTF_8));
        out.flush();
        stream.wrote();
      }
    } catch (IOException gone) {
      // The page closed, or its connection did: nothing more to send.
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      why = "Pitwall stopped";
    } finally {
      stream.close(why);
    }
  }

  /**
   * {@code PUT /api/views}: which views a page shows, {@code {"views": ["world"]}}, naming the
   * page's stream. 204 at once; the next loop sends what a newly shown view needs.
   */
  void views(HttpExchange exchange) throws IOException {
    Map<String, @Nullable Object> body = requests.object(exchange);
    if (body.size() != 1 || !(body.get("views") instanceof List<?> named)) {
      throw new Problem(400, "Views", "Send {\"views\": [view, ...]}");
    }
    Set<String> views = views(named);
    String stream = Requests.stream(exchange);
    if (stream == null) {
      throw new Problem(
          400,
          "No stream",
          "Name the page's event stream (" + PitwallServer.STREAM + ") whose views these are");
    }
    if (!broadcaster.show(stream, views)) {
      throw new Problem(404, "No such stream", "No event stream " + stream + " is open");
    }
    responses.text(exchange, 204, "text/plain", "");
  }

  /** Views a page names, each a scene's name, a few at most. */
  private static Set<String> views(List<?> named) {
    if (named.size() > MAX_VIEWS) {
      throw new Problem(400, "Too many views", "A page shows " + MAX_VIEWS + " views at most");
    }
    Set<String> views = new LinkedHashSet<>();
    for (Object view : named) {
      if (!(view instanceof String name) || !VIEW.matcher(name).matches()) {
        throw new Problem(400, "Not a view", "A view is named by a scene's id: " + view);
      }
      views.add(name);
    }
    return views;
  }
}
