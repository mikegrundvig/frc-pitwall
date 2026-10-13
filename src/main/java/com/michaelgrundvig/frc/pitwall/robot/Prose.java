package com.michaelgrundvig.frc.pitwall.robot;

/**
 * What the runtime logs about a joint in words, read here and nowhere else.
 *
 * <p>Today the runtime logs where a joint stands, who asked it and how its last move ended as
 * sentences. The view shows those as they are, and needs only two facts out of them: whether the
 * joint knows where it is, and whether a command asked anything of it. Both are read from the
 * sentence's first word, here. When the runtime logs these as structured records, this is the one
 * class to change: {@link #story} then reads the records, and the view does not change.
 *
 * <p>What holds a joint is not read from the log at all. The view works it out from the positions
 * and sensors of the moment with the model's own rules ({@link Causes}).
 */
final class Prose {
  private Prose() {}

  /** What {@code State} says while a joint knows where it is and may be driven to a position. */
  private static final String READY = "READY";

  /** What {@code Command} says in a loop in which no command asked anything of the joint. */
  private static final String IDLE = "idle";

  /** Whether a joint knows where it is. */
  enum Standing {
    /** It has found where it is, and moves on it are taken. */
    READY,
    /** It is homing, has not homed, or is faulted. */
    NOT_READY
  }

  /** Whether a command asked anything of a joint in a loop. */
  enum Asked {
    BY_A_COMMAND,
    BY_NOTHING
  }

  /**
   * A joint's loop as the runtime's words tell it.
   *
   * @param standing whether it knows where it is
   * @param state where it stands, as logged: "READY", "HOMING: toward its lower stop"
   * @param asked whether a command asked anything of it
   * @param command who asked, as logged: a command's name, "outside any command", or "idle"
   * @param goalName the name of what was asked for, or empty
   * @param lastMove how the last move on it ended, as logged, or empty before any has
   */
  record Story(
      Standing standing,
      String state,
      Asked asked,
      String command,
      String goalName,
      String lastMove) {}

  /** The story of one joint in one loop. */
  static Story story(Moment.OfJoint joint) {
    Standing standing = joint.state().equals(READY) ? Standing.READY : Standing.NOT_READY;
    boolean nothingAsked = joint.command().isEmpty() || joint.command().equals(IDLE);
    Asked asked = nothingAsked ? Asked.BY_NOTHING : Asked.BY_A_COMMAND;
    return new Story(
        standing, joint.state(), asked, joint.command(), joint.goalName(), joint.outcome());
  }
}
