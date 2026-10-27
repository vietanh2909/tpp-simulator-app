package com.tpp.simulator.ingestion;

import com.tpp.simulator.config.ErrorCode;

/**
 * Sáu lý do từ chối được ghi (BR14.6): mỗi lý do ứng với đúng một mã lỗi, một mã HTTP (qua {@link
 * ErrorCode}) và một thông báo cố định. 401 dùng cùng một câu cho thiếu và sai khóa.
 */
public enum RejectionReason {
  SERVICE_DELETED(ErrorCode.SERVICE_NOT_FOUND, "Không tìm thấy dịch vụ."),
  SERVICE_DISABLED(ErrorCode.SERVICE_DISABLED, "Dịch vụ đang tắt."),
  MISSING_API_KEY(ErrorCode.UNAUTHORIZED, "Khóa API thiếu hoặc không hợp lệ."),
  INVALID_API_KEY(ErrorCode.UNAUTHORIZED, "Khóa API thiếu hoặc không hợp lệ."),
  UNSUPPORTED_CONTENT_TYPE(
      ErrorCode.UNSUPPORTED_CONTENT_TYPE, "Content-Type phải là application/json."),
  INVALID_JSON(ErrorCode.INVALID_JSON, "Body không phải JSON hợp lệ.");

  private final ErrorCode errorCode;
  private final String message;

  RejectionReason(ErrorCode errorCode, String message) {
    this.errorCode = errorCode;
    this.message = message;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  /** Thông báo cố định của thân lỗi; không chứa khóa, giá trị header hay nội dung body. */
  public String message() {
    return message;
  }
}
