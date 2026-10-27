package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SD-07, BR22.2: chỉ tên kết thúc bằng _load; kiểm tra thuần, không kết nối. */
class DatabaseNameGuardTest {

  @ParameterizedTest
  @ValueSource(strings = {"tpp_simulator_load", "perf_2026_load", "a_load", "x1_y2_load"})
  void acceptsLoadDatabaseNames(String name) {
    assertDoesNotThrow(() -> DatabaseNameGuard.validate(name));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "tpp_simulator",
        "tpp_simulator_test",
        "other_project",
        "tpp_simulator_load_old",
        "TPP_Simulator_load",
        "_load",
        "load",
        "1abc_load",
        "my load",
        "a\"b_load",
        "a;drop_load",
        "a'b_load",
        "tên_load",
        "a-b_load"
      })
  void rejectsEverythingElse(String name) {
    assertThrows(LoadRefusedException.class, () -> DatabaseNameGuard.validate(name));
  }

  @Test
  void rejectsNamesLongerThanSixtyThreeCharacters() {
    String sixtyThree = "a".repeat(63 - "_load".length()) + "_load";
    String sixtyFour = "a".repeat(64 - "_load".length()) + "_load";

    assertDoesNotThrow(() -> DatabaseNameGuard.validate(sixtyThree));
    assertThrows(LoadRefusedException.class, () -> DatabaseNameGuard.validate(sixtyFour));
  }

  @Test
  void theDefaultNameIsValid() {
    assertDoesNotThrow(() -> DatabaseNameGuard.validate(DatabaseNameGuard.DEFAULT_DATABASE));
  }
}
