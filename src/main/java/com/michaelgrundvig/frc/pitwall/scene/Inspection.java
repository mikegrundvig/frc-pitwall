package com.michaelgrundvig.frc.pitwall.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>A shape may instead offer one such fault ready to apply, with its numbers already set ({@link
 * #offer}): a start that sets the robot down there. It ends the panel as a button, and a robot's
 * page is told of none either.
 *
 * @param title what was clicked, in a few words
 * @param details what there is to know about it, in order
 * @param actions the names of the actions that can be taken about it
 * @param components the names of the simulator's parts it is, whose faults the panel offers
 * @param offers the faults of the simulator's parts the panel offers to apply as they stand
 */
public record Inspection(
    String title,
    List<Detail> details,
    List<String> actions,
    List<String> components,
    List<FaultOffer> offers) {
  /**
   * One line of what there is to know.
   *
   * @param label what it is, such as "Verdict"
   * @param value what it says, such as "rejected: gate 45.2 > 13.8"
   */
  public record Detail(String label, String value) {}

  /**
   * A fault of one of the simulator's parts, offered as a button with its numbers already set.
   * Pressing it applies the fault as the Sim view would with those numbers, by the part's and the
   * fault's names as the simulation declared them.
   *
   * @param label what the button says: "Place the robot here"
   * @param component the simulator's part, by name
   * @param fault the part's fault, by name
   * @param values the fault's numbers by name, in the units it takes them in
   */
  public record FaultOffer(
      String label, String component, String fault, Map<String, Double> values) {
    public FaultOffer {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
  }

  public Inspection {
    details = List.copyOf(details);
    actions = List.copyOf(actions);
    components = List.copyOf(components);
    offers = List.copyOf(offers);
  }

  /** An inspection with a title, and nothing else yet. */
  public static Inspection of(String title) {
    return new Inspection(title, List.of(), List.of(), List.of(), List.of());
  }

  /** This, with a line of detail more. */
  public Inspection detail(String label, String value) {
    List<Detail> more = new ArrayList<>(details);
    more.add(new Detail(label, value));
    return new Inspection(title, more, actions, components, offers);
  }

  /** This, ending in one more action, by its name on the page. */
  public Inspection action(String name) {
    List<String> more = new ArrayList<>(actions);
    more.add(name);
    return new Inspection(title, details, more, components, offers);
  }

  /**
   * This, as one more of the simulator's parts, by the name its simulation declared it under. A
   * name the simulator has no part of is left out, as on a robot, where there are none.
   */
  public Inspection component(String name) {
    List<String> more = new ArrayList<>(components);
    more.add(name);
    return new Inspection(title, details, actions, more, offers);
  }

  /** This, as one more of the simulator's parts, if there is such a part. */
  public Inspection component(Optional<String> name) {
    return name.map(this::component).orElse(this);
  }

  /** This, ending in one more fault to apply as it stands. */
  public Inspection offer(FaultOffer offer) {
    List<FaultOffer> more = new ArrayList<>(offers);
    more.add(offer);
    return new Inspection(title, details, actions, components, more);
  }

  /** This, ending in one more fault to apply as it stands, if there is one. */
  public Inspection offer(Optional<FaultOffer> offer) {
    return offer.map(this::offer).orElse(this);
  }
}
