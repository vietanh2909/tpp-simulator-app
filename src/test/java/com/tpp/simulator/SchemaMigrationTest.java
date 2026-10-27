package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * NFR1.2, NFR2.5, BR1.2: bản chuyển V1 đã áp dụng trên PostgreSQL thật và các ràng buộc hoạt động.
 */
class SchemaMigrationTest extends AbstractIntegrationTest {

  private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
  private static final String V1_SHA256 =
      "3f6893f349fc703eb46e8f3973236d6ad558ba0568145d465209e84cfbceb3ee";

  private void insertService(String code) {
    jdbc.update(
        "INSERT INTO services (service_code, name, enabled, deleted, auth_type, current_version_number,"
            + " created_at, updated_at) VALUES (?, 'n', true, false, 'NONE', 1, now(), now())",
        code);
  }

  private List<String> indexNames(String table) {
    return jdbc.queryForList(
        "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ?",
        String.class,
        table);
  }

  @Test
  void flywayAppliedV1Successfully() {
    List<String> versions =
        jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
            String.class);

    assertTrue(versions.contains("1"), versions.toString());
  }

  @Test
  void flywayAppliedV1ThenV2Successfully() {
    List<String> versions =
        jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL"
                + " ORDER BY installed_rank",
            String.class);

    assertEquals(List.of("1", "2"), versions);
  }

  @Test
  void v2AddsTheFourListFilterIndexes() {
    assertTrue(indexNames("callbacks").contains("ix_callbacks_received"));
    assertTrue(indexNames("callbacks").contains("ix_callbacks_outcome_received"));
    assertTrue(indexNames("callbacks").contains("ix_callbacks_rejection_received"));
    assertTrue(indexNames("transactions").contains("ix_transactions_service_latest"));
    String partial =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_callbacks_rejection_received'",
            String.class);
    assertTrue(partial.contains("WHERE (rejection_reason IS NOT NULL)"), partial);
  }

  @Test
  void v1MigrationIsNeverEdited() throws Exception {
    byte[] raw = Files.readAllBytes(MIGRATIONS.resolve("V1__init_schema.sql"));
    String normalized = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));

    assertEquals(V1_SHA256, HexFormat.of().formatHex(digest));
  }

  @Test
  void noMigrationCreatesAnExtension() throws Exception {
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      for (Path file : files.toList()) {
        assertFalse(
            Files.readString(file).toUpperCase(Locale.ROOT).contains("CREATE EXTENSION"),
            file.toString());
      }
    }
  }

  @Test
  void fourTablesExist() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
            String.class);

    assertTrue(
        tables.containsAll(
            List.of("services", "service_config_versions", "callbacks", "transactions")));
  }

  @Test
  void serviceCodeIsUniqueIgnoringCase() {
    insertService("THU_HO");

    assertThrows(DuplicateKeyException.class, () -> insertService("thu_ho"));
    assertThrows(DuplicateKeyException.class, () -> insertService("Thu_Ho"));
    insertService("CHI_HO");
  }

  @Test
  void transactionKeyIsTheCompositeOfServiceCodeAndTransactionId() {
    List<String> primaryKeyColumns =
        jdbc.queryForList(
            "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
                + " AND a.attnum = ANY(i.indkey) WHERE i.indrelid = 'transactions'::regclass"
                + " AND i.indisprimary ORDER BY array_position(i.indkey::int[], a.attnum::int)",
            String.class);

    assertEquals(List.of("service_code", "transaction_id"), primaryKeyColumns);
  }

  @Test
  void callbacksHaveNoForeignKeyToTransactions() {
    Integer fromCallbacksToTransactions =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                + " AND conrelid = 'callbacks'::regclass AND confrelid = 'transactions'::regclass",
            Integer.class);
    Integer latestCallbackFk =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                + " AND conrelid = 'transactions'::regclass AND confrelid = 'callbacks'::regclass",
            Integer.class);

    assertEquals(0, fromCallbacksToTransactions);
    assertEquals(1, latestCallbackFk);
  }

  @Test
  void requiredIndexesExist() {
    assertTrue(indexNames("services").contains("ux_services_service_code_lower"));
    assertTrue(indexNames("callbacks").contains("ix_callbacks_service_received"));
    assertTrue(indexNames("callbacks").contains("ix_callbacks_service_txn_received"));
    assertTrue(indexNames("transactions").contains("ix_transactions_latest"));
  }

  @Test
  void sortIndexMatchesTheListOrder() {
    String definition =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_transactions_latest'",
            String.class);

    assertTrue(definition.contains("latest_received_at DESC"), definition);
    assertTrue(definition.contains("latest_callback_id DESC"), definition);
  }

  @Test
  void configVersionsAreUniquePerServiceAndVersionNumber() {
    insertService("THU_HO");
    String sql =
        "INSERT INTO service_config_versions (service_code, version_number, transaction_id_path,"
            + " status_path, timestamp_format, default_time_zone, response_http_status, response_body,"
            + " created_at) VALUES ('THU_HO', 1, '$.a', '$.b', 'ISO_8601', 'UTC', 200, '{}', now())";
    jdbc.update(sql);

    assertThrows(DuplicateKeyException.class, () -> jdbc.update(sql));
  }

  @Test
  void checkConstraintsRejectInvalidValues() {
    insertService("THU_HO");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO service_config_versions (service_code, version_number, transaction_id_path,"
                    + " status_path, timestamp_format, default_time_zone, response_http_status,"
                    + " response_body, created_at) VALUES ('THU_HO', 2, '$.a', '$.b', 'ISO_8601',"
                    + " 'UTC', 700, '{}', now())"));
    assertFalse(indexNames("services").isEmpty());
  }
}
