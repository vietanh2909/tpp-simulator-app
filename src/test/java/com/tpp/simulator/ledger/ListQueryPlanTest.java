package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NFR1.23, PD-03, PD-11: ba nhóm truy vấn chọn lọc của hai danh sách dùng chỉ mục, không quét tuần
 * tự bảng lớn. Lớp ĐỘC LẬP với {@code QueryPlanTest} của U1. Lọc chuỗi chứa được miễn (NFR1.22).
 */
class ListQueryPlanTest extends AbstractIntegrationTest {

  private static final int SERVICES = 100;
  private static final int CALLBACKS = 30_000;

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
    // 94% SUCCESS, 5% MAPPING_ERROR, 1% REJECTED (đủ sáu lý do xoay vòng).
    jdbc.update(
        "INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,"
            + " rejection_reason, http_status_returned, request_headers_masked, payload_json,"
            + " transaction_id, status, mapping_field_results)"
            + " SELECT 'S' || (g % ?), (g % ?) + 1, now() - (g || ' seconds')::interval,"
            + " CASE WHEN g % 100 = 0 THEN 'REJECTED' WHEN g % 20 = 1 THEN 'MAPPING_ERROR'"
            + " ELSE 'SUCCESS' END,"
            + " CASE WHEN g % 100 = 0 THEN (ARRAY['SERVICE_DISABLED','SERVICE_DELETED',"
            + "'MISSING_API_KEY','INVALID_API_KEY','UNSUPPORTED_CONTENT_TYPE','INVALID_JSON'])"
            + "[(g / 100) % 6 + 1] END,"
            + " 200, '{}', '{}', CASE WHEN g % 100 = 0 THEN NULL ELSE 'T' || (g / 4) END, 'ok', '{}'"
            + " FROM generate_series(0, ?) g",
        SERVICES, SERVICES, CALLBACKS - 1);
    jdbc.update(
        "INSERT INTO transactions (service_code, transaction_id, latest_status, latest_received_at,"
            + " latest_callback_id, latest_has_mapping_error, callback_count)"
            + " SELECT DISTINCT ON (service_code, transaction_id) service_code, transaction_id,"
            + " status, received_at, record_id, false, 1 FROM callbacks"
            + " WHERE transaction_id IS NOT NULL"
            + " ORDER BY service_code, transaction_id, received_at DESC, record_id DESC");
    jdbc.execute("ANALYZE");
  }

  /** Thay mọi tham số có tên bằng giá trị mẫu để chạy EXPLAIN. */
  private String plan(ListQueryBuilder query, boolean count) {
    String sql = count ? query.countSql() : query.pageSql();
    for (Map.Entry<String, Object> param : query.params().entrySet()) {
      Object value = param.getValue();
      String literal =
          value instanceof OffsetDateTime time
              ? "'" + time + "'"
              : "'" + value.toString().replace("'", "''") + "'";
      sql = sql.replace(":" + param.getKey(), literal);
    }
    sql = sql.replace(":limit", "20").replace(":offset", "0");
    return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
  }

  private static void assertNoSeqScan(String plan, String table) {
    assertFalse(plan.contains("Seq Scan on " + table), plan);
  }

  private static final OffsetDateTime FROM = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
  private static final OffsetDateTime TO = OffsetDateTime.now(ZoneOffset.UTC);

  @Test
  void unfilteredCallbackListUsesTheNewestFirstIndex() {
    String plan = plan(ListQueryBuilder.callbacks(CallbackFilter.none()), false);

    assertNoSeqScan(plan, "callbacks");
    assertTrue(plan.contains("ix_callbacks_received"), plan);
    assertFalse(plan.contains("Sort"), plan);
  }

  @Test
  void callbacksByServiceCodeUseTheServiceIndex() {
    CallbackFilter byService = new CallbackFilter("s7", null, null, null, null, null);
    CallbackFilter byServiceAndTime = new CallbackFilter("s7", null, null, null, FROM, TO);

    for (CallbackFilter filter : List.of(byService, byServiceAndTime)) {
      String page = plan(ListQueryBuilder.callbacks(filter), false);
      String count = plan(ListQueryBuilder.callbacks(filter), true);
      assertNoSeqScan(page, "callbacks");
      assertNoSeqScan(count, "callbacks");
      assertTrue(page.contains("ix_callbacks_service_received"), page);
    }
  }

  @Test
  void callbacksByOutcomeAndRejectionReasonAvoidSequentialScans() {
    CallbackFilter byOutcome = new CallbackFilter(null, null, "REJECTED", null, null, null);
    CallbackFilter byReason = new CallbackFilter(null, null, null, "INVALID_JSON", null, null);

    for (CallbackFilter filter : List.of(byOutcome, byReason)) {
      assertNoSeqScan(plan(ListQueryBuilder.callbacks(filter), false), "callbacks");
      assertNoSeqScan(plan(ListQueryBuilder.callbacks(filter), true), "callbacks");
    }
    assertTrue(
        plan(ListQueryBuilder.callbacks(byReason), false)
                .contains("ix_callbacks_rejection_received")
            || plan(ListQueryBuilder.callbacks(byReason), true)
                .contains("ix_callbacks_rejection_received"));
  }

  @Test
  void unfilteredTransactionListUsesTheSortIndexAndNeverSorts() {
    String plan = plan(ListQueryBuilder.transactions(TransactionFilter.none()), false);

    assertNoSeqScan(plan, "transactions");
    assertTrue(plan.contains("ix_transactions_latest"), plan);
    assertFalse(plan.contains("Sort"), plan);
  }

  @Test
  void transactionsByServiceCodeUseTheServiceIndex() {
    TransactionFilter byService = new TransactionFilter("s7", null, null, null, null);
    TransactionFilter byServiceAndTime = new TransactionFilter("s7", null, null, FROM, TO);

    for (TransactionFilter filter : List.of(byService, byServiceAndTime)) {
      String page = plan(ListQueryBuilder.transactions(filter), false);
      String count = plan(ListQueryBuilder.transactions(filter), true);
      assertNoSeqScan(page, "transactions");
      assertNoSeqScan(count, "transactions");
      assertTrue(page.contains("ix_transactions_service_latest"), page);
    }
  }

  @Test
  void theCallbackDetailLookupUsesThePrimaryKey() {
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN " + CallbackLedger.FIND_CALLBACK.replace(":recordId", "123"),
                String.class));

    assertNoSeqScan(plan, "callbacks");
    assertTrue(plan.contains("pk_callbacks"), plan);
  }
}
