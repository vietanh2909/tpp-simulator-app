package com.tpp.simulator.web;

import com.tpp.simulator.config.LogSanitizer;

/**
 * Mục nhật ký tích lũy theo request của endpoint callback (OD-01, SD-04). Kiểu này chỉ có phương
 * thức nhận mã dịch vụ, số, kiểu liệt kê và lớp lỗi cùng SQLSTATE: không có chỗ nào để đưa header,
 * body hay khóa vào, kể cả khi sau này có người sửa mã.
 */
public final class CallbackLogEntry {

  private String service = "-";
  private Long recordId;
  private Enum<?> outcome;
  private Enum<?> rejectionReason;
  private int httpStatus;
  private String exceptionClass;
  private String sqlState;
  private boolean emitted;

  /** Mã dịch vụ lấy từ đường dẫn; được làm sạch ký tự điều khiển và cắt độ dài. */
  public CallbackLogEntry service(String serviceCode) {
    this.service = LogSanitizer.clean(serviceCode);
    return this;
  }

  public CallbackLogEntry recordId(Long id) {
    this.recordId = id;
    return this;
  }

  public CallbackLogEntry outcome(Enum<?> value) {
    this.outcome = value;
    return this;
  }

  public CallbackLogEntry rejectionReason(Enum<?> value) {
    this.rejectionReason = value;
    return this;
  }

  public CallbackLogEntry httpStatus(int status) {
    this.httpStatus = status;
    return this;
  }

  /** Lỗi ghi hoặc đọc kho: chỉ tên lớp ngoại lệ và SQLSTATE, không thông điệp. */
  public CallbackLogEntry failure(String exceptionClassName, String state) {
    this.exceptionClass = exceptionClassName;
    this.sqlState = state;
    return this;
  }

  boolean hasFailure() {
    return exceptionClass != null;
  }

  /** Đánh dấu đã phát; trả false nếu đã phát trước đó (để ghi đúng một dòng). */
  boolean markEmitted() {
    if (emitted) {
      return false;
    }
    emitted = true;
    return true;
  }

  /** Thông điệp theo OD-02: trường vắng thì bỏ cả khóa, thứ tự cố định. */
  String message() {
    StringBuilder text = new StringBuilder("Callback request: service=").append(service);
    if (recordId != null) {
      text.append(" recordId=").append(recordId);
    }
    if (outcome != null) {
      text.append(" outcome=").append(outcome.name());
    } else if (rejectionReason != null) {
      text.append(" rejectionReason=").append(rejectionReason.name());
    }
    text.append(" httpStatus=").append(httpStatus);
    if (exceptionClass != null) {
      text.append(" exception=").append(exceptionClass).append(" sqlState=").append(sqlState);
    }
    return text.toString();
  }
}
