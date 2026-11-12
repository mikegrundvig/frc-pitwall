package com.michaelgrundvig.frc.pitwall;

import com.michaelgrundvig.frc.pitwall.PitwallServer.DeviceLimits;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A device's routes: its cards ({@code GET /api/devices}), a download by its id (GET), and a file
 * sent to it or a choice made on its card (POST). Each is asked of the device on the server's
 * thread, never the robot loop's, and only by the page: a request names the page's event stream,
 * which another site's page can't send.
 */
final class DeviceRoutes {
  /** A device's download: the device's name, then the download's id. */
  static final Pattern DOWNLOAD = route("downloads");

  /** A file sent to a device: the device's name, then the upload's id. */
  static final Pattern UPLOAD = route("uploads");

  /** A choice made on a device's card: the device's name, then the choice's id. */
  static final Pattern CHOICE = route("choices");

  /** A device's route of a kind, names as {@link Devices#NAME} allows them, and nothing else. */
  private static Pattern route(String kind) {
    String name = "(" + Devices.NAME.pattern() + ")";
    return Pattern.compile("/api/devices/" + name + "/" + kind + "/" + name);
  }

  private final Page state;
  private final Requests requests;
  private final Responses responses;
  private final DeviceLimits limits;

  /** Requests to devices under way: a page can't make the robot ask more at once. */
  private final Semaphore under;

  DeviceRoutes(Page state, Requests requests, Responses responses, DeviceLimits limits) {
    this.state = state;
    this.requests = requests;
    this.responses = responses;
    this.limits = limits;
    under = new Semaphore(limits.concurrent());
  }

  /** {@code GET /api/devices}: each device's card as of its last read. */
  void cards(HttpExchange exchange) throws IOException {
    responses.json(exchange, 200, state.devices().view());
  }

  /**
   * A device's download, asked of the device now, on this thread: it may wait on the network, never
   * the robot loop. At most {@link DeviceLimits#concurrent} at once, and none while the field
   * system is connected.
   */
  void download(HttpExchange exchange, Matcher route) throws IOException {
    String name = route.group(1);
    Devices.Device device = device(exchange, name);
    String id = route.group(2);
    Devices.Download download =
        device
            .download(id)
            .orElseThrow(
                () -> new Problem(404, "No download", name + " has no download named " + id));
    if (state.tuning().isFieldConnected()) {
      throw new Problem(
          409,
          "On the field",
          "Downloads from devices wait until the field system disconnects: the match has the"
              + " network");
    }
    if (!under.tryAcquire()) {
      throw new Problem(
          503,
          "Busy",
          "Pitwall is already asking devices for "
              + limits.concurrent()
              + " things: try again when one is done");
    }
    try {
      sendFile(exchange, name, download);
    } finally {
      under.release();
    }
  }

  /**
   * A file sent to a device, taken by the robot's own handler, on this thread, never the robot
   * loop. Never while the field system is connected.
   */
  void upload(HttpExchange exchange, Matcher route) throws IOException {
    String name = route.group(1);
    Devices.Device device = device(exchange, name);
    whileOffTheField();
    String id = route.group(2);
    Devices.Upload upload =
        device
            .upload(id)
            .orElseThrow(
                () -> new Problem(404, "No upload", name + " takes no upload named " + id));
    String message =
        taken(
            name,
            () -> {
              byte[] body = Requests.bytes(exchange, "A file sent to a device is at most 64 KB");
              return upload.accept().accept(body);
            });
    responses.json(exchange, 200, Map.of("message", message));
  }

  /**
   * A choice made on a device's card, taken by the robot's own handler, on this thread, never the
   * robot loop. Never while the field system is connected.
   */
  void choice(HttpExchange exchange, Matcher route) throws IOException {
    String name = route.group(1);
    Devices.Device device = device(exchange, name);
    whileOffTheField();
    String id = route.group(2);
    Devices.Choose choose =
        device
            .chooser(id)
            .orElseThrow(() -> new Problem(404, "No choice", name + " has no choice named " + id));
    String message =
        taken(
            name,
            () -> {
              Object value = requests.object(exchange).get("value");
              if (!(value instanceof String chosen)) {
                throw new Problem(400, "No value", "A choice is sent as {\"value\": \"...\"}");
              }
              return choose.choose(chosen);
            });
    responses.json(exchange, 200, Map.of("message", message));
  }

