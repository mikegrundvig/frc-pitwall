package com.michaelgrundvig.frc.pitwall;

import java.util.List;

/**
 * An answer that says what went wrong, as problem details (RFC 9457): thrown by a route's handler,
 * and written by {@link Responses#problem}.
 */
final class Problem extends RuntimeException {
  private static final long serialVersionUID = 1;

  private final int status;
  private final String title;

  /** For a refused change to tunables, each reason with the values it concerns. */
  private final transient List<RequestOutcome.Reason> reasons;

  /** For a busy device, when to ask again, in seconds: 0 for nothing to say. */
  private int retryAfterSeconds;

  Problem(int status, String title, String detail) {
    this(status, title, detail, List.of());
  }

  Problem(int status, String title, String detail, List<RequestOutcome.Reason> reasons) {
    super(detail);
    this.status = status;
    this.title = title;
    this.reasons = reasons;
  }

  /** This problem, saying when to ask again. */
  Problem retryAfter(int seconds) {
    retryAfterSeconds = seconds;
    return this;
  }

  int status() {
    return status;
  }

  String title() {
    return title;
  }

  List<RequestOutcome.Reason> reasons() {
    return reasons;
  }

  int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
