package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.robot.LoopRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;

/**
 * Pitwall's receiver of the log, among the others, as AdvantageKit runs them: every receiver in
 * turn on one thread, catching nothing. A bug in Pitwall's reading must never cost the log's file
 * or its stream a single loop.
 */
class AdvantageKitLogTest {
  private static final int LOOPS = 10;

  /** A receiver that counts the tables it was given: the log's file, or its stream. */
  private static final class Counting implements LogDataReceiver {
    int tables;

    @Override
    public void putTable(LogTable table) {
      tables++;
    }
  }

  /**
   * One way Pitwall's reading of a loop goes wrong.
   *
   * @param failsAt the loop it first throws in, counting from 0
   * @param thrown what it throws
   * @param said what the alert says of it
   */
  private record Failure(
      String name, int failsAt, RuntimeException thrown, boolean alertThrows, String said) {}

  private static final List<Failure> FAILURES =
      List.of(
          new Failure(
              "on the first loop",
              0,
              new IllegalStateException("no joint named pivot"),
              false,
              "no joint named pivot"),
          new Failure(
              "partway through",
              4,
              new ArrayIndexOutOfBoundsException("Index 6 out of bounds for length 6"),
              false,
              "Index 6 out of bounds for length 6"),
          new Failure(
              "with no message",
              2,
              new NullPointerException(),
              false,
              "java.lang.NullPointerException"),
          new Failure(
              "and the alert throws too",
              1,
              new IllegalStateException("no joint named pivot"),
              true,
              "no joint named pivot"));

  @Test
  void aReceiverThatThrowsNeverStopsTheOthers() throws InterruptedException {
    for (Failure failure : FAILURES) {
      Counting file = new Counting();
      Counting stream = new Counting();
      List<Long> taken = new ArrayList<>();
      List<String> alerts = new ArrayList<>();
      Consumer<LoopRecord> taking =
          record -> {
            if (taken.size() == failure.failsAt()) {
              taken.add(record.timestampNanos());
              throw failure.thrown();
            }
            taken.add(record.timestampNanos());
          };
      Consumer<String> stopped =
          text -> {
            alerts.add(text);
            if (failure.alertThrows()) {
              throw new IllegalStateException("no alerts in this program");
            }
          };
      List<LogDataReceiver> receivers =
          List.of(file, AdvantageKitLog.receiver(taking, stopped), stream);

      for (int loop = 0; loop < LOOPS; loop++) {
        LogTable table = new LogTable(loop * 20_000_000L);
        // As AdvantageKit's receiver thread does it: each in turn, nothing caught.
        for (LogDataReceiver receiver : receivers) {
          receiver.putTable(table);
        }
      }

      assertThat(file.tables).as(failure.name() + ": the log's file").isEqualTo(LOOPS);
      assertThat(stream.tables).as(failure.name() + ": the log's stream").isEqualTo(LOOPS);
      assertThat(taken)
          .as(failure.name() + ": Pitwall stops at the loop that threw")
          .hasSize(failure.failsAt() + 1);
      assertThat(alerts).as(failure.name() + ": said once").hasSize(1);
      assertThat(alerts.get(0))
          .as(failure.name())
          .startsWith("Pitwall stopped reading the log")
          .endsWith(failure.said());
    }
  }
}
