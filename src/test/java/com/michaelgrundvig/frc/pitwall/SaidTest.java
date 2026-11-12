package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What went wrong, as Pitwall writes it: the message, or what was thrown when there's none. */
class SaidTest {
  @Test
  void aThrowableSaysItsMessageOrWhatItIs() {
    record Row(Throwable thrown, String said) {}
    List<Row> rows =
        List.of(
            new Row(new IllegalStateException("no table"), "no table"),
            new Row(new IOException("Connection refused"), "Connection refused"),
            new Row(new NullPointerException(), "java.lang.NullPointerException"),
            new Row(new IllegalArgumentException(" "), "java.lang.IllegalArgumentException:  "),
            new Row(new IllegalArgumentException(""), "java.lang.IllegalArgumentException: "));
    for (Row row : rows) {
      assertThat(Said.of(row.thrown())).as(row.thrown().getClass().getName()).isEqualTo(row.said());
    }
  }
}
