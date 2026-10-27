package com.tpp.simulator.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.DbErrors;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.config.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Bộ xử lý lỗi tập trung: dịch mọi ngoại lệ thành {@code ErrorResponse} kèm {@code
 * X-Simulator-Error}. Thông báo dựng từ mẫu cố định, không nhắc lại đầu vào thô hay khóa; thân JSON
 * hỏng hoặc sai kiểu khi tạo dịch vụ luôn là 400 VALIDATION_FAILED, không bao giờ 500.
 */
@RestControllerAdvice
public class ApiExceptionAdvice {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionAdvice.class);

  private final ApiErrors errors;

  public ApiExceptionAdvice(ApiErrors errors) {
    this.errors = errors;
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorBody> handleApi(ApiException e) {
    // INTERNAL_ERROR đã được bên ném ghi log mức ERROR an toàn; các từ chối khác ghi một dòng INFO.
    if (e.code() != ErrorCode.INTERNAL_ERROR) {
      log.info(
          "Request rejected: error={} httpStatus={} service={}",
          e.code(),
          e.code().status().value(),
          e.subject() == null ? "-" : LogSanitizer.clean(e.subject()));
    }
    return errors.response(e);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
    List<FieldError> fields = new ArrayList<>();
    if (e.getCause() instanceof JsonMappingException mapping) {
      for (JsonMappingException.Reference reference : mapping.getPath()) {
        if (reference.getFieldName() != null) {
          fields.add(new FieldError(reference.getFieldName(), "Giá trị sai kiểu dữ liệu."));
        }
      }
    }
    if (fields.isEmpty()) {
      fields.add(new FieldError("body", "Thân yêu cầu phải là một object JSON hợp lệ."));
    }
    return handleApi(
        new ApiException(ErrorCode.VALIDATION_FAILED, "Thân yêu cầu không hợp lệ.", fields, null));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorBody> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return handleApi(
        new ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Tham số không hợp lệ.",
            List.of(new FieldError(e.getName(), "Giá trị sai kiểu dữ liệu.")),
            null));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorBody> handleMissingParameter(
      MissingServletRequestParameterException e) {
    return handleApi(
        new ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Thiếu tham số bắt buộc.",
            List.of(new FieldError(e.getParameterName(), "Trường bắt buộc.")),
            null));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorBody> handleMethod(HttpRequestMethodNotSupportedException e) {
    return handleApi(
        new ApiException(
            ErrorCode.METHOD_NOT_ALLOWED, "Phương thức HTTP không được hỗ trợ.", List.of(), null));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorBody> handleMediaType(HttpMediaTypeNotSupportedException e) {
    return handleApi(
        new ApiException(
            ErrorCode.UNSUPPORTED_MEDIA_TYPE,
            "Content-Type của yêu cầu không được hỗ trợ.",
            List.of(),
            null));
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ErrorBody> handleNotFound(Exception e) {
    return handleApi(
        new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy đường dẫn.", List.of(), null));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ErrorBody> handleDataAccess(
      DataAccessException e, HttpServletRequest request) {
    if (!isCallbackEndpoint(request)) {
      DbErrors.logWriteFailure(log, null, e);
    }
    return errors.response(internal());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> handleUnexpected(Exception e, HttpServletRequest request) {
    // Endpoint callback đã tự ghi đúng một dòng nhật ký (OD-01); không ghi dòng thứ hai.
    if (!isCallbackEndpoint(request)) {
      if (e instanceof org.springframework.transaction.TransactionException) {
        DbErrors.logWriteFailure(log, null, e);
      } else {
        log.error("Unexpected error: exception={}", e.getClass().getName());
      }
    }
    return errors.response(internal());
  }

  private static boolean isCallbackEndpoint(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri != null && uri.startsWith("/callbacks/");
  }

  private static ApiException internal() {
    return new ApiException(ErrorCode.INTERNAL_ERROR, "Lỗi nội bộ.", List.of(), null);
  }
}
