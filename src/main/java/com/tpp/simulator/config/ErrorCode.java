package com.tpp.simulator.config;

import org.springframework.http.HttpStatus;

/**
 * Bảng mã lỗi do ứng dụng sinh ra (contract-summary.md, "Bảng mã lỗi"). Giá trị {@code name()} là
 * trường {@code error} của thân lỗi và cũng là giá trị header {@code X-Simulator-Error}.
 *
 * <p>{@code NOT_FOUND}, {@code METHOD_NOT_ALLOWED} và {@code UNSUPPORTED_MEDIA_TYPE} bổ sung cho
 * lỗi mức container (đường dẫn lạ, phương thức sai, Content-Type sai ở API quản lý) để mọi lỗi đều
 * đúng dạng hợp đồng.
 */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  UNSUPPORTED_CONTENT_TYPE(HttpStatus.BAD_REQUEST),
  INVALID_JSON(HttpStatus.BAD_REQUEST),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
  SERVICE_NOT_FOUND(HttpStatus.NOT_FOUND),
  TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
  CALLBACK_NOT_FOUND(HttpStatus.NOT_FOUND),
  NOT_FOUND(HttpStatus.NOT_FOUND),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
  SERVICE_CODE_DUPLICATE(HttpStatus.CONFLICT),
  PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  SERVICE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
