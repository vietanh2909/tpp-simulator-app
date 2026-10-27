package com.tpp.simulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * MỘT bộ dựng lỗi duy nhất cho thân {@code ErrorResponse} và header {@code X-Simulator-Error}
 * (BR2.5), dùng chung cho bộ xử lý lỗi tập trung và bộ lọc kích thước (chạy trước
 * DispatcherServlet).
 */
@Component
public class ApiErrors {

  public static final String HEADER = "X-Simulator-Error";

  /** Có mặt (giá trị {@code true}) khi lần nhận bị từ chối không ghi được (BR15.6). */
  public static final String RECORD_FAILED_HEADER = "X-Simulator-Record-Failed";

  static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

  private final ObjectMapper objectMapper;

  public ApiErrors(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Dựng thân lỗi; {@code fieldErrors} chỉ xuất hiện với VALIDATION_FAILED. */
  public ErrorBody body(ApiException e) {
    return new ErrorBody(
        e.code().name(),
        e.getMessage(),
        e.code() == ErrorCode.VALIDATION_FAILED ? e.fieldErrors() : null);
  }

  /** Dạng ResponseEntity cho bộ xử lý lỗi tập trung. */
  public ResponseEntity<ErrorBody> response(ApiException e) {
    return ResponseEntity.status(e.code().status())
        .contentType(JSON_UTF8)
        .header(HEADER, e.code().name())
        .body(body(e));
  }

  /** Như {@link #response(ApiException)}, kèm một header phụ (ví dụ X-Simulator-Record-Failed). */
  public ResponseEntity<ErrorBody> response(
      ApiException e, String extraHeaderName, String extraHeaderValue) {
    return ResponseEntity.status(e.code().status())
        .contentType(JSON_UTF8)
        .header(HEADER, e.code().name())
        .header(extraHeaderName, extraHeaderValue)
        .body(body(e));
  }

  /** Ghi thẳng vào response servlet (dùng trong bộ lọc, trước DispatcherServlet). */
  public void write(HttpServletResponse response, ApiException e) throws IOException {
    response.setStatus(e.code().status().value());
    response.setHeader(HEADER, e.code().name());
    response.setContentType(JSON_UTF8.toString());
    response.getOutputStream().write(objectMapper.writeValueAsBytes(body(e)));
  }
}
