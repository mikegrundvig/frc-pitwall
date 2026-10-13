package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import org.jspecify.annotations.Nullable;

/**
 * The newest loop of the robot's session, read once for all of the World view's layers: each asks
 * for it in the robot loop while a page shows the view, and the session's memory is read only when
 * it has taken another loop.
 *
 * <p>The robot loop's: not thread-safe.
 */
final class Newest {
  private final RobotSession session;
  private long index = -1;
  private @Nullable Moment moment;

  Newest(RobotSession session) {
    this.session = session;
  }

  /** The newest loop's moment, or null before the session has taken a loop. */
  @Nullable Moment read() {
    long newest = session.taken() - 1;
    if (newest != index) {
      moment = session.moment(newest).orElse(null);
      index = newest;
    }
    return moment;
  }

  /** The index of the loop last read. */
  long index() {
    return index;
  }

  RobotSession session() {
    return session;
  }
}
