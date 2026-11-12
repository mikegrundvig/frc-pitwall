package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.pitwall.Devices.Card;
import com.michaelgrundvig.frc.pitwall.Devices.Device;
import com.michaelgrundvig.frc.pitwall.Devices.Level;
import com.michaelgrundvig.frc.pitwall.Devices.Line;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

/** The devices' cards as the robot loop reads them, and what a device may be. */
class DevicesTest {
  private final AtomicLong clock = new AtomicLong();
  private final Devices devices = new Devices(clock::get, Duration.ofSeconds(1));
  private final AtomicInteger reads = new AtomicInteger();
  private static final Devices.Fetch NO_FILE =
      () -> Devices.File.of("empty.zip", "application/zip", new byte[0]);

  private Card card() {
    reads.incrementAndGet();
    return Card.of(
        "Present", Level.OK, List.of(new Line("Version", "v1 (" + reads + ")", Level.OK)));
  }

  @Test
  void cardsAreReadOnlyWhileWatched() {
    devices.add(Device.named("vision-front", "Coprocessor", this::card));

    devices.periodic(false);

    assertThat(reads).hasValue(0);
    assertThat(devices.view()).isEmpty();
  }

  @Test
  void cardsAreReadAtMostOnceAnInterval() {
    devices.add(Device.named("vision-front", "Coprocessor", this::card));

    devices.periodic(true);
    clock.addAndGet(500_000_000L);
    devices.periodic(true);

    assertThat(reads).hasValue(1);
    clock.addAndGet(500_000_000L);
    devices.periodic(true);
    assertThat(reads).hasValue(2);
  }

  @Test
  void theViewHoldsWhatTheCardSaysAndWhatCanBeDone() {
    devices.add(
        Device.named("vision-front", "Coprocessor", this::card)
            .download("settings", "Download settings backup", NO_FILE)
            .upload("survey", "Upload venue", body -> "Saved"));

    devices.periodic(true);

    @SuppressWarnings("unchecked")
    Map<String, Object> shown =
        (Map<String, Object>) Objects.requireNonNull(devices.view().get("vision-front"));
    assertThat(shown)
        .containsEntry("kind", "Coprocessor")
        .containsEntry("state", "Present")
        .containsEntry("level", "ok");
    assertThat(shown.get("lines"))
        .isEqualTo(List.of(Map.of("label", "Version", "value", "v1 (1)", "level", "ok")));
    assertThat(shown.get("downloads"))
        .isEqualTo(List.of(Map.of("id", "settings", "label", "Download settings backup")));
    assertThat(shown.get("uploads"))
        .isEqualTo(List.of(Map.of("id", "survey", "label", "Upload venue")));
    assertThat(shown).doesNotContainKey("autonomous");
  }

