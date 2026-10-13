package com.michaelgrundvig.frc.pitwall.world;

import com.michaelgrundvig.frc.pitwall.scene.Inspection;
import com.michaelgrundvig.frc.pitwall.scene.SceneBuilder;
import com.michaelgrundvig.frc.pitwall.scene.Shape;
import com.michaelgrundvig.frc.pitwall.scene.Style;
import com.michaelgrundvig.frc.world.field.Block;
import com.michaelgrundvig.frc.world.field.Element;
import com.michaelgrundvig.frc.world.physical.Polygon2d;
import java.util.List;
import java.util.stream.Collectors;
import org.wpilib.math.geometry.Translation2d;

/**
 * A field's elements as shapes: each block of each element a solid, its outline at its bottom and
 * again at its top with walls between. A click on any block of an element says the element's name
 * and size, as the field model has them.
 */
final class ElementShapes {
  private ElementShapes() {}

  /** Adds every element's blocks, in the field's frame: {@code element.<element>.<block>}. */
  static void describe(SceneBuilder scene, List<Element> elements) {
    for (int index = 0; index < elements.size(); index++) {
      Element element = elements.get(index);
      List<Block> blocks = element.blocks();
      for (int block = 0; block < blocks.size(); block++) {
        Shape.Mesh solid = scene.mesh("element." + index + "." + block, SceneBuilder.ROOT);
        prism(solid, blocks.get(block));
        solid.style(Style.STRUCTURE).inspect(() -> inspection(element));
      }
    }
  }

  /**
   * A block as triangles. Its corners are listed round its bottom and then round its top, so corner
   * {@code i} of the top is vertex {@code corners + i}. Each wall is two triangles between a side
   * of the bottom and the same side of the top, and the top and the bottom are each a fan of
   * triangles from their first corner, which fills an outline with no corner pointing in.
   */
  private static void prism(Shape.Mesh solid, Block block) {
    List<Translation2d> outline = block.outline().corners();
    int corners = outline.size();
    for (double height : new double[] {block.bottomMeters(), block.topMeters()}) {
      for (Translation2d corner : outline) {
        solid.vertex(corner.getX(), corner.getY(), height);
      }
    }
    for (int side = 0; side < corners; side++) {
      int next = (side + 1) % corners;
      solid.triangle(side, next, corners + next);
      solid.triangle(side, corners + next, corners + side);
    }
    for (int corner = 1; corner + 1 < corners; corner++) {
      solid.triangle(corners, corners + corner, corners + corner + 1);
      solid.triangle(0, corner + 1, corner);
    }
  }

  /**
   * What a click on an element says: how much floor it covers, along the field and across it, how
   * tall it is, how many blocks it is drawn as, and the tags that hang on it.
   */
  private static Inspection inspection(Element element) {
    Polygon2d footprint = element.footprint();
    double alongTheField = extent(footprint, Extent.ALONG_THE_FIELD);
    double acrossTheField = extent(footprint, Extent.ACROSS_THE_FIELD);
    Inspection inspection =
        Inspection.of(element.name())
            .detail(
                "Covers",
                WorldWords.meters(alongTheField)
                    + " along the field by "
                    + WorldWords.meters(acrossTheField)
                    + " across")
            .detail("Height", WorldWords.meters(element.heightMeters()));
    if (element.blocks().size() > 1) {
      inspection = inspection.detail("Drawn as", element.blocks().size() + " blocks");
    }
    if (!element.tags().isEmpty()) {
      String tags =
          element.tags().stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
      inspection = inspection.detail("Tags", tags);
    }
    return inspection.detail("From", "the field model: a robot runs into it");
  }

  /** Which way across a footprint is measured. */
  private enum Extent {
    ALONG_THE_FIELD,
    ACROSS_THE_FIELD
  }

  /** How far a footprint reaches from one side to the other, along the field's x or across it. */
  private static double extent(Polygon2d footprint, Extent extent) {
    double least = Double.POSITIVE_INFINITY;
    double most = Double.NEGATIVE_INFINITY;
    for (Translation2d corner : footprint.corners()) {
      double where =
          switch (extent) {
            case ALONG_THE_FIELD -> corner.getX();
            case ACROSS_THE_FIELD -> corner.getY();
          };
      least = Math.min(least, where);
      most = Math.max(most, where);
    }
    return most - least;
  }
}
