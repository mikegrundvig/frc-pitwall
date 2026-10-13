package com.michaelgrundvig.frc.pitwall.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a shape on the page says when it's clicked: a title, its details as labeled lines, and the
 * actions that can be taken about it, which end the panel as buttons:
 *
 * {@snippet :
 * Inspection said =
 *     Inspection.of(SightingSentences.title(camera, sighted.tag()))
 *         .detail("Verdict", SightingSentences.verdict(sighted, words));
 *         ...
 * return offersCalibration ? said.action(calibrate.get()) : said;
 * }
 *
 * <p>An action is one of the page's actions, by name ({@code pitwall.actions()}): it runs as it
 * would from Pit, with the same gate, and a name that isn't one isn't shown. A panel that ends in
 * something to do is what Pitwall is for.
 *
 * <p>In the simulator a shape may also be a part that can fail there ({@code
 * pitwall.simulator().components()}): a camera, the gyro, a wheel's module, the robot itself. It
 * names the part ({@link #component}), and the panel then ends in that part's faults, applied and
 * undone where it was clicked. On a robot there are no such parts, and the page is told of none.
 *
 * @param title what was clicked, in a few words
 * @param details what there is to know about it, in order
 * @param actions the names of the actions that can be taken about it
 * @param components the names of the simulator's parts it is, whose faults the panel offers
 */
public record Inspection(
    String title, List<Detail> details, List<String> actions, List<String> components) {
  /**
   * One line of what there is to know.
   *
   * @param label what it is, such as "Verdict"
   * @param value what it says, such as "rejected: gate 45.2 > 13.8"
   */
  public record Detail(String label, String value) {}

  public Inspection {
    details = List.copyOf(details);
    actions = List.copyOf(actions);
    components = List.copyOf(components);
  }

  /** An inspection with a title, and nothing else yet. */
  public static Inspection of(String title) {
    return new Inspection(title, List.of(), List.of(), List.of());
  }

  /** This, with a line of detail more. */
  public Inspection detail(String label, String value) {
    List<Detail> more = new ArrayList<>(details);
    more.add(new Detail(label, value));
    return new Inspection(title, more, actions, components);
  }

  /** This, ending in one more action, by its name on the page. */
  public Inspection action(String name) {
    List<String> more = new ArrayList<>(actions);
    more.add(name);
    return new Inspection(title, details, more, components);
  }

  /**
   * This, as one more of the simulator's parts, by the name its simulation declared it under. A
   * name the simulator has no part of is left out, as on a robot, where there are none.
   */
  public Inspection component(String name) {
    List<String> more = new ArrayList<>(components);
    more.add(name);
    return new Inspection(title, details, actions, more);
  }

  /** This, as one more of the simulator's parts, if there is such a part. */
  public Inspection component(Optional<String> name) {
    return name.map(this::component).orElse(this);
  }
}
