package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * NFR1.26, NFR2.29, NFR3.26, LC-10: nạp 500 callback hai dịch vụ (có LOAD_02 là API_KEY) bằng cách
 * gọi trực tiếp {@code LoadRunner.run} trên database kiểm thử (ngoại lệ có kiểm soát của NFR3.26);
 * lớp cơ sở dọn dữ liệu trước mỗi kiểm thử nên dịch vụ LOAD_ được dọn trước và sau.
 */
class LoadRunnerSmallRunTest extends AbstractIntegrationTest {

  @Autowired private ServiceCatalog catalog;
  @Autowired private CallbackIngestionService ingestion;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private Clock appClock;

  private final ByteArrayOutputStream progress = new ByteArrayOutputStream();

  private LoadRunner runner() {
    return new LoadRunner(
        catalog,
        ingestion,
        jdbcClient,
        appClock,
        new PrintStream(progress, true, StandardCharsets.UTF_8));
  }

  private static LoadParameters parameters(int callbacks, int services, boolean append) {
    return new LoadParameters(callbacks, 20_261_019L, 2, services, append, "tpp_simulator_test");
  }

  @Test
  void aSmallRunWritesEveryReceiptThroughTheRealIngestionRules() {
    LoadReport report = runner().run(parameters(500, 2, false));

    assertEquals(0, report.exitCode(), String.valueOf(report.failure()));
    assertEquals(500, report.callbacksInDatabase());
    assertEquals(500, report.writtenThisRun());
    assertEquals(count("callbacks"), report.callbacksInDatabase());
    assertEquals(count("transactions"), report.transactionsInDatabase());
    assertTrue(count("transactions") > 0);
    assertEquals(2, count("services"));
    assertEquals(
        List.of("NONE", "API_KEY"),
        jdbc.queryForList("SELECT auth_type FROM services ORDER BY service_code", String.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM services WHERE enabled AND NOT deleted", Integer.class));
  }

  @Test
  void allSixRejectionReasonsAndAllOutcomesAreProduced() {
    runner().run(parameters(500, 2, false));

    assertEquals(
        Set.of(
            "SERVICE_DISABLED",
            "SERVICE_DELETED",
            "MISSING_API_KEY",
            "INVALID_API_KEY",
            "UNSUPPORTED_CONTENT_TYPE",
            "INVALID_JSON"),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT DISTINCT rejection_reason FROM callbacks WHERE outcome = 'REJECTED'",
                String.class)));
    assertEquals(
        Set.of("SUCCESS", "MAPPING_ERROR", "REJECTED"),
        Set.copyOf(jdbc.queryForList("SELECT DISTINCT outcome FROM callbacks", String.class)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE outcome = 'REJECTED' AND transaction_id IS NOT NULL",
            Integer.class));
  }

  @Test
  void rejectedReceiptsNeverEnterTransactionsAndTheKeyIsMasked() {
    runner().run(parameters(500, 2, false));

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM transactions t JOIN callbacks c ON c.record_id = t.latest_callback_id"
                + " WHERE c.outcome = 'REJECTED'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE request_headers_masked::text LIKE ?",
            Integer.class,
            "%" + LoadServiceDefinitions.API_KEY_VALUE + "%"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE request_headers_masked::text LIKE '%wrong-key%'",
            Integer.class));
    assertTrue(
        jdbc.queryForObject(
                "SELECT count(*) FROM callbacks WHERE request_headers_masked->>'X-Api-Key' = '***'",
                Integer.class)
            > 0);
  }

  @Test
  void receivedAtIsSpreadOverTheRequestedDaysAndNotTheRunTime() {
    runner().run(parameters(500, 2, false));

    assertTrue(
        jdbc.queryForObject(
            "SELECT max(received_at) - min(received_at) > interval '40 hours' FROM callbacks",
            Boolean.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT min(received_at) >= now() - interval '2 days 1 minute' FROM callbacks",
            Boolean.class));
  }

  @Test
  void theSchemaVersionMatchesTheNewestMigrationAndTheTablesWereAnalyzed() {
    runner().run(parameters(500, 2, false));

    assertEquals(
        "2",
        jdbc.queryForObject(
            "SELECT max(version::int)::text FROM flyway_schema_history", String.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT last_analyze FROM pg_stat_user_tables WHERE relname = 'callbacks'",
            java.sql.Timestamp.class));
  }

  @Test
  void runningAgainWithoutAppendIsRefusedAndWritesNothing() {
    runner().run(parameters(100, 2, false));
    int before = count("callbacks");

    LoadReport second = runner().run(parameters(100, 2, false));

    assertEquals(2, second.exitCode());
    assertTrue(second.failure().contains("LOAD_01"), second.failure());
    assertEquals(before, count("callbacks"));
  }

  @Test
  void appendReusesExistingServicesAndAddsMoreReceipts() {
    runner().run(parameters(100, 2, false));

    LoadReport second = runner().run(parameters(100, 2, true));

    assertEquals(0, second.exitCode(), String.valueOf(second.failure()));
    assertEquals(200, count("callbacks"));
    assertEquals(2, count("services"));
    assertEquals(
        1, jdbc.queryForObject("SELECT max(current_version_number) FROM services", Integer.class));
  }

  @Test
  void appendOnlyCreatesTheMissingServicesInTheRange() {
    runner().run(parameters(100, 2, false));

    LoadReport grown = runner().run(parameters(200, 3, true));

    assertEquals(0, grown.exitCode(), String.valueOf(grown.failure()));
    assertEquals(3, count("services"));
    assertEquals(300, count("callbacks"));
  }

  @Test
  void aServiceBeyondTheRequestedRangeIsNeverTouched() {
    runner().run(parameters(100, 3, false));
    int beforeForThird =
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE service_code = 'LOAD_03'", Integer.class);

    runner().run(parameters(100, 2, true));

    assertEquals(
        beforeForThird,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE service_code = 'LOAD_03'", Integer.class));
  }

  @Test
  void withOneServiceTheApiKeyVariantMakesAllSixReasonsAppearWithoutChangingTheService() {
    LoadReport report = runner().run(parameters(500, 1, false));

    assertEquals(0, report.exitCode(), String.valueOf(report.failure()));
    assertEquals(
        6,
        jdbc.queryForObject(
            "SELECT count(DISTINCT rejection_reason) FROM callbacks WHERE outcome = 'REJECTED'",
            Integer.class));
    assertEquals("NONE", jdbc.queryForObject("SELECT auth_type FROM services", String.class));
    assertNull(jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
  }

  @Test
  void progressIsReported() {
    runner().run(parameters(100, 2, false));

    assertTrue(progress.toString(StandardCharsets.UTF_8).contains("Progress: 100/100"));
    assertFalse(
        progress.toString(StandardCharsets.UTF_8).contains(LoadServiceDefinitions.API_KEY_VALUE));
  }

  private static void assertNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNull(value);
  }
}
