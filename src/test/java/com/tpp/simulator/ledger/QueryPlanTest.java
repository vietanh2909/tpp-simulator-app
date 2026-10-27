package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NFR1.2: các truy vấn khóa của U1 dùng chỉ mục, không quét toàn bảng. Dữ liệu đủ lớn để bộ lập kế
 * hoạch chọn chỉ mục và đã ANALYZE; chạy EXPLAIN trên đúng câu SQL mà CallbackLedger dùng (thay
 * tham số bằng giá trị mẫu).
 */
class QueryPlanTest extends AbstractIntegrationTest {

  private static final int SERVICES = 5_000;
  private static final int CALLBACKS = 40_000;

  @BeforeEach
  void loadVolume() {
    jdbc.update(
        "INSERT INTO services (service_code, name, enabled, deleted, auth_type, current_version_number,"
            + " created_at, updated_at)"
            + " SELECT 'S' || g, 'n', true, false, 'NONE', 1, now(), now()"
            + " FROM generate_series(0, ?) g",
        SERVICES - 1);
    jdbc.update(
        "INSERT INTO service_config_versions (service_code, version_number, transaction_id_path,"
            + " status_path, timestamp_path, timestamp_format, default_time_zone, response_http_status,"
            + " response_body, created_at)"
            + " SELECT service_code, 1, '$.a', '$.b', '$.c', 'ISO_8601', 'UTC', 200, '{}', now() FROM services");
    jdbc.update(
        "INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,"
            + " http_status_returned, request_headers_masked, payload_json, transaction_id, status,"
            + " mapping_field_results)"
            + " SELECT 'S' || (g % ?), (g % ?) + 1, now() - (g || ' seconds')::interval, 'SUCCESS', 200,"
            + " '{}', '{}', 'T' || g, 'ok', '{}'"
            + " FROM generate_series(0, ?) g",
        SERVICES, SERVICES, CALLBACKS - 1);
    jdbc.update(
        "INSERT INTO transactions (service_code, transaction_id, latest_status, latest_received_at,"
            + " latest_callback_id, latest_has_mapping_error, callback_count)"
            + " SELECT service_code, transaction_id, status, received_at, record_id, false, 1"
            + " FROM callbacks");
    jdbc.execute("ANALYZE");
  }

  private String plan(String sql) {
    List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
    return String.join("\n", lines);
  }

  private static void assertNoSeqScan(String plan, String table) {
    assertFalse(plan.contains("Seq Scan on " + table), plan);
  }

  @Test
  void serviceLookupByLowerCodeUsesTheCaseInsensitiveUniqueIndex() {
    String plan =
        plan(
            "SELECT s.service_code FROM services s JOIN service_config_versions v ON v.service_code ="
                + " s.service_code AND v.version_number = s.current_version_number"
                + " WHERE lower(s.service_code) = lower('s123')");

    assertNoSeqScan(plan, "services");
    assertNoSeqScan(plan, "service_config_versions");
    assertTrue(plan.contains("ux_services_service_code_lower"), plan);
  }

  @Test
  void transactionLookupByKeyPairUsesThePrimaryKey() {
    String sql =
        CallbackLedger.FIND_TRANSACTION.replace(":svc", "'s123'").replace(":txn", "'T123'");

    String plan = plan(sql);

    assertNoSeqScan(plan, "transactions");
    assertNoSeqScan(plan, "services");
    assertTrue(plan.contains("pk_transactions"), plan);
  }

  @Test
  void readingACallbackHistoryOfATransactionUsesTheTransactionIndexInReceiveOrder() {
    String sql =
        CallbackLedger.LIST_TRANSACTION_CALLBACKS
            .replace(":svc", "'S123'")
            .replace(":txn", "'T123'");

    String plan = plan(sql);

    assertNoSeqScan(plan, "callbacks");
    assertNoSeqScan(plan, "service_config_versions");
    assertTrue(plan.contains("ix_callbacks_service_txn_received"), plan);
    assertFalse(
        plan.contains("Sort"), "Chỉ mục đã đúng thứ tự nhận nên không cần sắp xếp bù:\n" + plan);
  }

  @Test
  void transactionListUsesTheSortIndexWithoutSortingTheWholeTable() {
    String sql =
        ListQueryBuilder.transactions(TransactionFilter.none())
            .pageSql()
            .replace(":limit", "20")
            .replace(":offset", "0");

    String plan = plan(sql);

    assertTrue(plan.contains("ix_transactions_latest"), plan);
    assertFalse(plan.contains("Sort"), plan);
    assertNoSeqScan(plan, "transactions");
  }
}
