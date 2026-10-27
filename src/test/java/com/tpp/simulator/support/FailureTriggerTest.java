package com.tpp.simulator.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Hạ tầng ép lỗi (RD-04): chỉ ép lỗi đúng mã dịch vụ, đóng là sạch, quét dọn được. */
class FailureTriggerTest extends AbstractIntegrationTest {

  private void insertCallback(String code, long versionId) {
    jdbc.update(
        "INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,"
            + " http_status_returned, request_headers_masked, mapping_field_results)"
            + " VALUES (?, ?, now(), 'SUCCESS', 200, '{}', '{}')",
        code,
        versionId);
  }

  @Test
  void forcesFailureOnlyForTheNamedServiceAndCleansUpOnClose() {
    long a = insertServiceRow("FT_A");
    long b = insertServiceRow("FT_B");

    try (FailureTrigger ignored = FailureTrigger.on("callbacks", "INSERT", "FT_A")) {
      assertThrows(DataAccessException.class, () -> insertCallback("FT_A", a));
      insertCallback("FT_B", b);
    }

    insertCallback("FT_A", a);
    assertEquals(2, count("callbacks"));
    assertEquals(0, FailureTrigger.count());
  }

  @Test
  void canRestrictTheFailureToOneOutcome() {
    long a = insertServiceRow("FT_O");

    try (FailureTrigger ignored = FailureTrigger.on("callbacks", "INSERT", "FT_O", "REJECTED")) {
      insertCallback("FT_O", a);
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,"
                      + " http_status_returned, request_headers_masked, mapping_field_results)"
                      + " VALUES (?, ?, now(), 'REJECTED', 401, '{}', '{}')",
                  "FT_O",
                  a));
    }
  }

  @Test
  void sweepRemovesTriggersThatWereLeftBehind() {
    insertServiceRow("FT_S");
    FailureTrigger.on("callbacks", "INSERT", "FT_S");
    assertEquals(1, FailureTrigger.count());

    int dropped = FailureTrigger.sweep();

    assertEquals(2, dropped);
    assertEquals(0, FailureTrigger.count());
  }

  @Test
  void rejectsParametersThatCouldInjectSql() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FailureTrigger.on("callbacks", "INSERT", "X'; DROP TABLE callbacks; --"));
    assertThrows(
        IllegalArgumentException.class, () -> FailureTrigger.on("pg_user", "INSERT", "OK"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FailureTrigger.on("callbacks", "INSERT", "OK", "SUCCESS' OR '1'='1"));
  }
}
