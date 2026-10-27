package com.tpp.simulator.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sổ cái callback: ghi Callback và gom Transaction (BR4.x), đọc danh sách và chi tiết giao dịch
 * (BR5.x).
 *
 * <p>Callback bất biến: lớp này chỉ thêm dòng vào {@code callbacks}, không bao giờ sửa hay xóa
 * (NFR2.6). Giao dịch cơ sở dữ liệu do bên gọi (CallbackIngestion) mở; {@link #append} yêu cầu giao
 * dịch đã có.
 */
@Repository
public class CallbackLedger {

  private static final String INSERT_CALLBACK =
      """
      INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,
          http_status_returned, request_headers_masked, payload_json, transaction_id, status,
          business_timestamp_utc, mapping_field_results, response_status_returned,
          response_body_returned)
      VALUES (:svc, :versionId, :receivedAt, :outcome, :httpStatus, CAST(:headers AS jsonb),
          CAST(:payload AS jsonb), :txn, :status, :bts, CAST(:fields AS jsonb), :httpStatus,
          CAST(:responseBody AS jsonb))
      RETURNING record_id
      """;

  /**
   * MỘT câu lệnh thêm-hoặc-cập-nhật-có-điều-kiện (scalability-design.md): tăng callback_count
   * nguyên tử và chỉ thay TẤT CẢ các cột latest_* cùng nhau khi (received_at, record_id) mới lớn
   * hơn cặp đang lưu. Hai callback đầu tiên cùng cặp khóa đến cùng lúc không bên nào lỗi: bên thua
   * rơi vào nhánh cập nhật.
   */
  private static final String UPSERT_TRANSACTION =
      """
      INSERT INTO transactions (service_code, transaction_id, latest_status, latest_business_ts,
          latest_received_at, latest_callback_id, latest_has_mapping_error, callback_count)
      VALUES (:svc, :txn, :status, :bts, :receivedAt, :callbackId, :hasError, 1)
      ON CONFLICT (service_code, transaction_id) DO UPDATE SET
        callback_count = transactions.callback_count + 1,
        latest_status = CASE WHEN (EXCLUDED.latest_received_at, EXCLUDED.latest_callback_id)
            > (transactions.latest_received_at, transactions.latest_callback_id)
            THEN EXCLUDED.latest_status ELSE transactions.latest_status END,
        latest_business_ts = CASE WHEN (EXCLUDED.latest_received_at, EXCLUDED.latest_callback_id)
            > (transactions.latest_received_at, transactions.latest_callback_id)
            THEN EXCLUDED.latest_business_ts ELSE transactions.latest_business_ts END,
        latest_has_mapping_error = CASE WHEN (EXCLUDED.latest_received_at, EXCLUDED.latest_callback_id)
            > (transactions.latest_received_at, transactions.latest_callback_id)
            THEN EXCLUDED.latest_has_mapping_error ELSE transactions.latest_has_mapping_error END,
        latest_callback_id = CASE WHEN (EXCLUDED.latest_received_at, EXCLUDED.latest_callback_id)
            > (transactions.latest_received_at, transactions.latest_callback_id)
            THEN EXCLUDED.latest_callback_id ELSE transactions.latest_callback_id END,
        latest_received_at = CASE WHEN (EXCLUDED.latest_received_at, EXCLUDED.latest_callback_id)
            > (transactions.latest_received_at, transactions.latest_callback_id)
            THEN EXCLUDED.latest_received_at ELSE transactions.latest_received_at END
      """;

  /**
   * Lần nhận bị từ chối (BR15.2): outcome REJECTED, kết quả ánh xạ là đối tượng rỗng. Hai tham số
   * payload có kiểu tường minh để giá trị JSON null (chuỗi {@code null}) khác SQL NULL (BR15.4).
   */
  private static final String INSERT_REJECTED =
      """
      INSERT INTO callbacks (service_code, config_version_id, received_at, outcome,
          rejection_reason, http_status_returned, request_headers_masked, payload_json,
          payload_raw_text, mapping_field_results, response_status_returned,
          response_body_returned)
      VALUES (:svc, :versionId, :receivedAt, 'REJECTED', :reason, :httpStatus,
          CAST(:headers AS jsonb), CAST(:payload AS jsonb), :rawText, CAST('{}' AS jsonb),
          :httpStatus, CAST(:responseBody AS jsonb))
      RETURNING record_id
      """;

  static final String FIND_CALLBACK =
      """
      SELECT c.record_id, c.service_code, s.deleted, c.received_at, c.outcome, c.rejection_reason,
             c.http_status_returned, c.transaction_id, c.status, c.business_timestamp_utc,
             v.version_number, CAST(c.request_headers_masked AS text) AS request_headers,
             CAST(c.payload_json AS text) AS payload_json, c.payload_raw_text,
             CAST(c.mapping_field_results AS text) AS mapping_field_results,
             c.response_status_returned, CAST(c.response_body_returned AS text) AS response_body
        FROM callbacks c
        JOIN services s ON s.service_code = c.service_code
        JOIN service_config_versions v ON v.version_id = c.config_version_id
       WHERE c.record_id = :recordId
      """;

  static final String FIND_TRANSACTION =
      """
      SELECT t.service_code, s.deleted, t.transaction_id, t.latest_status, t.latest_business_ts,
             t.latest_received_at, t.latest_callback_id, t.latest_has_mapping_error, t.callback_count
        FROM services s
        JOIN transactions t ON t.service_code = s.service_code
       WHERE lower(s.service_code) = lower(:svc) AND t.transaction_id = :txn
      """;

  static final String LIST_TRANSACTION_CALLBACKS =
      """
      SELECT c.record_id, c.service_code, c.received_at, c.outcome, c.rejection_reason,
             c.http_status_returned, c.transaction_id, c.status, c.business_timestamp_utc,
             v.version_number, CAST(c.request_headers_masked AS text) AS request_headers,
             CAST(c.payload_json AS text) AS payload_json, c.payload_raw_text,
             CAST(c.mapping_field_results AS text) AS mapping_field_results,
             c.response_status_returned, CAST(c.response_body_returned AS text) AS response_body
        FROM callbacks c
        JOIN service_config_versions v ON v.version_id = c.config_version_id
       WHERE c.service_code = :svc AND c.transaction_id = :txn
       ORDER BY c.received_at, c.record_id
      """;

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public CallbackLedger(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Ghi Callback rồi (chỉ khi transactionId hợp lệ) thêm hoặc cập nhật Transaction. Phải chạy trong
   * giao dịch của bên gọi để hai thao tác cùng thành công hoặc cùng hủy (BR2.11).
   *
   * @return recordId của Callback vừa ghi
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long append(NewCallback callback) {
    String headersJson = toJson(callback.requestHeaders().asMap());
    long recordId =
        jdbc.sql(INSERT_CALLBACK)
            .param("svc", callback.serviceCode())
            .param("versionId", callback.configVersionId())
            .param("receivedAt", utc(callback.receivedAt()))
            .param("outcome", callback.outcome())
            .param("httpStatus", callback.responseStatus())
            .param("headers", headersJson)
            .param("payload", callback.payloadJson())
            .param("txn", callback.transactionId(), Types.VARCHAR)
            .param("status", callback.status(), Types.VARCHAR)
            .param("bts", utc(callback.businessTimestampUtc()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("fields", callback.mappingFieldResultsJson())
            .param("responseBody", callback.responseBody())
            .query(Long.class)
            .single();
    if (callback.transactionId() != null) {
      jdbc.sql(UPSERT_TRANSACTION)
          .param("svc", callback.serviceCode())
          .param("txn", callback.transactionId())
          .param("status", callback.status(), Types.VARCHAR)
          .param("bts", utc(callback.businessTimestampUtc()), Types.TIMESTAMP_WITH_TIMEZONE)
          .param("receivedAt", utc(callback.receivedAt()))
          .param("callbackId", recordId)
          .param("hasError", callback.mappingError())
          .update();
    }
    return recordId;
  }

  /**
   * Ghi lần nhận bị từ chối (BR15.2). Phải chạy trong giao dịch RIÊNG của bên gọi (BR15.5); không
   * bao giờ chạm {@code transactions} (BR15.3).
   *
   * @return recordId của Callback vừa ghi
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long appendRejected(RejectedCallback rejected) {
    return jdbc.sql(INSERT_REJECTED)
        .param("svc", rejected.serviceCode())
        .param("versionId", rejected.configVersionId())
        .param("receivedAt", utc(rejected.receivedAt()))
        .param("reason", rejected.rejectionReason())
        .param("httpStatus", rejected.httpStatus())
        .param("headers", toJson(rejected.requestHeaders().asMap()))
        .param("payload", rejected.payloadJson(), Types.VARCHAR)
        .param("rawText", rejected.payloadRawText(), Types.VARCHAR)
        .param("responseBody", rejected.responseBodyJson())
        .query(Long.class)
        .single();
  }

  /** W5/W21: một trang giao dịch theo bộ lọc, mới nhất trước (không bộ lọc: như U1). */
  public PageResult<TransactionSummary> listTransactions(int page, int size) {
    return listTransactions(TransactionFilter.none(), page, size);
  }

  /**
   * W21: một trang giao dịch; đếm trước, trang vượt cuối cho {@code items} rỗng mà không chạy câu
   * trang (PD-04). Đọc hết rồi trả kết nối về pool.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<TransactionSummary> listTransactions(
      TransactionFilter filter, long page, int size) {
    ListQueryBuilder query = ListQueryBuilder.transactions(filter);
    return page(query, page, size, CallbackLedger::toSummary);
  }

  /** W22: một trang callback theo bộ lọc, mới nhất trước. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<CallbackListItem> listCallbacks(CallbackFilter filter, long page, int size) {
    ListQueryBuilder query = ListQueryBuilder.callbacks(filter);
    return page(query, page, size, CallbackLedger::toListItem);
  }

  /** W23: chi tiết một lần nhận theo recordId; rỗng nếu không có. */
  public Optional<CallbackDetail> findCallback(long recordId) {
    return jdbc.sql(FIND_CALLBACK)
        .param("recordId", recordId)
        .query((rs, row) -> toDetail(rs, rs.getBoolean("deleted")))
        .optional();
  }

  private <T> PageResult<T> page(ListQueryBuilder query, long page, int size, RowMapper<T> mapper) {
    long total = bind(jdbc.sql(query.countSql()), query).query(Long.class).single();
    int totalPages = (int) ((total + size - 1) / size);
    if (page > totalPages) {
      return new PageResult<>(List.of(), page, size, total, totalPages);
    }
    List<T> items =
        bind(jdbc.sql(query.pageSql()), query)
            .param("limit", size)
            .param("offset", (page - 1) * size)
            .query(mapper)
            .list();
    return new PageResult<>(items, page, size, total, totalPages);
  }

  private static JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec statement, ListQueryBuilder query) {
    return statement.params(query.params());
  }

  /** W6: giao dịch và toàn bộ lần nhận (cũ nhất trước); đọc hết rồi trả kết nối về pool. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<TransactionDetail> findTransaction(String serviceCode, String transactionId) {
    Optional<TransactionSummary> summary =
        jdbc.sql(FIND_TRANSACTION)
            .param("svc", serviceCode)
            .param("txn", transactionId)
            .query(CallbackLedger::toSummary)
            .optional();
    if (summary.isEmpty()) {
      return Optional.empty();
    }
    TransactionSummary transaction = summary.get();
    boolean deleted = transaction.serviceDeleted();
    List<CallbackDetail> callbacks =
        jdbc.sql(LIST_TRANSACTION_CALLBACKS)
            .param("svc", transaction.serviceCode())
            .param("txn", transaction.transactionId())
            .query((rs, row) -> toDetail(rs, deleted))
            .list();
    return Optional.of(new TransactionDetail(transaction, callbacks));
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize JSON value", e);
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static TransactionSummary toSummary(ResultSet rs, int row) throws SQLException {
    return new TransactionSummary(
        rs.getString("service_code"),
        rs.getBoolean("deleted"),
        rs.getString("transaction_id"),
        rs.getString("latest_status"),
        instant(rs, "latest_business_ts"),
        instant(rs, "latest_received_at"),
        rs.getLong("latest_callback_id"),
        rs.getBoolean("latest_has_mapping_error"),
        rs.getInt("callback_count"));
  }

  private static CallbackListItem toListItem(ResultSet rs, int row) throws SQLException {
    return new CallbackListItem(
        rs.getLong("record_id"),
        rs.getString("service_code"),
        rs.getBoolean("deleted"),
        instant(rs, "received_at"),
        rs.getString("outcome"),
        rs.getString("rejection_reason"),
        rs.getInt("http_status_returned"),
        rs.getString("transaction_id"),
        rs.getString("status"),
        instant(rs, "business_timestamp_utc"));
  }

  private static CallbackDetail toDetail(ResultSet rs, boolean serviceDeleted) throws SQLException {
    return new CallbackDetail(
        rs.getLong("record_id"),
        rs.getString("service_code"),
        serviceDeleted,
        instant(rs, "received_at"),
        rs.getString("outcome"),
        rs.getString("rejection_reason"),
        rs.getInt("http_status_returned"),
        rs.getString("transaction_id"),
        rs.getString("status"),
        instant(rs, "business_timestamp_utc"),
        rs.getInt("version_number"),
        rs.getString("request_headers"),
        rs.getString("payload_json"),
        rs.getString("payload_raw_text"),
        rs.getString("mapping_field_results"),
        (Integer) rs.getObject("response_status_returned"),
        rs.getString("response_body"));
  }
}
