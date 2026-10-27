package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** BR14.2, BR14.3, BR14.6, BR15.1: thứ tự kiểm tra cố định, mã lỗi và lý do được ghi. */
class CallbackOrderingTest extends AbstractIntegrationTest {

  private static final String GOOD_KEY = "k-good";

  @BeforeEach
  void services() {
    createService("OPEN");
    String keyed =
        serviceJson("KEYED")
            .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
            .replace(
                "{\"serviceCode\"",
                "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\""
                    + GOOD_KEY
                    + "\",\"serviceCode\"");
    createServiceFromJson(keyed);
  }

  private String reasonOfLastReceipt() {
    return jdbc.queryForObject(
        "SELECT rejection_reason FROM callbacks ORDER BY record_id DESC LIMIT 1", String.class);
  }

  private void assertRejected(
      HttpResponse<String> response, int status, String error, String reason) {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(error, response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(error, jsonBody(response).get("error").asText());
    assertNull(response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
    assertEquals(reason, reasonOfLastReceipt());
    assertEquals("REJECTED", outcomeOfLastReceipt());
  }

  private String outcomeOfLastReceipt() {
    return jdbc.queryForObject(
        "SELECT outcome FROM callbacks ORDER BY record_id DESC LIMIT 1", String.class);
  }

  @Test
  void anUnknownServiceIs404AndNothingIsStored() {
    HttpResponse<String> response = callback("NO_SUCH", "not json at all");

    assertEquals(404, response.statusCode());
    assertEquals(
        "SERVICE_NOT_FOUND", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(0, count("callbacks"));
  }

  @Test
  void aDeletedServiceIs404AndRecordedEvenWhenItIsAlsoDisabledAndTheRequestIsBroken() {
    jdbc.update("UPDATE services SET deleted = true, enabled = false WHERE service_code = 'KEYED'");

    HttpResponse<String> response =
        send("POST", "/callbacks/KEYED", "text/plain", "{bad", "X-Api-Key", "wrong");

    assertRejected(response, 404, "SERVICE_NOT_FOUND", "SERVICE_DELETED");
  }

  @Test
  void aDisabledServiceWithAWrongKeyAndABrokenBodyIs503BeforeAnythingElse() {
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'KEYED'");

    HttpResponse<String> response =
        send("POST", "/callbacks/KEYED", "text/plain", "{bad", "X-Api-Key", "wrong");

    assertRejected(response, 503, "SERVICE_DISABLED", "SERVICE_DISABLED");
  }

  @Test
  void anEnabledApiKeyServiceChecksTheKeyBeforeContentTypeAndBody() {
    HttpResponse<String> response =
        send("POST", "/callbacks/KEYED", "text/plain", "{bad", "X-Api-Key", "wrong");

    assertRejected(response, 401, "UNAUTHORIZED", "INVALID_API_KEY");
  }

  @Test
  void aMissingKeyIsRecordedAsMissingApiKey() {
    HttpResponse<String> response = callback("KEYED", payload("T1", "00"));

    assertRejected(response, 401, "UNAUTHORIZED", "MISSING_API_KEY");
  }

  @Test
  void withTheRightKeyAWrongContentTypeIs400() {
    HttpResponse<String> response =
        send("POST", "/callbacks/KEYED", "text/plain", payload("T1", "00"), "X-Api-Key", GOOD_KEY);

    assertRejected(response, 400, "UNSUPPORTED_CONTENT_TYPE", "UNSUPPORTED_CONTENT_TYPE");
  }

  @Test
  void withTheRightKeyAndContentTypeABrokenBodyIsInvalidJson() {
    HttpResponse<String> response = callback("KEYED", "{bad", "X-Api-Key", GOOD_KEY);

    assertRejected(response, 400, "INVALID_JSON", "INVALID_JSON");
  }

  @Test
  void aServiceWithoutAuthenticationNeverReturns401() {
    HttpResponse<String> plain = callback("OPEN", payload("T1", "00"));
    HttpResponse<String> withIrrelevantHeaders =
        callback("OPEN", payload("T2", "00"), "X-Api-Key", "whatever", "Authorization", "Bearer x");

    assertEquals(201, plain.statusCode());
    assertEquals(201, withIrrelevantHeaders.statusCode());
    assertEquals(2, count("callbacks"));
  }

  @Test
  void contentTypeParametersAndCaseAreIgnoredButOtherTypesAreRejected() {
    List<String> accepted =
        List.of(
            "application/json",
            "application/json; charset=utf-8",
            "APPLICATION/JSON",
            "Application/Json;charset=UTF-8");
    for (String contentType : accepted) {
      HttpResponse<String> response =
          send("POST", "/callbacks/OPEN", contentType, payload("T", "00"));
      assertEquals(201, response.statusCode(), contentType);
    }
    for (String contentType :
        List.of(
            "application/jsonx",
            "text/json",
            "application/xml",
            "multipart/form-data; boundary=x",
            "application/x-www-form-urlencoded")) {
      HttpResponse<String> response =
          send("POST", "/callbacks/OPEN", contentType, payload("T", "00"));
      assertRejected(response, 400, "UNSUPPORTED_CONTENT_TYPE", "UNSUPPORTED_CONTENT_TYPE");
    }
  }

  @Test
  void aMissingContentTypeIsUnsupported() {
    HttpResponse<String> response = send("POST", "/callbacks/OPEN", null, payload("T", "00"));

    assertRejected(response, 400, "UNSUPPORTED_CONTENT_TYPE", "UNSUPPORTED_CONTENT_TYPE");
  }

  @Test
  void anEmptyOrBrokenBodyIsInvalidJsonAndAnyOtherJsonValueIsAccepted() {
    assertRejected(callback("OPEN", ""), 400, "INVALID_JSON", "INVALID_JSON");
    assertRejected(callback("OPEN", "{\"a\":"), 400, "INVALID_JSON", "INVALID_JSON");
    assertRejected(callback("OPEN", "{\"a\":1} {\"b\":2}"), 400, "INVALID_JSON", "INVALID_JSON");
    for (String body : List.of("[1,2]", "\"text\"", "42", "true", "null")) {
      HttpResponse<String> response = callback("OPEN", body);
      assertEquals(201, response.statusCode(), body);
      assertEquals("MAPPING_ERROR", outcomeOfLastReceipt(), body);
    }
  }

  @Test
  void invalidUtf8BytesAreInvalidJsonAndAreStoredWithReplacementCharacters() {
    byte[] bad = {'{', '"', 'a', '"', ':', (byte) 0xC3, (byte) 0x28, '}'};

    HttpResponse<String> response = sendBytes("POST", "/callbacks/OPEN", JSON, bad);

    assertRejected(response, 400, "INVALID_JSON", "INVALID_JSON");
    String raw = jdbc.queryForObject("SELECT payload_raw_text FROM callbacks", String.class);
    assertTrue(raw.contains("\uFFFD"), raw);
    assertNull(jdbc.queryForObject("SELECT payload_json::text FROM callbacks", String.class));
  }

  @Test
  void aMappingErrorStillReturnsTheConfiguredResponseWithoutErrorHeaders() {
    HttpResponse<String> response = callback("OPEN", "{\"unrelated\":1}");

    assertEquals(201, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());
    assertNull(response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertNull(response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
    assertEquals("MAPPING_ERROR", outcomeOfLastReceipt());
    assertNull(reasonOfLastReceipt());
  }

  @Test
  void aRejectionBodyHasAFixedMessageAndIsStoredAsTheResponseReturned() {
    HttpResponse<String> missing = callback("KEYED", payload("T1", "00"));
    String storedBody =
        jdbc.queryForObject("SELECT response_body_returned::text FROM callbacks", String.class);
    HttpResponse<String> wrong = callback("KEYED", payload("T1", "00"), "X-Api-Key", "nope");

    assertEquals(jsonBody(missing).get("message"), jsonBody(wrong).get("message"));
    JsonNode stored = jsonBodyOf(storedBody);
    assertEquals("UNAUTHORIZED", stored.get("error").asText());
    assertEquals(jsonBody(missing).get("message").asText(), stored.get("message").asText());
    assertEquals(
        401,
        jdbc.queryForObject(
            "SELECT response_status_returned FROM callbacks LIMIT 1", Integer.class));
    assertFalse(missing.body().contains(GOOD_KEY));
    assertFalse(wrong.body().contains("nope"));
  }

  @Test
  void rejectedReceiptsNeverEnterTransactions() {
    assertEquals(201, callback("KEYED", payload("TX", "00"), "X-Api-Key", GOOD_KEY).statusCode());
    int before = jdbc.queryForObject("SELECT callback_count FROM transactions", Integer.class);

    callback("KEYED", payload("TX", "00"), "X-Api-Key", "bad");
    callback("KEYED", payload("TX", "00"));

    assertEquals(1, count("transactions"));
    assertEquals(
        before, jdbc.queryForObject("SELECT callback_count FROM transactions", Integer.class));
    assertEquals(3, count("callbacks"));
  }

  @Test
  void aRejectedReceiptReferencesTheCurrentConfigVersionAndUsesTheClockForReceivedAt() {
    clock.set(java.time.Instant.parse("2026-10-19T03:00:00.987654Z"));
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'OPEN'");

    callback("OPEN", payload("T1", "00"));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM callbacks");
    assertEquals(
        java.time.Instant.parse("2026-10-19T03:00:00.987654Z"),
        ((java.sql.Timestamp) row.get("received_at")).toInstant());
    assertEquals(
        jdbc.queryForObject(
            "SELECT version_id FROM service_config_versions WHERE service_code = 'OPEN'",
            Long.class),
        ((Number) row.get("config_version_id")).longValue());
  }

  @Test
  void twoConcurrentRejectionsCreateTwoIndependentRecords() {
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'OPEN'");
    List<Integer> statuses =
        com.tpp.simulator.support.ParallelRunner.runAll(
            List.of(
                () -> callback("OPEN", payload("A", "00")).statusCode(),
                () -> callback("OPEN", payload("B", "00")).statusCode()));

    assertEquals(List.of(503, 503), statuses);
    assertEquals(2, count("callbacks"));
  }

  private JsonNode jsonBodyOf(String text) {
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
