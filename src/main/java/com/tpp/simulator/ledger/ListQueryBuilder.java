package com.tpp.simulator.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghép các đoạn SQL CỐ ĐỊNH cho từng bộ lọc có mặt (PD-02, SD-06). Mọi giá trị là tham số có tên và
 * ép kiểu tường minh; lớp này chỉ nối các hằng {@code static final String}. Không dùng mẫu {@code
 * (:v IS NULL OR cột = :v)} vì nó làm kế hoạch dùng chung không chọn được chỉ mục. Câu đếm và câu
 * trang sinh từ cùng danh sách điều kiện nên {@code totalItems} khớp {@code items}.
 */
public final class ListQueryBuilder {

  private static final String TRANSACTIONS_SELECT =
      """
      SELECT t.service_code, s.deleted, t.transaction_id, t.latest_status, t.latest_business_ts,
             t.latest_received_at, t.latest_callback_id, t.latest_has_mapping_error, t.callback_count
        FROM transactions t
        JOIN services s ON s.service_code = t.service_code
      """;

  private static final String TRANSACTIONS_COUNT = "SELECT count(*) FROM transactions t";

  private static final String TRANSACTIONS_ORDER =
      " ORDER BY t.latest_received_at DESC, t.latest_callback_id DESC LIMIT :limit OFFSET :offset";

  private static final String CALLBACKS_SELECT =
      """
      SELECT c.record_id, c.service_code, s.deleted, c.received_at, c.outcome, c.rejection_reason,
             c.http_status_returned, c.transaction_id, c.status, c.business_timestamp_utc
        FROM callbacks c
        JOIN services s ON s.service_code = c.service_code
      """;

  private static final String CALLBACKS_COUNT = "SELECT count(*) FROM callbacks c";

  private static final String CALLBACKS_ORDER =
      " ORDER BY c.received_at DESC, c.record_id DESC LIMIT :limit OFFSET :offset";

  private static final String T_SERVICE =
      "t.service_code = (SELECT s2.service_code FROM services s2"
          + " WHERE lower(s2.service_code) = lower(CAST(:serviceCode AS text)))";
  private static final String T_TRANSACTION_ID =
      "position(lower(CAST(:transactionId AS text)) IN lower(t.transaction_id)) > 0";
  private static final String T_STATUS =
      "position(lower(CAST(:status AS text)) IN lower(t.latest_status)) > 0";
  private static final String T_FROM = "t.latest_received_at >= CAST(:receivedFrom AS timestamptz)";
  private static final String T_TO = "t.latest_received_at <= CAST(:receivedTo AS timestamptz)";

  private static final String C_SERVICE =
      "c.service_code = (SELECT s2.service_code FROM services s2"
          + " WHERE lower(s2.service_code) = lower(CAST(:serviceCode AS text)))";
  private static final String C_TRANSACTION_ID =
      "position(lower(CAST(:transactionId AS text)) IN lower(c.transaction_id)) > 0";
  private static final String C_OUTCOME = "c.outcome = CAST(:outcome AS text)";
  private static final String C_REJECTION = "c.rejection_reason = CAST(:rejectionReason AS text)";
  private static final String C_FROM = "c.received_at >= CAST(:receivedFrom AS timestamptz)";
  private static final String C_TO = "c.received_at <= CAST(:receivedTo AS timestamptz)";

  private final String selectSql;
  private final String countSql;
  private final String orderSql;
  private final List<String> conditions;
  private final Map<String, Object> params;

  private ListQueryBuilder(
      String selectSql,
      String countSql,
      String orderSql,
      List<String> conditions,
      Map<String, Object> params) {
    this.selectSql = selectSql;
    this.countSql = countSql;
    this.orderSql = orderSql;
    this.conditions = conditions;
    this.params = params;
  }

  /** Truy vấn danh sách giao dịch; không bộ lọc thì sinh đúng câu của U1. */
  public static ListQueryBuilder transactions(TransactionFilter filter) {
    Conditions c = new Conditions();
    c.add(filter.serviceCode(), "serviceCode", T_SERVICE);
    c.add(filter.transactionId(), "transactionId", T_TRANSACTION_ID);
    c.add(filter.status(), "status", T_STATUS);
    c.add(filter.receivedFrom(), "receivedFrom", T_FROM);
    c.add(filter.receivedTo(), "receivedTo", T_TO);
    return new ListQueryBuilder(
        TRANSACTIONS_SELECT, TRANSACTIONS_COUNT, TRANSACTIONS_ORDER, c.list, c.params);
  }

  /** Truy vấn danh sách callback. */
  public static ListQueryBuilder callbacks(CallbackFilter filter) {
    Conditions c = new Conditions();
    c.add(filter.serviceCode(), "serviceCode", C_SERVICE);
    c.add(filter.transactionId(), "transactionId", C_TRANSACTION_ID);
    c.add(filter.outcome(), "outcome", C_OUTCOME);
    c.add(filter.rejectionReason(), "rejectionReason", C_REJECTION);
    c.add(filter.receivedFrom(), "receivedFrom", C_FROM);
    c.add(filter.receivedTo(), "receivedTo", C_TO);
    return new ListQueryBuilder(
        CALLBACKS_SELECT, CALLBACKS_COUNT, CALLBACKS_ORDER, c.list, c.params);
  }

  private static final class Conditions {
    private final List<String> list = new ArrayList<>();
    private final Map<String, Object> params = new LinkedHashMap<>();

    void add(Object value, String name, String fragment) {
      if (value != null) {
        list.add(fragment);
        params.put(name, value);
      }
    }
  }

  private String where() {
    return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
  }

  /** Câu lấy một trang; có hai tham số {@code :limit} và {@code :offset}. */
  public String pageSql() {
    return selectSql.stripTrailing() + where() + orderSql;
  }

  /** Câu đếm tổng số dòng khớp. */
  public String countSql() {
    return countSql + where();
  }

  /** Tham số của bộ lọc (không gồm limit và offset). */
  public Map<String, Object> params() {
    return Map.copyOf(params);
  }
}
