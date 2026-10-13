package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.scene.Layer;
import com.michaelgrundvig.frc.pitwall.scene.Legend;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One of the World view's layers in which nothing moves of itself: shapes described once, in frames
 * another layer moves or in none. It is the same in every loop of the robot's record, so the page
 * can show or hide it at any loop, as one thing.
 */
final class StillLayer implements Layer {
  private final String id;
  private final String title;
  private final Consumer<SceneBuilder> describe;
  private final List<Legend> legend;
  private final Supplier<Object> configuration;

  /**
   * @param describe adds the layer's shapes
   * @param legend what its marks mean
   * @param configuration what its shapes are drawn from: when it changes, they are described again
   */
  StillLayer(
      String id,
      String title,
      Consumer<SceneBuilder> describe,
      List<Legend> legend,
      Supplier<Object> configuration) {
    this.id = id;
    this.title = title;
    this.describe = describe;
    this.legend = List.copyOf(legend);
    this.configuration = configuration;
  }

  /** A layer drawn from what never changes while the robot runs. */
  StillLayer(String id, String title, Consumer<SceneBuilder> describe, List<Legend> legend) {
    this(id, title, describe, legend, () -> "");
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  public void describe(SceneBuilder scene) {
    describe.accept(scene);
  }

  @Override
  public Object configuration() {
    return configuration.get();
  }

  @Override
  public List<Legend> legend() {
    return legend;
  }

  @Override
  public Optional<Recorded> recorded() {
    return Optional.of(Recorded.STILL);
  }
}
