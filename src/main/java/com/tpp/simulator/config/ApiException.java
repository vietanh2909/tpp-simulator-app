package com.tpp.simulator.config;

import java.util.List;

/**
 * Lỗi do ứng dụng chủ động sinh ra. Thông báo luôn dựng từ mẫu cố định, không nhắc lại đầu vào thô
 * hay khóa. {@code subject} (nếu có) là mã dịch vụ đã làm sạch, chỉ dùng để ghi log.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final List<FieldError> fieldErrors;
  private final String subject;

  public ApiException(ErrorCode code, String message) {
    this(code, message, List.of(), null);
  }

  public ApiException(
      ErrorCode code, String message, List<FieldError> fieldErrors, String subject) {
    super(message, null, false, false);
    this.code = code;
    this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    this.subject = subject;
  }

  public ErrorCode code() {
    return code;
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  public String subject() {
    return subject;
  }
}
