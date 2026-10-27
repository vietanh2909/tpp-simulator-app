package com.tpp.simulator.ingestion;

import com.tpp.simulator.config.DbErrors;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.mapping.MappingOutcome;

/**
 * Kết quả xử lý một request nhận callback, đủ để lớp web dựng response HTTP và dòng nhật ký mà
 * không phải bắt ngoại lệ nào (RD-02, OD-01).
 *
 * <ul>
 *   <li>{@link Kind#ACCEPTED}: callback được chấp nhận. {@code writeStatus} là {@code WRITTEN} (có
 *       {@code response} đã cấu hình và {@code recordId}) hoặc {@code ACCEPTED_WRITE_FAILED}.
 *   <li>{@link Kind#REJECTED}: bị từ chối vì một trong sáu lý do; {@code errorCode}/{@code
 *       errorMessage} là response từ chối gốc; {@code writeStatus} là {@code WRITTEN} hoặc {@code
 *       REJECTED_RECORD_FAILED}.
 *   <li>{@link Kind#UNKNOWN_SERVICE}: mã dịch vụ chưa từng có (404, không ghi, không {@code
 *       writeStatus}).
 *   <li>{@link Kind#SNAPSHOT_FAILED}: không đọc được ảnh dịch vụ (500, không ghi).
 * </ul>
 *
 * @param recordId bản ghi đã ghi; null nếu không ghi hoặc ghi lỗi
 * @param outcome kết quả ánh xạ của callback được chấp nhận; null với các nhánh khác
 * @param failureClass tên lớp ngoại lệ khi {@code writeStatus} khác {@code WRITTEN}; không có thông
 *     điệp (SD-05)
 * @param sqlState SQLSTATE của ngoại lệ gốc, hoặc {@code -}
 */
public record IngestionResult(
    Kind kind,
    WriteStatus writeStatus,
    Long recordId,
    MappingOutcome outcome,
    RejectionReason rejectionReason,
    ConfiguredResponse response,
    ErrorCode errorCode,
    String errorMessage,
    String failureClass,
    String sqlState) {

  /** Loại kết quả. */
  public enum Kind {
    ACCEPTED,
    REJECTED,
    UNKNOWN_SERVICE,
    SNAPSHOT_FAILED
  }

  static IngestionResult written(
      long recordId, MappingOutcome outcome, ConfiguredResponse response) {
    return new IngestionResult(
        Kind.ACCEPTED,
        WriteStatus.WRITTEN,
        recordId,
        outcome,
        null,
        response,
        null,
        null,
        null,
        null);
  }

  static IngestionResult acceptedWriteFailed(MappingOutcome outcome, Throwable error) {
    return new IngestionResult(
        Kind.ACCEPTED,
        WriteStatus.ACCEPTED_WRITE_FAILED,
        null,
        outcome,
        null,
        null,
        ErrorCode.INTERNAL_ERROR,
        "Lỗi nội bộ khi ghi dữ liệu.",
        error.getClass().getName(),
        DbErrors.sqlState(error));
  }

  static IngestionResult rejected(RejectionReason reason, long recordId) {
    return new IngestionResult(
        Kind.REJECTED,
        WriteStatus.WRITTEN,
        recordId,
        null,
        reason,
        null,
        reason.errorCode(),
        reason.message(),
        null,
        null);
  }

  static IngestionResult rejectedRecordFailed(RejectionReason reason, Throwable error) {
    return new IngestionResult(
        Kind.REJECTED,
        WriteStatus.REJECTED_RECORD_FAILED,
        null,
        null,
        reason,
        null,
        reason.errorCode(),
        reason.message(),
        error.getClass().getName(),
        DbErrors.sqlState(error));
  }

  static IngestionResult unknownService() {
    return new IngestionResult(
        Kind.UNKNOWN_SERVICE,
        null,
        null,
        null,
        null,
        null,
        ErrorCode.SERVICE_NOT_FOUND,
        "Không tìm thấy dịch vụ.",
        null,
        null);
  }

  static IngestionResult snapshotReadFailed(Throwable error) {
    return new IngestionResult(
        Kind.SNAPSHOT_FAILED,
        WriteStatus.SNAPSHOT_READ_FAILED,
        null,
        null,
        null,
        null,
        ErrorCode.INTERNAL_ERROR,
        "Lỗi nội bộ.",
        error.getClass().getName(),
        DbErrors.sqlState(error));
  }

  /** true khi có bản ghi được ghi bền (callback được chấp nhận hoặc lần nhận bị từ chối). */
  public boolean written() {
    return writeStatus == WriteStatus.WRITTEN;
  }
}