  /**
   * A card's picture is an address for the browser to load from the device, or why there's none; a
   * card with no picture says nothing of one.
   */
  @Test
  void aCardsPictureIsWhereTheBrowserLoadsItOrWhyThereIsNone() {
    record Case(String name, Card card, Object shown) {}
    List<Line> lines = List.of(new Line("Frames", "60 fps", Level.OK));
    List<Case> cases =
        List.of(
            new Case(
                "streaming",
                Card.of("Sending", Level.OK, lines)
                    .withPicture(Devices.Picture.at("http://10.12.34.11:5809/stream/front")),
                Map.of("url", "http://10.12.34.11:5809/stream/front")),
            new Case(
                "on another program",
                Card.of("Sending", Level.OK, lines)
                    .withPicture(
                        Devices.Picture.none("No live view: the camera is on PhotonVision")),
                Map.of("unavailable", "No live view: the camera is on PhotonVision")),
            new Case("without one", Card.of("Sending", Level.OK, lines), "none"));
    for (Case c : cases) {
      Devices each = new Devices(clock::get, Duration.ofSeconds(1));
      each.add(Device.named("front", "Camera", c::card));
      each.periodic(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> shown =
          (Map<String, Object>) Objects.requireNonNull(each.view().get("front"));
      assertThat(shown.getOrDefault("picture", "none")).as(c.name()).isEqualTo(c.shown());
    }
    for (String address :
        List.of("javascript:alert(1)", "ftp://board/x", "http://a b", "/stream")) {
      assertThatIllegalArgumentException()
          .as(address)
          .isThrownBy(() -> Devices.Picture.at(address))
          .withMessageContaining("http or https");
    }
  }

  /**
   * A place a card's picture may come from is an origin, http or https, a host and its port, and
   * nothing after: anything else is refused, as the page's policy couldn't name it.
   */
  @Test
  void aPicturesPlaceIsAnOriginAndNothingMore() {
    Devices devices = new Devices(clock::get, Duration.ofSeconds(1));
    for (String origin :
        List.of("http://10.12.34.11:5809", "https://robot.local", "http://visor-front:5809")) {
      devices.picturesFrom(origin);
    }
    assertThat(devices.pictureOrigins())
        .containsExactly(
            "http://10.12.34.11:5809", "https://robot.local", "http://visor-front:5809");
    for (String origin :
        List.of(
            "http:",
            "*",
            "http://10.12.34.11:5809/stream/front",
            "http://[fe80::1]:5809",
            "ftp://board",
            "http://a b",
            "http://board; script-src *",
            "10.12.34.11:5809")) {
      assertThatIllegalArgumentException()
          .as(origin)
          .isThrownBy(() -> devices.picturesFrom(origin))
          .withMessageContaining("http or https");
    }
  }

  /** A card names the live configuration whose values it shows, to tune from the card. */
  @Test
  void aCardNamesTheConfigurationItTunes() {
    Device camera = Device.named("front", "Camera", this::card).tuning("front camera");
    devices.add(camera);
    devices.add(Device.named("back", "Camera", this::card));
    devices.periodic(true);

    assertThat(devices.view().get("front"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("tuning", "front camera");
    assertThat(devices.view().get("back"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .doesNotContainKey("tuning");
    assertThat(camera.tuning()).hasValue("front camera");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Device.named("side", "Camera", this::card).tuning(" "));
    assertThatThrownBy(() -> camera.tuning("another"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("on the page already");
  }

  /**
   * The device whose card is what autonomous runs says so, and no other does: the page's Sim
   * controls show that card over the press that sets autonomous and enables.
   */
  @Test
  void theCardOfWhatAutonomousRunsSaysSo() {
    devices.add(Device.named("auto", "Autonomous", this::card).autonomous());
    devices.add(Device.named("vision-front", "Coprocessor", this::card));

    devices.periodic(true);

    assertThat(devices.view().get("auto"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("autonomous", true);
    assertThat(devices.view().get("vision-front"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .doesNotContainKey("autonomous");
  }

  @Test
  void aCardThatThrowsSaysWhyRatherThanStopTheRobot() {
    devices.add(
        Device.named(
            "vision-front",
            "Coprocessor",
            () -> {
              throw new IllegalStateException("no table");
            }));

    devices.periodic(true);

    @SuppressWarnings("unchecked")
    Map<String, Object> shown =
        (Map<String, Object>) Objects.requireNonNull(devices.view().get("vision-front"));
    assertThat(shown).containsEntry("state", "Unknown").containsEntry("level", "warning");
    assertThat(String.valueOf(shown.get("lines"))).contains("Error: no table");
  }

  @Test
  void aDeviceAddedLaterIsReadAtTheNextChance() {
    devices.add(Device.named("vision-front", "Coprocessor", this::card));
    devices.periodic(true);

    devices.add(Device.named("vision-back", "Coprocessor", this::card));
    devices.periodic(true);

    assertThat(devices.view()).containsOnlyKeys("vision-front", "vision-back");
  }

  @Test
  void namesAreUniqueAndFitAnAddress() {
    devices.add(Device.named("vision-front", "Coprocessor", this::card));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> devices.add(Device.named("vision-front", "Camera", this::card)))
        .withMessage(
            "two devices are named vision-front: the Coprocessor card and the Camera card");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Device.named("Vision Front", "Coprocessor", this::card));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Device.named("vision-back", "Coprocessor", this::card)
                    .download("settings", "One", NO_FILE)
                    .download("settings", "Two", NO_FILE))
        .withMessageContaining("two downloads");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                Device.named("vision-back", "Coprocessor", this::card)
                    .download("../etc", "Bad", NO_FILE));
    assertThat(devices.named("vision-front")).isPresent();
    assertThat(devices.named("nothing")).isEmpty();
    assertThat(devices.all()).hasSize(1);
  }

  /**
   * A robot program checks its cards' names by the rule {@link Devices#add} keeps, before it makes
   * any: the first card to take a name has it, and a later one is told whose card has it.
   */
  @Test
  void aNameTakenIsSaidToBeAnotherCardsBeforeAnyIsAdded() {
    record Taking(String name, String whose, String clashesWith) {}
    List<Taking> rows =
        List.of(
            new Taking("auto", "the autos' card", ""),
            new Taking("front", "camera \"front\"'s card", ""),
            new Taking("auto", "camera \"Auto\"'s card", "the autos' card"),
            new Taking("front", "camera \"Front\"'s card", "camera \"front\"'s card"),
            new Taking("rear", "camera \"rear\"'s card", ""));
    CardNames names = new CardNames();
    for (Taking row : rows) {
      assertThat(names.take(row.name(), row.whose()).orElse(""))
          .as(row.whose())
          .isEqualTo(row.clashesWith());
    }
  }

  /** A card's level is its worst line's, a note reading as OK; a state may make it worse. */
  @Test
  void aCardsLevelIsItsWorstLine() {
    record Rolled(String name, List<Level> lines, Level card) {}
    List<Rolled> rows =
        List.of(
            new Rolled("no lines", List.of(), Level.OK),
            new Rolled("notes alone", List.of(Level.NOTE, Level.NOTE), Level.OK),
            new Rolled("a note and an OK", List.of(Level.NOTE, Level.OK), Level.OK),
            new Rolled("a warning", List.of(Level.OK, Level.WARNING, Level.NOTE), Level.WARNING),
            new Rolled("a problem first", List.of(Level.PROBLEM, Level.WARNING), Level.PROBLEM),
            new Rolled("a problem last", List.of(Level.WARNING, Level.PROBLEM), Level.PROBLEM));
    for (Rolled row : rows) {
      List<Line> lines = row.lines().stream().map(level -> new Line("A", "b", level)).toList();
      assertThat(Level.of(lines)).as(row.name()).isEqualTo(row.card());
    }
    assertThat(Level.OK.worse(Level.PROBLEM)).isEqualTo(Level.PROBLEM);
    assertThat(Level.PROBLEM.worse(Level.WARNING)).isEqualTo(Level.PROBLEM);
    assertThat(Level.WARNING.worse(Level.OK)).isEqualTo(Level.WARNING);
  }

  @Test
  void filesAreChecked() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Devices.File.of("../passwd", "text/plain", new byte[0]));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Devices.File.of(".hidden", "text/plain", new byte[0]));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Devices.File.of("a.txt", "text/html\r\nX: y", new byte[0]));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Devices.File(
                    "a.txt", "text/plain", -2, new java.io.ByteArrayInputStream(new byte[0])));
  }

  /** Once on the page, the server's threads read a device: it can't change after. */
  @Test
  void aDeviceIsDescribedWholeBeforeItsAdded() {
    Device device = Device.named("vision-front", "Coprocessor", this::card);
    devices.add(device);

    assertThatThrownBy(() -> device.download("settings", "Settings", NO_FILE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("describe a device whole");
    assertThatThrownBy(() -> device.upload("survey", "Upload", body -> "Saved"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> device.choose("venue", value -> "Chose"))
        .isInstanceOf(IllegalStateException.class);
  }
}
