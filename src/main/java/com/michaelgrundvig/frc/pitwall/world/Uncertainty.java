package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.robot.Moment;
import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.SceneState;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import java.util.Locale;
import java.util.Optional;

/**
 * How sure localization was of where the robot is. It is an ellipse around the estimate that
 * reaches two standard deviations of the position's uncertainty each way. The ellipse is dashed,
 * since it's a bound and not a thing, and it is drawn in the color of what's wrong while the
 * estimate is lost or the gyro is.
 */
final class Uncertainty extends WorldLayer {
  /** The ellipse's frame on the page, which sits at the estimate, turned along its longest axis. */
  private static final String FRAME = "uncertainty";

  /** The ellipse itself, which a click asks about. */
  private static final String ELLIPSE = "uncertainty.ellipse";

  /** How many standard deviations the ellipse reaches. */
  private static final double SIGMAS = 2;

  /**
   * The largest radius drawn, in meters. An estimate not yet placed is uncertain across the whole
   * field, and beyond.
   */
  private static final double MAX_RADIUS = 20;

  /** What a click says in place of something the localizer does not report. */
  private static final String NOT_REPORTED = "not reported by this localizer";

  @Override
  public String id() {
    return "uncertainty";
  }

  @Override
  public String title() {
    return "Uncertainty (2σ)";
  }

  @Override
  public void describe(SceneBuilder scene) {
    scene
        .frame(FRAME, SceneBuilder.ROOT)
        .deadband(FRAME, RobotLayer.DEADBAND_METERS, RobotLayer.DEADBAND_RADIANS)
        .value("uncertainty.major", 0.005)
        .value("uncertainty.minor", 0.005);
    scene
        .cylinder(ELLIPSE, FRAME)
        .radii("uncertainty.major", "uncertainty.minor")
        .at(0, 0, 0.03)
        .outline()
        .style(Style.ACCENT.dashed())
        .inspect(this::inspectionNow);
  }

  /**
   * Places the ellipse at the estimate and sizes it by the position's covariance.
   *
   * <p>The covariance of the position is the matrix {@code [xx xy; xy yy]}. Its eigenvectors are
   * the ellipse's axes, and its eigenvalues are the variances along them:
   *
   * <pre>
   * mean   = (xx + yy) / 2
   * spread = sqrt(((xx - yy) / 2)² + xy²)
   * variance along the longer axis  = mean + spread
   * variance along the shorter axis = mean - spread
   * angle of the longer axis        = atan2(2 xy, xx - yy) / 2
   * </pre>
   */
  @Override
  void draw(Moment moment, SceneState state) {
    Optional<Moment.OfEstimate> known = moment.world().estimate();
    if (known.isEmpty()) {
      return;
    }
    Moment.OfEstimate estimate = known.get();
    double x = estimate.pose().getX();
    double y = estimate.pose().getY();
    if (estimate.spread().isEmpty()) {
      // A localizer that doesn't say how sure it is has no ellipse: none is drawn, of any size.
      state.pose(FRAME, x, y, 0);
      state.value("uncertainty.major", 0);
      state.value("uncertainty.minor", 0);
      return;
    }
    Moment.Spread covariance = estimate.spread().get();
    double mean = (covariance.xx() + covariance.yy()) / 2;
    double spread = Math.hypot((covariance.xx() - covariance.yy()) / 2, covariance.xy());
    double angle = 0.5 * Math.atan2(2 * covariance.xy(), covariance.xx() - covariance.yy());
    state.pose(FRAME, x, y, angle);
    state.value("uncertainty.major", radius(mean + spread));
    state.value("uncertainty.minor", radius(mean - spread));
    // A localizer that doesn't say what its pose rests on is drawn as described: nothing here knows
    // of anything wrong.
    if (estimate.rests().isWrong()) {
      state.style(ELLIPSE, Style.BAD.dashed());
    } else {
      // Pitwall takes null for "as it was described".
      state.style(ELLIPSE, null);
    }
  }

  /**
   * How far the ellipse reaches along an axis with this variance: two standard deviations, and no
   * farther than the largest radius drawn. Rounding can leave the shorter axis's variance a hair
   * below zero, which is taken as zero.
   */
  private static double radius(double variance) {
    return Math.min(MAX_RADIUS, SIGMAS * Math.sqrt(Math.max(0, variance)));
  }

  @Override
  Optional<Inspection> inspect(Moment moment, String shapeId) {
    if (!shapeId.equals(ELLIPSE)) {
      return Optional.empty();
    }
    return Optional.of(inspection(moment.world().estimate()));
  }

  /** What a click on the ellipse says live: of the session's newest loop. */
  private Inspection inspectionNow() {
    return inspection(newestMoment().flatMap(moment -> moment.world().estimate()));
  }

  /** What a click on the ellipse says: how sure, along which way, and what it rests on. */
  private static Inspection inspection(Optional<Moment.OfEstimate> known) {
    if (known.isEmpty()) {
      return Inspection.of("Uncertainty").detail("Estimate", "not drawn yet");
    }
    Moment.OfEstimate estimate = known.get();
    String basedOn = NOT_REPORTED;
    if (estimate.rests() != Moment.Rests.NOT_REPORTED) {
      basedOn = estimate.rests().words();
    }
    Inspection said = Inspection.of("Uncertainty of the estimate").detail("Based on", basedOn);
    String cameras = estimate.tally().map(Uncertainty::cameras).orElse(NOT_REPORTED);
    if (estimate.spread().isEmpty()) {
      said =
          said.detail("Position, 1σ", NOT_REPORTED)
              .detail("Heading, 1σ", NOT_REPORTED)
              .detail("Cameras", cameras);
      return said.detail("Drawn", "nothing: the localizer reports no uncertainty");
    }
    Moment.Spread covariance = estimate.spread().get();
    return said.detail(
            "Position, 1σ",
            String.format(
                Locale.ROOT,
                "%.1f cm along x, %.1f cm along y, %.1f cm the least sure way",
                100 * Math.sqrt(covariance.xx()),
                100 * Math.sqrt(covariance.yy()),
                100 * covariance.largestStdDev()))
        .detail("Heading, 1σ", headingSpread(covariance))
        .detail("Cameras", cameras)
        .detail("Drawn", "two standard deviations across, about the estimate");
  }

  /** How the cameras' measurements have fared. */
  private static String cameras(Moment.Tally tally) {
    return String.format(
        Locale.ROOT,
        "%d accepted, %d rejected; last accepted %s",
        tally.accepted(),
        tally.rejected(),
        lastAccepted(tally));
  }

  private static String headingSpread(Moment.Spread covariance) {
    if (!Double.isFinite(covariance.heading())) {
      return "unknown";
    }
    return String.format(Locale.ROOT, "%.2f°", Math.toDegrees(Math.sqrt(covariance.heading())));
  }

  /**
   * How long ago the last accepted image was taken. The tally carries an infinite age until one has
   * been accepted.
   */
  private static String lastAccepted(Moment.Tally tally) {
    if (!Double.isFinite(tally.visionAgeSeconds())) {
      return "never";
    }
    return String.format(Locale.ROOT, "%.1f s ago", tally.visionAgeSeconds());
  }
}
