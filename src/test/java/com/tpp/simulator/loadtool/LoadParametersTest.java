package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR22.3: tự phân tích tham số, mặc định, từ chối sai kiểu hoặc ngoài khoảng. */
class LoadParametersTest {

  @Test
  void defaultsMatchTheDesign() {
    LoadParameters p = LoadParameters.parse(new String[0]);

    assertEquals(100_000, p.callbacks());
    assertEquals(30, p.days());
    assertEquals(3, p.services());
    assertEquals(LoadParameters.DEFAULT_SEED, p.seed());
    assertFalse(p.append());
    assertEquals("tpp_simulator_load", p.database());
  }

  @Test
  void parsesEveryOption() {
    LoadParameters p =
        LoadParameters.parse(
            new String[] {
              "--callbacks=500",
              "--seed=7",
              "--days=2",
              "--services=4",
              "--append",
              "--database=perf_load"
            });

    assertEquals(500, p.callbacks());
    assertEquals(7, p.seed());
    assertEquals(2, p.days());
    assertEquals(4, p.services());
    assertTrue(p.append());
    assertEquals("perf_load", p.database());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--callbacks=0", "--callbacks=-5", "--callbacks=abc", "--callbacks=", "--days=0",
        "--services=0", "--services=2147483648", "--seed=x", "--unknown=1", "callbacks=5",
        "--callbacks", "--append=true", "-x"
      })
  void rejectsBadOrUnknownParameters(String arg) {
    assertThrows(LoadRefusedException.class, () -> LoadParameters.parse(new String[] {arg}));
  }

  @Test
  void aLongUnknownArgumentIsShortenedInTheMessage() {
    LoadRefusedException e =
        assertThrows(
            LoadRefusedException.class,
            () -> LoadParameters.parse(new String[] {"--" + "x".repeat(200) + "=1"}));

    assertTrue(e.getMessage().length() < 120, e.getMessage());
  }

  @Test
  void aNegativeSeedIsAllowed() {
    assertEquals(-3, LoadParameters.parse(new String[] {"--seed=-3"}).seed());
  }
}
