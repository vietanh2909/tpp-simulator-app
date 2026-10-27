package com.tpp.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Bảng mã lỗi của hợp đồng (functional-spec mục 4): mỗi mã ứng với đúng một mã HTTP. */
class ErrorCodeMappingTest {

  @Test
  void everyErrorCodeMapsToTheContractHttpStatus() {
    Map<ErrorCode, HttpStatus> expected =
        Map.ofEntries(
            Map.entry(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.UNSUPPORTED_CONTENT_TYPE, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.INVALID_JSON, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.SERVICE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.TRANSACTION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.CALLBACK_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED),
            Map.entry(ErrorCode.SERVICE_CODE_DUPLICATE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.SERVICE_DISABLED, HttpStatus.SERVICE_UNAVAILABLE));

    assertEquals(expected.size(), ErrorCode.values().length);
    for (ErrorCode code : ErrorCode.values()) {
      assertEquals(expected.get(code), code.status(), code.name());
    }
  }

  @Test
  void aTooManyRequestsCodeDoesNotExistBecauseThereIsNoRateLimiting() {
    for (ErrorCode code : ErrorCode.values()) {
      assertEquals(false, code.status().value() == 429, code.name());
    }
  }
}
