package com.tpp.simulator.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dữ liệu mẫu chèn thẳng bằng SQL cho kiểm thử danh sách và chi tiết. */
public final class Fixtures {

  private Fixtures() {}

  /** Chèn một Callback; trả recordId. {@code outcome} REJECTED thì {@code reason} bắt buộc. */
  public static long callback(
      JdbcTemplate jdbc,
      String serviceCode,
      long versionId,
      Instant receivedAt,
      String outcome,
      String reason,
      String transactionId,
      String status) {
    return jdbc.queryForObject(
        "INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,"
            + " rejection_reason, http_status_returned, request_headers_masked, payload_json,"
            + " transaction_id, status, mapping_field_results) VALUES (?, ?, ?, ?, ?, ?, '{}', '{}',"
            + " ?, ?, '{}') RETURNING record_id",
        Long.class,
        serviceCode,
        versionId,
        OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC),
        outcome,
        reason,
        "REJECTED".equals(outcome) ? 401 : 200,
        transactionId,
        status);
  }

  /** Chèn một Transaction trỏ tới callback gần nhất {@code callbackId}. */
  public static void transaction(
      JdbcTemplate jdbc,
      String serviceCode,
      String transactionId,
      String status,
      Instant latestReceivedAt,
      long callbackId,
      int count) {
    jdbc.update(
        "INSERT INTO transactions (service_code, transaction_id, latest_status, latest_received_at,"
            + " latest_callback_id, latest_has_mapping_error, callback_count)"
            + " VALUES (?, ?, ?, ?, ?, false, ?)",
        serviceCode,
        transactionId,
        status,
        OffsetDateTime.ofInstant(latestReceivedAt, ZoneOffset.UTC),
        callbackId,
        count);
  }

  /** Một Callback SUCCESS kèm Transaction của nó (mỗi lần gọi tạo giao dịch mới). */
  public static long successWithTransaction(
      JdbcTemplate jdbc,
      String serviceCode,
      long versionId,
      Instant receivedAt,
      String transactionId,
      String status) {
    long id =
        callback(jdbc, serviceCode, versionId, receivedAt, "SUCCESS", null, transactionId, status);
    transaction(jdbc, serviceCode, transactionId, status, receivedAt, id, 1);
    return id;
  }
}