  /** The device a request names, which only the page may ask of: 400 without its stream. */
  private Devices.Device device(HttpExchange exchange, String name) {
    if (Requests.stream(exchange) == null) {
      throw new Problem(
          400,
          "No page",
          "Devices are asked from Pitwall's page: a request names the page's event stream ("
              + PitwallServer.STREAM
              + ")");
    }
    return state
        .devices()
        .named(name)
        .orElseThrow(() -> new Problem(404, "No device", "There is no device named " + name));
  }

  /** A change to a device waits until the field system disconnects. */
  private void whileOffTheField() {
    if (state.tuning().isFieldConnected()) {
      throw new Problem(
          409,
          "On the field",
          "Changes to devices wait until the field system disconnects: the match has the robot");
    }
  }

  /**
   * What the robot's handler said of a change, or 422 with why it refused it; 500 when reading the
   * request or taking it failed.
   */
  private static String taken(String name, DeviceCall<String> change) {
    try {
      return change.call();
    } catch (Devices.Refused refused) {
      throw new Problem(422, "Refused", Said.of(refused));
    } catch (IOException e) {
      throw new Problem(500, "Failed", name + ": " + Said.of(e));
    }
  }

  /**
   * A device's file, streamed through as it arrives when the device says its length: one that ends
   * short reaches the page cut off, as an error. One of unknown length is read whole first, up to
   * the limit, so a file over it is refused rather than cut.
   */
  private void sendFile(HttpExchange exchange, String name, Devices.Download download)
      throws IOException {
    long most = limits.maxFileBytes();
    try (Devices.File file = fromDevice(name, download.fetch()::fetch)) {
      long length = file.length();
      if (length > most) {
        throw tooBig(name, file.name(), most);
      }
      if (length < 0) {
        byte[] whole =
            fromDevice(
                name,
                () -> file.body().readNBytes((int) Math.min(most + 1, Integer.MAX_VALUE - 8)));
        if (whole.length > most) {
          throw tooBig(name, file.name(), most);
        }
        responses.file(exchange, file.name(), file.contentType(), whole);
        return;
      }
      responses.attachment(exchange, file.name(), file.contentType());
      exchange.sendResponseHeaders(200, length > 0 ? length : -1);
      if (length > 0) {
        stream(exchange, file.body(), length);
      }
    }
  }

  /**
   * Passes a body on as it arrives. A body that ends early leaves the answer short of its length:
   * closing it then cuts the connection, so the browser reports the download failed.
   */
  private static void stream(HttpExchange exchange, InputStream body, long length)
      throws IOException {
    try (OutputStream out = exchange.getResponseBody()) {
      byte[] chunk = new byte[64 * 1024];
      long left = length;
      while (left > 0) {
        int read = body.read(chunk, 0, (int) Math.min(chunk.length, left));
        if (read < 0) {
          break;
        }
        out.write(chunk, 0, read);
        left -= read;
      }
    }
  }

  private static Problem tooBig(String name, String file, long most) {
    return new Problem(
        502,
        "Too big",
        name + "'s " + file + " is more than Pitwall passes on: " + most + " bytes");
  }

  /** Something a device gives, or 502 saying why it didn't (503 when it's busy). */
  private static <T> T fromDevice(String name, DeviceCall<T> call) {
    try {
      return call.call();
    } catch (Devices.Busy busy) {
      throw new Problem(503, name + " is busy", name + " is busy: " + Said.of(busy)).retryAfter(1);
    } catch (IOException | RuntimeException e) {
      throw new Problem(502, "No answer from " + name, name + " didn't answer: " + Said.of(e));
    }
  }

  /** A call to a device. */
  @FunctionalInterface
  private interface DeviceCall<T> {
    T call() throws IOException;
  }
}
