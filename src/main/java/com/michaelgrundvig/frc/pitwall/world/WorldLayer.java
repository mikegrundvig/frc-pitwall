package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.robot.RobotSession;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.Layer;
import com.michaelgrundvig.frc.pitwall.scene.SceneState;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One of the World view's layers that moves: what it says is a function of one loop's {@link
 * Moment}, and of the robot and the field it was made with. So it is drawn the same way whichever
 * loop it is: the newest, live, or the one the page's cursor is on.
 */
abstract class WorldLayer implements Layer {
  /** The session it follows once it is on a page, or null while it is only a function. */
  private @Nullable Newest newest;

  /** The loop it last drew live, so that a loop is drawn once. */
  private long drawn = -1;

  /** Says what moves, as one loop had it: frames' poses, values, styles, shapes of the moment. */
  abstract void draw(Moment moment, SceneState state);

  /**
   * Says what moves in the newest loop, live. It is {@link #draw} unless a layer has a cheaper way
   * to say the same to a state that keeps what it was told.
   */
  void drawLive(Moment moment, SceneState state) {
    draw(moment, state);
  }

  /** What a click on one of its shapes says of a loop; empty for a shape that is not its own. */
  Optional<Inspection> inspect(Moment moment, String shapeId) {
    return Optional.empty();
  }

  /** Has it follow a session: its newest loop live, and any loop it holds when asked. */
  final void follow(Newest session) {
    newest = session;
  }

  /** The newest loop of the session it follows, if it follows one that has taken a loop. */
  final Optional<Moment> newestMoment() {
    Newest following = newest;
    return following == null ? Optional.empty() : following.session().newest();
  }

  @Override
  public final void update(SceneState state) {
    Newest following = newest;
    if (following == null) {
      return;
    }
    Moment moment = following.read();
    if (moment == null || following.index() == drawn) {
      return;
    }
    drawn = following.index();
    drawLive(moment, state);
  }

  @Override
  public final Optional<Recorded> recorded() {
    Newest following = newest;
    if (following == null) {
      return Optional.empty();
    }
    RobotSession session = following.session();
    return Optional.of(
        new Recorded() {
          @Override
          public boolean update(long loop, SceneState state) {
            Optional<Moment> moment = session.moment(loop);
            moment.ifPresent(held -> draw(held, state));
            return moment.isPresent();
          }

          @Override
          public Optional<Inspection> inspect(long loop, String shapeId) {
            return session.moment(loop).flatMap(held -> WorldLayer.this.inspect(held, shapeId));
          }
        });
  }
}
