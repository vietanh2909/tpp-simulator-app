package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Thay trang lỗi mặc định của máy chủ nhúng: lỗi mức container (404 cho đường dẫn không có, kể cả
 * endpoint Actuator không mở, 405...) cũng ra {@code ErrorResponse} JSON kèm header {@code
 * X-Simulator-Error} (NFR3.8).
 */
@Controller
public class ContainerErrorController implements ErrorController {

  private final ApiErrors errors;

  public ContainerErrorController(ApiErrors errors) {
    this.errors = errors;
  }

  @RequestMapping("/error")
  public ResponseEntity<ErrorBody> error(HttpServletRequest request) {
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int code = status instanceof Integer value ? value : 500;
    ApiException exception =
        switch (code) {
          case 400 ->
              new ApiException(
                  ErrorCode.VALIDATION_FAILED, "Yêu cầu không hợp lệ.", List.of(), null);
          case 404 ->
              new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy đường dẫn.", List.of(), null);
          case 405 ->
              new ApiException(
                  ErrorCode.METHOD_NOT_ALLOWED,
                  "Phương thức HTTP không được hỗ trợ.",
                  List.of(),
                  null);
          case 413 ->
              new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Body quá lớn.", List.of(), null);
          case 415 ->
              new ApiException(
                  ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                  "Content-Type của yêu cầu không được hỗ trợ.",
                  List.of(),
                  null);
          default -> new ApiException(ErrorCode.INTERNAL_ERROR, "Lỗi nội bộ.", List.of(), null);
        };
    return errors.response(exception);
  }
}
