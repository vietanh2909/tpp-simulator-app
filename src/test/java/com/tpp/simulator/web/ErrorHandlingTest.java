package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * NFR3.8, BR2.5: mọi lỗi do ứng dụng sinh (kể cả 404 đường dẫn lạ, 405, 415, 500) đều là
 * ErrorResponse JSON kèm header X-Simulator-Error có giá trị bằng trường error; thông báo không lộ
 * chi tiết nội bộ; không bật CORS.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlingTest extends AbstractIntegrationTest {

  private static void assertAppError(HttpResponse<String> response, int status, String code) {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(code, response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
        response.headers().toString());
    assertTrue(response.body().contains("\"error\":\"" + code + "\""), response.body());
    assertTrue(response.body().contains("\"message\":"), response.body());
  }

  @ParameterizedTest
  @CsvSource({
    "GET,/does/not/exist,404,NOT_FOUND",
    "GET,/,404,NOT_FOUND",
    "GET,/api,404,NOT_FOUND",
    "GET,/api/unknown,404,NOT_FOUND",
    "POST,/api/transactions,405,METHOD_NOT_ALLOWED",
    "PATCH,/api/services/THU_HO,405,METHOD_NOT_ALLOWED",
    "PUT,/api/services,405,METHOD_NOT_ALLOWED",
    "POST,/api/callbacks,405,METHOD_NOT_ALLOWED",
    "DELETE,/api/callbacks/1,405,METHOD_NOT_ALLOWED",
    "GET,/api/mapping-tests,405,METHOD_NOT_ALLOWED",
    "GET,/callbacks/THU_HO,405,METHOD_NOT_ALLOWED",
    "DELETE,/api/transactions/detail,405,METHOD_NOT_ALLOWED"
  })
  void unknownPathsAndWrongMethodsGetTheStandardJsonErrorWithHeader(
      String method, String path, int status, String code) {
    HttpResponse<String> response = send(method, path, null, null);

    assertAppError(response, status, code);
  }

  @Test
  void unknownPathUnderCallbacksIs404Json() {
    HttpResponse<String> response = postJson("/callbacks/", "{}");

    assertAppError(response, 404, "NOT_FOUND");
  }

  @Test
  void errorBodyHasOnlyErrorAndMessageExceptForValidationFailures() {
    JsonNode notFound = jsonBody(get("/api/services/NOPE"));
    JsonNode validation = jsonBody(postJson("/api/services", "{}"));

    assertEquals(2, notFound.size());
    assertTrue(validation.has("fieldErrors"));
    assertTrue(validation.get("fieldErrors").get(0).has("field"));
    assertTrue(validation.get("fieldErrors").get(0).has("message"));
  }

  @Test
  void errorResponsesNeverLeakInternals() {
    String[] bodies = {
      get("/api/services/NOPE").body(),
      postJson("/api/services", "{").body(),
      get("/nope").body(),
      send("PATCH", "/api/services", null, null).body()
    };

    for (String body : bodies) {
      assertFalse(body.contains("Exception"), body);
      assertFalse(body.contains("org.springframework"), body);
      assertFalse(body.contains("at com."), body);
      assertFalse(body.contains("jdbc"), body);
    }
  }

  @Test
  void databaseFailureOnReadIs500WithGenericMessageAndOnlyClassAndSqlStateInTheLog(
      CapturedOutput output) {
    jdbc.execute("ALTER TABLE transactions RENAME TO transactions_hidden");
    HttpResponse<String> response;
    try {
      response = get("/api/transactions");
    } finally {
      jdbc.execute("ALTER TABLE transactions_hidden RENAME TO transactions");
    }

    assertAppError(response, 500, "INTERNAL_ERROR");
    assertFalse(response.body().contains("transactions"), response.body());
    assertFalse(response.body().contains("relation"), response.body());
    String log = output.getAll();
    assertTrue(log.contains("sqlState=42P01"), log);
    assertFalse(log.contains("does not exist"), "không log thông điệp thô của ngoại lệ");
    assertEquals(200, get("/api/transactions").statusCode());
  }

  @Test
  void corsIsNotEnabled() {
    HttpResponse<String> preflight =
        send(
            "OPTIONS",
            "/api/services",
            null,
            null,
            "Origin",
            "http://evil.example",
            "Access-Control-Request-Method",
            "POST");
    HttpResponse<String> simple = get("/api/transactions", "Origin", "http://evil.example");

    assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    assertTrue(simple.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  }

  @Test
  void createdThingsAreNeverReflectedInErrorMessages() {
    HttpResponse<String> response =
        postJson(
            "/api/services", "{\"serviceCode\":\"<img src=x onerror=alert(1)>\",\"name\":\"n\"}");

    assertAppError(response, 400, "VALIDATION_FAILED");
    assertFalse(response.body().contains("onerror"), response.body());
  }
}
