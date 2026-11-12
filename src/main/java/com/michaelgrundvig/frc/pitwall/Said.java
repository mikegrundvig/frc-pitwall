package com.michaelgrundvig.frc.pitwall;

/** What went wrong, as Pitwall writes it for people: one way, wherever an error is shown. */
public final class Said {
  private Said() {}

  /**
   * What a throwable says: its message, or, when it has none (a {@code NullPointerException} often
   * hasn't), its class and whatever else its {@code toString()} gives, so the line still says what
   * was thrown.
   */
  public static String of(Throwable thrown) {
    String message = thrown.getMessage();
    if (message == null || message.isBlank()) {
      return thrown.toString();
    }
    return message;
  }
}
