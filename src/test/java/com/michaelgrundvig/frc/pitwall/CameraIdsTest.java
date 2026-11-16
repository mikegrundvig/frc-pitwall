package com.michaelgrundvig.frc.pitwall;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.pitwall.robot.TestRobot;
import com.michaelgrundvig.frc.world.robot.CameraConfig;
import com.michaelgrundvig.frc.world.robot.CameraStationBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A camera's id fits the World view's names at 52 characters, and one longer is said by name. */
class CameraIdsTest {
  private static CameraConfig named(String name) {
    CameraConfig any = TestRobot.definition().cameras().get(0);
    return CameraStationBuilder.builder(any.station()).name(name).build().holds(any.camera());
  }

  @Test
  void aCameraWhoseIdIsTooLongIsNamed() {
    record Row(String what, List<CameraConfig> cameras, String why) {}
    String tooLong = "A".repeat(CameraIds.LONGEST + 1);
    List<Row> rows =
        List.of(
            new Row("no cameras", List.of(), ""),
            new Row("52 characters", List.of(named("a".repeat(CameraIds.LONGEST))), ""),
            new Row(
                "53 characters, after one that fits",
                List.of(named("front"), named(tooLong)),
                "camera \""
                    + tooLong
                    + "\"'s name is too long as an id (\""
                    + "a".repeat(53)
                    + "\", 53 characters; at most 52): shorten it"));
    for (Row row : rows) {
      String why = CameraIds.whyTooLong(row.cameras());
      if (row.why().isEmpty()) {
        assertThat(why).as(row.what()).isNull();
        continue;
      }
      assertThat(why).as(row.what()).isEqualTo(row.why());
    }
  }
}
