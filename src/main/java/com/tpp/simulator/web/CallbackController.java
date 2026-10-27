package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.ingestion.IngestionResult;
import com.tpp.simulator.ingestion.WriteStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C1: {@code POST /callbacks/{serviceCode}}. Mọi quy trình và mọi việc bắt lỗi kho dữ liệu nằm ở
 * {@link CallbackIngestionService} (RD-02); lớp này CHỈ ánh xạ {@link IngestionResult} sang HTTP và
 * ghi đúng một dòng nhật ký qua {@link CallbackAccessLog} (OD-01). Không có {@code catch} cho lỗi
 * truy cập dữ liệu và không chạm kho dữ liệu.
 */
@RestController
public class CallbackController {

  private final CallbackIngestionService ingestion;
  private final ApiErrors errors;
  private final Clock clock;

  public CallbackController(CallbackIngestionService ingestion, ApiErrors errors, Clock clock) {
    this.ingestion = ingestion;
    this.errors = errors;
    this.clock = clock;
  }

  @PostMapping("/callbacks/{serviceCode}")
  public ResponseEntity<?> receive(@PathVariable String serviceCode, HttpServletRequest request)
      throws IOException {
    CallbackLogEntry entry = new CallbackLogEntry().service(serviceCode).httpStatus(500);
    boolean completed = false;
    try {
      Instant receivedAt =
          request.getAttribute(RequestSizeLimitFilter.RECEIVED_AT) instanceof Instant instant
              ? instant
              : clock.instant().truncatedTo(ChronoUnit.MICROS);
      byte[] body = request.getInputStream().readAllBytes();

      IngestionResult result =
          ingestion.receive(
              serviceCode, request.getContentType(), headers(request), body, receivedAt);
      ResponseEntity<?> response = toResponse(result, entry);
      completed = true;
      return response;
    } finally {
      if (!completed) {
        entry.failure("unexpected", "-");
      }
      CallbackAccessLog.emit(entry);
    }
  }

  private ResponseEntity<?> toResponse(IngestionResult result, CallbackLogEntry entry) {
    if (result.failureClass() != null) {
      entry.failure(result.failureClass(), result.sqlState());
    }
    switch (result.kind()) {
      case ACCEPTED -> {
        entry.outcome(result.outcome()).recordId(result.recordId());
        if (result.written()) {
          entry.httpStatus(result.response().status());
          // Response đã cấu hình: không bao giờ có header X-Simulator-Error (BR2.5, BR2.8).
          return ResponseEntity.status(result.response().status())
              .contentType(ApiErrors.JSON_UTF8)
              .body(result.response().body().getBytes(StandardCharsets.UTF_8));
        }
      }
      case REJECTED -> entry.rejectionReason(result.rejectionReason()).recordId(result.recordId());
      default -> {
        // UNKNOWN_SERVICE và SNAPSHOT_FAILED: không có bản ghi, không có lý do từ chối.
      }
    }
    ApiException error = new ApiException(result.errorCode(), result.errorMessage());
    entry.httpStatus(error.code().status().value());
    if (result.writeStatus() == WriteStatus.REJECTED_RECORD_FAILED) {
      return errors.response(error, ApiErrors.RECORD_FAILED_HEADER, "true");
    }
    return errors.response(error);
  }

  private static Map<String, List<String>> headers(HttpServletRequest request) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.put(name, Collections.list(request.getHeaders(name)));
    }
    return headers;
  }
}
